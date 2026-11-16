package com.creed.resource.lbhealth.lb;

import org.springframework.cloud.client.ServiceInstance;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * A scriptable alive function for {@code HealthCheckServiceInstanceListSupplier}, run on virtual time: each instance
 * answers healthy / unhealthy / with an error / never, after a chosen delay.
 *
 * <p>Two execution models:
 * <ul>
 *   <li>{@link Mode#ASYNC} - like {@code withHealthChecks(WebClient)}: every probe of a round runs concurrently
 *   ({@code Mono.delay}).</li>
 *   <li>{@link Mode#SERIAL_BLOCKING} - like {@code withBlockingHealthChecks(RestTemplate)}: the probes block the one
 *   thread that subscribes to them, so they run one after the other. Modelled as a single virtual thread with a job
 *   queue. A running job cannot be interrupted (a blocking HTTP call is not cancellable), while a job still in the
 *   queue is dropped when its round is cancelled. Delays here stand for the probe's HTTP timeout, which in blocking
 *   mode is the only real bound.</li>
 * </ul>
 * Every probe start is recorded (virtual time) so tests can see when rounds ran.
 */
final class ScriptedProbes implements BiFunction<ServiceInstance, String, Mono<Boolean>> {

    enum Mode { ASYNC, SERIAL_BLOCKING }

    enum Outcome { HEALTHY, UNHEALTHY, ERROR, HANG }

    record Behaviour(Outcome outcome, Duration delay) {
    }

    private final VirtualTimeScheduler scheduler;
    private final Mode mode;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, List<Duration>> starts = new ConcurrentHashMap<>();
    private final VirtualThread thread = new VirtualThread();
    private Behaviour fallback = new Behaviour(Outcome.HEALTHY, Duration.ofSeconds(1));

    ScriptedProbes(VirtualTimeScheduler scheduler, Mode mode) {
        this.scheduler = scheduler;
        this.mode = mode;
    }

    /** Every instance not scripted individually. */
    ScriptedProbes all(Outcome outcome, Duration delay) {
        behaviours.clear();
        fallback = new Behaviour(outcome, delay);
        return this;
    }

    ScriptedProbes set(String instanceId, Outcome outcome, Duration delay) {
        behaviours.put(instanceId, new Behaviour(outcome, delay));
        return this;
    }

    /** Virtual times at which the probe of {@code instanceId} started. */
    List<Duration> startsOf(String instanceId) {
        return List.copyOf(starts.getOrDefault(instanceId, List.of()));
    }

    @Override
    public Mono<Boolean> apply(ServiceInstance instance, String healthCheckPath) {
        String id = instance.getInstanceId();
        return switch (mode) {
            case ASYNC -> Mono.defer(() -> {
                recordStart(id);
                Behaviour behaviour = behaviourOf(id);
                return behaviour.outcome() == Outcome.HANG ? Mono.never()
                        : Mono.delay(behaviour.delay(), scheduler).flatMap(tick -> result(behaviour.outcome()));
            });
            case SERIAL_BLOCKING -> new Mono<>() {
                @Override
                public void subscribe(CoreSubscriber<? super Boolean> actual) {
                    thread.submit(new Job(id, actual));
                }
            };
        };
    }

    private Behaviour behaviourOf(String id) {
        return behaviours.getOrDefault(id, fallback);
    }

    private void recordStart(String id) {
        starts.computeIfAbsent(id, key -> new ArrayList<>()).add(Duration.ofMillis(scheduler.now(TimeUnit.MILLISECONDS)));
    }

    private static Mono<Boolean> result(Outcome outcome) {
        return switch (outcome) {
            case HEALTHY -> Mono.just(true);
            case UNHEALTHY -> Mono.just(false);
            case ERROR -> Mono.error(new IllegalStateException("probe failed"));
            case HANG -> Mono.never();
        };
    }

    /** One probe waiting for, or running on, the virtual thread. */
    private final class Job implements Subscription {
        final String instanceId;
        final CoreSubscriber<? super Boolean> actual;
        volatile boolean cancelled;

        Job(String instanceId, CoreSubscriber<? super Boolean> actual) {
            this.instanceId = instanceId;
            this.actual = actual;
        }

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final class VirtualThread {
        private final Deque<Job> queue = new ArrayDeque<>();
        private boolean busy;

        void submit(Job job) {
            queue.add(job);
            if (!busy) {
                runNext();
            }
        }

        private void runNext() {
            Job job;
            while ((job = queue.poll()) != null) {
                // Hand out the Subscription now: a round cancelled while this job was queued cancels it here, and
                // the job is skipped without occupying the thread.
                job.actual.onSubscribe(job);
                if (!job.cancelled) {
                    break;
                }
            }
            if (job == null) {
                busy = false;
                return;
            }
            busy = true;
            recordStart(job.instanceId);
            Behaviour behaviour = behaviourOf(job.instanceId);
            if (behaviour.outcome() == Outcome.HANG) {
                return; // the thread is stuck for good - exactly what a probe without an HTTP timeout does
            }
            Job running = job;
            scheduler.schedule(() -> {
                if (!running.cancelled) {
                    switch (behaviour.outcome()) {
                        case HEALTHY -> { running.actual.onNext(true); running.actual.onComplete(); }
                        case UNHEALTHY -> { running.actual.onNext(false); running.actual.onComplete(); }
                        case ERROR -> running.actual.onError(new IllegalStateException("probe failed"));
                        case HANG -> throw new IllegalStateException("unreachable");
                    }
                }
                runNext();
            }, behaviour.delay().toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
