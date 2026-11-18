package com.creed.resource.lbhealth.downstream;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

/**
 * "The backend keeps calling the downstream API": {@code rps} calls per second to one cluster through
 * {@link DownstreamCaller} for {@code seconds}, counted per second - one {@code [LOAD]} line and one {@link Second} row
 * each: what the bulkhead and the breaker let through, how it ended, and the load balancer's list size at that moment.
 *
 * <ul>
 *   <li>{@code concurrent=false} (default): one virtual thread, one call after the other. A call slower than
 *       {@code 1/rps} delays the next, so the achieved rate can fall below {@code rps}; the bulkhead never sees more
 *       than one call. Right for the circuit-breaker scenarios.</li>
 *   <li>{@code concurrent=true}: every call starts on its own virtual thread at its slot, whether or not the earlier
 *       ones have returned (an open model, like real users). A slow cluster then piles calls up - which is what a
 *       bulkhead is for. A row counts the calls that <em>ended</em> in that second. After the last second the run
 *       keeps writing one row per second while calls are still waiting or in flight (a bulkhead with a
 *       {@code max-wait-duration} queues them), for at most {@value #MAX_DRAIN_SECONDS} s.</li>
 * </ul>
 * One run at a time; a new one stops the old.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadRun {

    /**
     * @param ok           answered 2xx
     * @param failed       reached an instance (the load balancer handed one out) and failed: 5xx, 4xx, refused, timed out
     * @param noInstance   the load balancer's list was empty - recorded as a failure, but nothing was sent
     * @param rejected     {@code CallNotPermittedException}: the breaker is OPEN (or HALF_OPEN's trial calls are taken)
     * @param bulkheadFull {@code BulkheadFullException}: {@code maxConcurrentCalls} calls to this cluster in flight
     * @param waiting      calls started but still waiting for a bulkhead permit ({@code max-wait-duration}) at the end of
 *                     the second - concurrent mode only
 * @param inFlight     calls inside the bulkhead at the end of the second
     * @param lbInstances  the load balancer's list size at the end of the second
     */
    public record Second(long second, String state, int ok, int failed, int noInstance, int rejected, int bulkheadFull,
                         int waiting, int inFlight, int lbInstances) {
    }

    public record Status(String cluster, int rps, int seconds, boolean concurrent, Instant startedAt, boolean running,
                         List<Second> timeline) {
    }

    static final int MAX_DRAIN_SECONDS = 120;
    private static final int OK = 0, FAILED = 1, NO_INSTANCE = 2, REJECTED = 3, BULKHEAD_FULL = 4;

    private final DownstreamCaller caller;
    private final LoadBalancerClientFactory loadBalancerClientFactory;

    private volatile Status status;
    private volatile Thread thread;

    public synchronized Status start(String cluster, int rps, int seconds, boolean concurrent) {
        DownstreamCaller.Guard guard = caller.guard(cluster); // 400 for an unknown name, before anything starts
        stop();
        List<Second> timeline = new ArrayList<>();
        Instant startedAt = Instant.now();
        status = new Status(cluster, rps, seconds, concurrent, startedAt, true, timeline);
        thread = Thread.ofVirtual().name("load-" + cluster)
                .start(() -> run(guard, rps, seconds, concurrent, timeline));
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
            return new Status(s.cluster(), s.rps(), s.seconds(), s.concurrent(), s.startedAt(),
                    t != null && t.isAlive(), List.copyOf(s.timeline()));
        }
    }

    private void run(DownstreamCaller.Guard guard, int rps, int seconds, boolean concurrent, List<Second> timeline) {
        String cluster = guard.cluster();
        long periodNanos = Duration.ofSeconds(1).toNanos() / rps;
        long start = System.nanoTime();
        AtomicIntegerArray counts = new AtomicIntegerArray(5);
        Phaser outstanding = new Phaser(1); // the loop itself is one party
        log.info("[LOAD] start cluster={} rps={} seconds={} concurrent={}", cluster, rps, seconds, concurrent);
        int sec = 0;
        for (; sec < seconds && !Thread.currentThread().isInterrupted(); sec++) {
            for (int i = 0; i < rps && !Thread.currentThread().isInterrupted(); i++) {
                long due = start + (sec * (long) rps + i) * periodNanos;
                LockSupport.parkNanos(due - System.nanoTime());
                if (concurrent) {
                    outstanding.register();
                    Thread.ofVirtual().start(() -> {
                        try {
                            counts.incrementAndGet(callOnce(cluster));
                        } finally {
                            outstanding.arriveAndDeregister();
                        }
                    });
                } else {
                    counts.incrementAndGet(callOnce(cluster));
                }
            }
            // concurrent: wait for the end of the second, so a row is "ended in this second"
            LockSupport.parkNanos(start + (sec + 1L) * Duration.ofSeconds(1).toNanos() - System.nanoTime());
            row(guard, sec + 1L, counts, timeline, outstanding);
        }
        if (concurrent) {
            // drain: one row per second while calls wait for a permit or are in flight; a hung cluster (no read
            // timeout) is not waited for beyond MAX_DRAIN_SECONDS
            int last = sec + MAX_DRAIN_SECONDS;
            while (outstanding.getUnarrivedParties() > 1 && sec < last && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(start + (sec + 1L) * Duration.ofSeconds(1).toNanos() - System.nanoTime());
                sec++;
                row(guard, sec, counts, timeline, outstanding);
            }
            if (outstanding.getUnarrivedParties() > 1) {
                log.warn("[LOAD] cluster={} {} calls still waiting or in flight at the end", cluster,
                        outstanding.getUnarrivedParties() - 1);
            }
        }
        log.info("[LOAD] end cluster={}", cluster);
    }

    private int callOnce(String cluster) {
        try {
            caller.call(cluster);
            return OK;
        } catch (BulkheadFullException e) {
            return BULKHEAD_FULL;
        } catch (CallNotPermittedException e) {
            return REJECTED;
        } catch (IllegalStateException e) {
            // LoadBalancerInterceptor / BlockingLoadBalancerClient: "No instances available for ..."
            return NO_INSTANCE;
        } catch (RuntimeException e) {
            return FAILED;
        }
    }

    private void row(DownstreamCaller.Guard guard, long second, AtomicIntegerArray counts, List<Second> timeline,
                     Phaser outstanding) {
        int inFlight = guard.inFlight().get();
        // parties = the loop + every started call not yet ended; those not inside the bulkhead wait for a permit
        int waiting = Math.max(0, outstanding.getUnarrivedParties() - 1 - inFlight);
        Second row = new Second(second, guard.breaker().getState().name(), counts.getAndSet(OK, 0),
                counts.getAndSet(FAILED, 0), counts.getAndSet(NO_INSTANCE, 0), counts.getAndSet(REJECTED, 0),
                counts.getAndSet(BULKHEAD_FULL, 0), waiting, inFlight, lbInstances(guard.cluster()));
        synchronized (timeline) {
            timeline.add(row);
        }
        log.info("[LOAD] t={}s cluster={} state={} lb={} ok={} failed={} noInstance={} rejected={} bulkheadFull={}"
                        + " waiting={} inFlight={}", row.second(), guard.cluster(), row.state(), row.lbInstances(), row.ok(),
                row.failed(), row.noInstance(), row.rejected(), row.bulkheadFull(), row.waiting(), row.inFlight());
    }

    private int lbInstances(String cluster) {
        ServiceInstanceListSupplier supplier = loadBalancerClientFactory.getInstance(cluster,
                ServiceInstanceListSupplier.class);
        List<ServiceInstance> list = supplier == null ? null : supplier.get().next().block(Duration.ofSeconds(1));
        return list == null ? -1 : list.size();
    }
}
