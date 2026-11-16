package com.creed.resource.lbhealth.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Spring's {@link HealthCheckServiceInstanceListSupplier} with its inner stream made visible. Behaviour is
 * unchanged: both overrides call {@code super} and only add side effects.
 *
 * <p>Spring Cloud 5.0.3 builds the stream as
 * {@code Flux.defer(delegate).repeatWhen(refetch).switchMap(this::healthCheckFlux).replay(1)}, and
 * {@link #healthCheckFlux} itself is {@code Flux.defer(oneRound).repeatWhen(interval)}. So:
 * <ul>
 *   <li>{@code [HC-STREAM] start} - {@code switchMap} subscribed to a new inner stream (start-up or refetch);</li>
 *   <li>{@code [HC-RESULT] size=N} - a round finished (one line per round with {@code update-results-list=false});</li>
 *   <li>{@code [HC-STREAM] cancelled by refetch} - the next refetch replaced the inner stream. Between rounds that
 *   is normal (it only drops the {@code interval} timer); <b>mid-round</b> it discards the round's result, and if
 *   that happens every time, {@code get()} never moves past the last completed list (WARN).</li>
 * </ul>
 *
 * <p>Built through {@code builder.with(...)} instead of {@code withBlockingHealthChecks(...)}, because those
 * builder methods always instantiate the stock class; the alive function passed in is a copy of the stock one
 * (see {@link HealthCheckClients}).
 */
@Slf4j
public class ObservableHealthCheckServiceInstanceListSupplier extends HealthCheckServiceInstanceListSupplier {

    /** True from the first probe of a round until that round's list is emitted. */
    private final AtomicBoolean roundInFlight = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong midRoundCancellations = new AtomicLong();

    public ObservableHealthCheckServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory,
            BiFunction<ServiceInstance, String, Mono<Boolean>> aliveFunction) {
        super(delegate, loadBalancerClientFactory, aliveFunction);
    }

    @Override
    protected Flux<List<ServiceInstance>> healthCheckFlux(List<ServiceInstance> instances) {
        return super.healthCheckFlux(instances)
                .doOnSubscribe(subscription -> log.info("[HC-STREAM] {} start service={} instances={}",
                        DebugLog.now(), getServiceId(), instances.size()))
                .doOnNext(alive -> {
                    roundInFlight.set(false);
                    log.info("[HC-RESULT] {} round={} size={}/{} alive={}", DebugLog.now(),
                            rounds.incrementAndGet(), alive.size(), instances.size(),
                            alive.stream().map(ServiceInstance::getInstanceId).toList());
                })
                .doOnCancel(this::logCancel);
    }

    /** Called for every instance when a round is assembled - i.e. at the very start of the round. */
    @Override
    protected Mono<Boolean> isAlive(ServiceInstance serviceInstance) {
        roundInFlight.set(true);
        return super.isAlive(serviceInstance);
    }

    @Override
    public void destroy() {
        destroyed.set(true);
        super.destroy();
    }

    private void logCancel() {
        if (destroyed.get()) {
            log.info("[HC-STREAM] {} cancelled by shutdown", DebugLog.now());
        } else if (roundInFlight.getAndSet(false)) {
            midRoundCancellations.incrementAndGet();
            log.warn("[HC-STREAM] {} cancelled by refetch MID-ROUND - this round's result is discarded; if it"
                    + " repeats, a round outlasts refetch-instances-interval and get() is stuck on the last"
                    + " completed list", DebugLog.now());
        } else {
            log.info("[HC-STREAM] {} cancelled by refetch (between rounds: only the interval timer is dropped)",
                    DebugLog.now());
        }
    }

    public long completedRounds() {
        return rounds.get();
    }

    public long midRoundCancellations() {
        return midRoundCancellations.get();
    }
}
