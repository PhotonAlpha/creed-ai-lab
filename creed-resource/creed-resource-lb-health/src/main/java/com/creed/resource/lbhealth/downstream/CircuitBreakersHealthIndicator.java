package com.creed.resource.lbhealth.downstream;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties.InstanceProperties;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /actuator/health} component {@code circuitBreakers}: what {@code register-health-indicator: true} was meant to
 * give, rebuilt for Boot 4. resilience4j-spring-boot3 2.3.0's own {@code CircuitBreakersHealthIndicator} implements
 * Boot 3's {@code org.springframework.boot.actuate.health.HealthIndicator}; Boot 4 moved it to
 * {@code org.springframework.boot.health.contributor}, so that auto-configuration never registers anything.
 *
 * <p>Same rules as the original: only breakers whose (merged) properties say {@code register-health-indicator: true}
 * are listed, one detail per cluster; an OPEN breaker turns the component DOWN only if that breaker also has
 * {@code allow-health-indicator-to-fail: true} (default false). Keep it false: the health of THIS app would otherwise go
 * DOWN because a DOWNSTREAM cluster is broken, and whatever load-balances this app would pull it out (docs, risk R10).
 */
@Component("circuitBreakersHealthIndicator")
@RequiredArgsConstructor
public class CircuitBreakersHealthIndicator extends AbstractHealthIndicator {

    private final DownstreamCaller caller;
    private final CircuitBreakerProperties properties;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean down = false;
        Map<String, Object> clusters = new LinkedHashMap<>();
        for (DownstreamCaller.Guard guard : caller.guards().values()) {
            InstanceProperties p = properties.findCircuitBreakerProperties(guard.cluster()).orElse(null);
            if (p == null || !Boolean.TRUE.equals(p.getRegisterHealthIndicator())) {
                continue;
            }
            CircuitBreaker breaker = guard.breaker();
            CircuitBreaker.Metrics m = breaker.getMetrics();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("state", breaker.getState().name());
            d.put("failureRate", m.getFailureRate() + "%");
            d.put("failureRateThreshold", breaker.getCircuitBreakerConfig().getFailureRateThreshold() + "%");
            d.put("slowCallRate", m.getSlowCallRate() + "%");
            d.put("bufferedCalls", m.getNumberOfBufferedCalls());
            d.put("failedCalls", m.getNumberOfFailedCalls());
            d.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
            d.put("bulkheadAvailableConcurrentCalls", guard.bulkhead().getMetrics().getAvailableConcurrentCalls());
            clusters.put(guard.cluster(), d);
            down |= breaker.getState() == CircuitBreaker.State.OPEN
                    && Boolean.TRUE.equals(p.getAllowHealthIndicatorToFail());
        }
        (down ? builder.down() : builder.up()).withDetails(clusters);
    }
}
