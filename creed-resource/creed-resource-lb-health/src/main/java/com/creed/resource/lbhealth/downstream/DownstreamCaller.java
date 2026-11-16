package com.creed.resource.lbhealth.downstream;

import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The load-balanced business call wrapped in a resilience4j circuit breaker (asked for, 2026-11-17): one breaker per
 * <em>backend</em> name ({@code resilience4j.circuitbreaker.instances.<name>}), so its window counts the calls to the
 * whole service - every instance the load balancer hands out lands in the same window.
 *
 * <p>The breaker is taken from the registry, never created here: {@code registry.circuitBreaker(unknownName)} would
 * quietly make one with the default config, and the experiment would then run against settings nobody wrote down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownstreamCaller {

    static final String URL = "http://" + LbHealthConfiguration.TARGET_SERVICE_ID + "/api/lb-health/target/whoami";

    private final RestTemplate loadBalancedRestTemplate;
    private final CircuitBreakerRegistry registry;

    /** Every state change on one line - {@code [CB] backendB CLOSED -> OPEN failureRate=…}. */
    @PostConstruct
    void logTransitions() {
        registry.getAllCircuitBreakers().forEach(cb -> cb.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.Metrics m = cb.getMetrics();
            log.info("[CB] {} {} failureRate={} calls={} failed={}", event.getCircuitBreakerName(),
                    event.getStateTransition(), m.getFailureRate(), m.getNumberOfBufferedCalls(),
                    m.getNumberOfFailedCalls());
        }));
    }

    /**
     * One call through the breaker. A rejected call throws {@code CallNotPermittedException} without touching the
     * load balancer; "no instances available" (the health check emptied the list) is an {@code IllegalStateException}
     * and counts as a failure, like any exception under the default {@code recordExceptions}.
     */
    public Map<?, ?> call(String backend) {
        return breaker(backend).executeSupplier(() -> loadBalancedRestTemplate.getForObject(URL, Map.class));
    }

    public CircuitBreaker breaker(String backend) {
        return registry.find(backend).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "no circuit breaker '" + backend + "' - configured: "
                        + registry.getAllCircuitBreakers().stream().map(CircuitBreaker::getName).toList()));
    }
}
