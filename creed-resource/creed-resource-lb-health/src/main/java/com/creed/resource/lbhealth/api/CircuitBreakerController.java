package com.creed.resource.lbhealth.api;

import com.creed.resource.lbhealth.downstream.DownstreamCaller;
import com.creed.resource.lbhealth.downstream.LoadRun;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The circuit-breaker experiment: one call, the breakers' state, a load run (docs/resilience4j-circuit-breaker.md). */
@RestController
@RequestMapping("/cb")
@RequiredArgsConstructor
public class CircuitBreakerController {

    private final DownstreamCaller caller;
    private final CircuitBreakerRegistry registry;
    private final LoadRun loadRun;

    /** {@code GET /cb/call?backend=backendB}: the load-balanced call through that breaker. */
    @GetMapping("/call")
    public ResponseEntity<?> call(@RequestParam String backend) {
        try {
            return ResponseEntity.ok(caller.call(backend));
        } catch (CallNotPermittedException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("rejected", true, "state", caller.breaker(backend).getState().name()));
        } catch (RuntimeException e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException rse) {
                throw rse;
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.toString()));
        }
    }

    /** Every breaker: state, what its window holds now, and the settings it really runs with. */
    @GetMapping
    public List<Map<String, Object>> breakers() {
        return registry.getAllCircuitBreakers().stream().map(CircuitBreakerController::describe).toList();
    }

    @PostMapping("/{backend}/reset")
    public Map<String, Object> reset(@PathVariable String backend) {
        CircuitBreaker breaker = caller.breaker(backend);
        breaker.reset();
        return describe(breaker);
    }

    /** {@code POST /cb/load?backend=backendB&rps=20&seconds=150}: start calling; {@code GET /cb/load} to watch. */
    @PostMapping("/load")
    public LoadRun.Status startLoad(@RequestParam String backend, @RequestParam(defaultValue = "20") int rps,
                                    @RequestParam(defaultValue = "150") int seconds) {
        if (rps < 1 || rps > 1000 || seconds < 1 || seconds > 3600) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rps 1..1000, seconds 1..3600");
        }
        return loadRun.start(backend, rps, seconds);
    }

    @GetMapping("/load")
    public ResponseEntity<LoadRun.Status> load() {
        LoadRun.Status status = loadRun.status();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    @DeleteMapping("/load")
    public void stopLoad() {
        loadRun.stop();
    }

    private static Map<String, Object> describe(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        CircuitBreakerConfig c = cb.getCircuitBreakerConfig();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", cb.getName());
        d.put("state", cb.getState().name());
        d.put("failureRate", m.getFailureRate()); // -1 = below minimumNumberOfCalls: not evaluated
        d.put("bufferedCalls", m.getNumberOfBufferedCalls());
        d.put("failedCalls", m.getNumberOfFailedCalls());
        d.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("slidingWindowType", c.getSlidingWindowType().name());
        config.put("slidingWindowSize", c.getSlidingWindowSize());
        config.put("minimumNumberOfCalls", c.getMinimumNumberOfCalls());
        config.put("failureRateThreshold", c.getFailureRateThreshold());
        config.put("waitDurationInOpenState", c.getWaitIntervalFunctionInOpenState().apply(1) + "ms");
        config.put("permittedNumberOfCallsInHalfOpenState", c.getPermittedNumberOfCallsInHalfOpenState());
        config.put("automaticTransitionFromOpenToHalfOpenEnabled", c.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        d.put("config", config);
        return d;
    }
}
