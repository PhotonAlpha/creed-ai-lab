package com.creed.resource.lbhealth.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>{@code [HC-ROUND] start round=N trigger=REFETCH|INTERVAL} - a round begins: the first round of an inner stream
 *   is the refetch's (start-up included - discovery's first list is a fetch too), every later one the interval
 *   timer's ({@code repeatWhen});</li>
 *   <li>{@code [HC-RESULT] round=N trigger=... size=N} - that round finished (one line per round with
 *   {@code update-results-list=false}, one per answering instance with {@code true});</li>
 *   <li>{@code [HC-STREAM] cancelled by refetch} - the next refetch replaced the inner stream. Between rounds that
 *   is normal (it only drops the {@code interval} timer); <b>mid-round</b> it discards the round's result, and if
 *   that happens every time, {@code get()} never moves past the last completed list (WARN).</li>
 * </ul>
 *
 * <p>Every probe runs with MDC {@value #MDC_TRIGGER} / {@value #MDC_ROUND} (asked for, 2026-11-17), so the Logbook
 * request / response lines of a probe say which round, started by what, they belong to. Spring calls
 * {@link #healthCheckFlux} once per refetched list (switchMap's mapper) and {@link #isAlive} for every instance when a
 * round is assembled, before any probe is subscribed - those two calls are the hooks. The MDC is set around the
 * probe's subscription (a blocking probe runs inside it) and written into the Reactor Context, which
 * {@link HealthCheckMdc} carries to the thread a WebClient response arrives on.
 *
 * <p>Built through {@code builder.with(...)} instead of {@code withBlockingHealthChecks(...)}, because those
 * builder methods always instantiate the stock class; the alive function passed in is a copy of the stock one
 * (see {@link HealthCheckClients}).
 */
@Slf4j
public class ObservableHealthCheckServiceInstanceListSupplier extends HealthCheckServiceInstanceListSupplier {

    public static final String MDC_TRIGGER = "hcTrigger";
    public static final String MDC_ROUND = "hcRound";

    /** What started a round: a refetched instance list (and start-up), or the {@code interval} timer. */
    public enum Trigger { REFETCH, INTERVAL }

    private record Round(long number, Trigger trigger) {
    }

    /** The trigger the next round to start gets: REFETCH once a new list arrives, INTERVAL after that. */
    private final AtomicReference<Trigger> nextTrigger = new AtomicReference<>(Trigger.REFETCH);
    private final AtomicLong startedRounds = new AtomicLong();
    private volatile Round current = new Round(0, Trigger.REFETCH);

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
        // switchMap's mapper: called once per refetched list, right before its first round is assembled
        nextTrigger.set(Trigger.REFETCH);
        return super.healthCheckFlux(instances)
                .doOnSubscribe(subscription -> log.info("[HC-STREAM] {} start service={} instances={}",
                        DebugLog.now(), getServiceId(), instances.size()))
                .doOnNext(alive -> {
                    roundInFlight.set(false);
                    rounds.incrementAndGet();
                    Round round = current;
                    log.info("[HC-RESULT] {} round={} trigger={} size={}/{} alive={}", DebugLog.now(),
                            round.number(), round.trigger(), alive.size(), instances.size(),
                            alive.stream().map(ServiceInstance::getInstanceId).toList());
                })
                .doOnCancel(this::logCancel);
    }

    /** Called for every instance when a round is assembled - i.e. at the very start of the round. */
    @Override
    protected Mono<Boolean> isAlive(ServiceInstance serviceInstance) {
        if (!roundInFlight.getAndSet(true)) {
            current = new Round(startedRounds.incrementAndGet(), nextTrigger.getAndSet(Trigger.INTERVAL));
            log.info("[HC-ROUND] {} start round={} trigger={}", DebugLog.now(), current.number(), current.trigger());
        }
        return withMdc(super.isAlive(serviceInstance), current);
    }

    /**
     * The probe with the round in the MDC: put around its subscription (a blocking probe runs inside it, on this
     * thread) and taken off right after, so the Reactor thread keeps nothing; and in the Reactor Context, for a
     * WebClient probe's response, which arrives on another thread.
     */
    private static Mono<Boolean> withMdc(Mono<Boolean> alive, Round round) {
        String trigger = round.trigger().name();
        String number = String.valueOf(round.number());
        Mono<Boolean> scoped = Mono.from(subscriber -> {
            try (MDC.MDCCloseable t = MDC.putCloseable(MDC_TRIGGER, trigger);
                 MDC.MDCCloseable r = MDC.putCloseable(MDC_ROUND, number)) {
                alive.subscribe(subscriber);
            }
        });
        return scoped.contextWrite(context -> context.put(MDC_TRIGGER, trigger).put(MDC_ROUND, number));
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
