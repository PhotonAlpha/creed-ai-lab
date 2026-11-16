package com.creed.resource.lbhealth.downstream;

import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * "The backend keeps calling the downstream API": {@code rps} calls per second through {@link DownstreamCaller} for
 * {@code seconds}, on one virtual thread, counted per second. Each second is one {@code [LOAD]} line and one
 * {@link Second} row - what the breaker let through, how it ended, and the load balancer's list size at that moment.
 *
 * <p>Calls are made one after the other: a call slower than {@code 1/rps} (a hanging instance) delays the next, so
 * the achieved rate can fall below {@code rps} - shown as the row's total. One run at a time; a new one stops the old.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadRun {

    /**
     * @param ok          answered 2xx
     * @param failed      reached an instance (the load balancer handed one out) and failed: 503, refused, timed out
     * @param noInstance  the load balancer's list was empty - recorded as a failure, but nothing was sent
     * @param rejected    {@code CallNotPermittedException}: the breaker is OPEN (or HALF_OPEN's trial calls are taken)
     * @param lbInstances the health-checked list's size at the end of the second
     */
    public record Second(long second, String state, int ok, int failed, int noInstance, int rejected,
                         int lbInstances) {
    }

    public record Status(String backend, int rps, int seconds, Instant startedAt, boolean running,
                         List<Second> timeline) {
    }

    private final DownstreamCaller caller;
    private final LoadBalancerClientFactory loadBalancerClientFactory;

    private volatile Status status;
    private volatile Thread thread;

    public synchronized Status start(String backend, int rps, int seconds) {
        CircuitBreaker breaker = caller.breaker(backend); // 400 for an unknown name, before anything starts
        stop();
        List<Second> timeline = new ArrayList<>();
        Instant startedAt = Instant.now();
        status = new Status(backend, rps, seconds, startedAt, true, timeline);
        thread = Thread.ofVirtual().name("load-" + backend).start(() -> run(breaker, rps, seconds, startedAt, timeline));
        return status;
    }

    public synchronized void stop() {
        Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
    }

    public Status status() {
        Status s = status;
        if (s == null) {
            return null;
        }
        synchronized (s.timeline()) {
            Thread t = thread;
            return new Status(s.backend(), s.rps(), s.seconds(), s.startedAt(), t != null && t.isAlive(),
                    List.copyOf(s.timeline()));
        }
    }

    private void run(CircuitBreaker breaker, int rps, int seconds, Instant startedAt, List<Second> timeline) {
        long periodNanos = Duration.ofSeconds(1).toNanos() / rps;
        long start = System.nanoTime();
        log.info("[LOAD] start backend={} rps={} seconds={}", breaker.getName(), rps, seconds);
        for (int sec = 0; sec < seconds && !Thread.currentThread().isInterrupted(); sec++) {
            int ok = 0, failed = 0, noInstance = 0, rejected = 0;
            for (int i = 0; i < rps && !Thread.currentThread().isInterrupted(); i++) {
                long due = start + (sec * (long) rps + i) * periodNanos;
                LockSupport.parkNanos(due - System.nanoTime());
                try {
                    caller.call(breaker.getName());
                    ok++;
                } catch (CallNotPermittedException e) {
                    rejected++;
                } catch (IllegalStateException e) {
                    // LoadBalancerInterceptor / BlockingLoadBalancerClient: "No instances available for ..."
                    noInstance++;
                } catch (RuntimeException e) {
                    failed++;
                }
            }
            Second row = new Second(sec + 1L, breaker.getState().name(), ok, failed, noInstance, rejected,
                    lbInstances());
            synchronized (timeline) {
                timeline.add(row);
            }
            log.info("[LOAD] t={}s backend={} state={} lb={} ok={} failed={} noInstance={} rejected={}", row.second(),
                    breaker.getName(), row.state(), row.lbInstances(), ok, failed, noInstance, rejected);
        }
        log.info("[LOAD] end backend={} started {}", breaker.getName(), startedAt);
    }

    private int lbInstances() {
        ServiceInstanceListSupplier supplier = loadBalancerClientFactory.getInstance(
                LbHealthConfiguration.TARGET_SERVICE_ID, ServiceInstanceListSupplier.class);
        List<ServiceInstance> list = supplier == null ? null : supplier.get().next().block(Duration.ofSeconds(1));
        return list == null ? -1 : list.size();
    }
}
