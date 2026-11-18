package com.creed.resource.lbhealth.downstream;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The load-balanced business call, guarded per <em>cluster</em> (asked for, 2026-11-18): a cluster is a service id under
 * {@code spring.cloud.discovery.client.simple.instances}, and each one gets its own {@link CircuitBreaker} and its own
 * {@link Bulkhead}, both named after it. One cluster's failures never open another cluster's breaker, and one slow
 * cluster cannot take another's concurrency.
 *
 * <p>Both come from {@code configs.default} unless {@code resilience4j.circuitbreaker.instances.<cluster>} /
 * {@code resilience4j.bulkhead.instances.<cluster>} overrides them. They are created here at start-up for every
 * cluster in discovery - not on the first call - so {@code GET /cb} lists them all and the transition log is attached
 * before traffic. A name that is not a cluster is a 400, never a new breaker: {@code registry.circuitBreaker(typo)}
 * would quietly create one.
 *
 * <p>Nesting: {@code Bulkhead( CircuitBreaker( call ) )} - deliberately NOT resilience4j's annotation order (tried on
 * 2026-11-19 and reverted the same day, docs §4.4). A full bulkhead rejects before the breaker is asked, so "we are at
 * our own concurrency limit" is never recorded as the cluster's success or failure, and the time a call waits for a
 * permit ({@code max-wait-duration}) is not part of the duration the breaker measures. With the bulkhead inside, a
 * {@code BulkheadFullException} was recorded as a SUCCESS (the predicate's {@code false}) and a 60 s wait could make
 * every call a slow call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownstreamCaller {

    static final String PATH = "/api/lb-health/target/whoami";

    private final RestTemplate loadBalancedRestTemplate;
    private final CircuitBreakerRegistry circuitBreakers;
    private final BulkheadRegistry bulkheads;
    private final SimpleDiscoveryProperties discovery;

    private final Map<String, Guard> guards = new TreeMap<>();

    /**
     * One cluster's protection.
     *
     * @param inFlight calls inside the bulkhead right now (what reaches the load balancer)
     * @param peak     the most there ever were at once since the last reset - never above the bulkhead's limit
     */
    public record Guard(String cluster, CircuitBreaker breaker, Bulkhead bulkhead, AtomicInteger inFlight,
                        AtomicInteger peak) {
    }

    @PostConstruct
    void createGuards() {
        for (String cluster : discovery.getInstances().keySet()) {
            CircuitBreaker breaker = circuitBreakers.circuitBreaker(cluster);
            Bulkhead bulkhead = bulkheads.bulkhead(cluster);
            breaker.getEventPublisher().onStateTransition(event -> {
                CircuitBreaker.Metrics m = breaker.getMetrics();
                log.info("[CB] {} {} failureRate={} calls={} failed={}", event.getCircuitBreakerName(),
                        event.getStateTransition(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                        m.getNumberOfFailedCalls());
            });
            guards.put(cluster, new Guard(cluster, breaker, bulkhead, new AtomicInteger(), new AtomicInteger()));
        }
        guards.values().forEach(g -> log.info("[CB] cluster={} breaker: window={} {} minimumCalls={} threshold={}%"
                        + " | bulkhead: maxConcurrentCalls={} maxWait={}", g.cluster(),
                g.breaker().getCircuitBreakerConfig().getSlidingWindowType(),
                g.breaker().getCircuitBreakerConfig().getSlidingWindowSize(),
                g.breaker().getCircuitBreakerConfig().getMinimumNumberOfCalls(),
                g.breaker().getCircuitBreakerConfig().getFailureRateThreshold(),
                g.bulkhead().getBulkheadConfig().getMaxConcurrentCalls(),
                g.bulkhead().getBulkheadConfig().getMaxWaitDuration()));
    }

    /**
     * One call to {@code http://<cluster>/api/lb-health/target/whoami}. Throws {@code BulkheadFullException} (too many
     * calls to this cluster in flight), {@code CallNotPermittedException} (its breaker is OPEN), or whatever the call
     * threw - recorded by the breaker as {@link DownstreamCircuitBreakerFailurePredicate} decides.
     */
    public Map<?, ?> call(String cluster) {
        Guard guard = guard(cluster);
        Supplier<Map<?, ?>> call = () -> {
            int now = guard.inFlight().incrementAndGet();
            guard.peak().accumulateAndGet(now, Math::max);
            try {
                return loadBalancedRestTemplate.getForObject("http://" + cluster + PATH, Map.class);
            } finally {
                guard.inFlight().decrementAndGet();
            }
        };
        return Bulkhead.decorateSupplier(guard.bulkhead(), CircuitBreaker.decorateSupplier(guard.breaker(), call)).get();
    }

    public Guard guard(String cluster) {
        Guard guard = guards.get(cluster);
        if (guard == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "no cluster '" + cluster + "' - configured: " + guards.keySet());
        }
        return guard;
    }

    public Map<String, Guard> guards() {
        return guards;
    }
}
