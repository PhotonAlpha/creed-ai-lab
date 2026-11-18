package com.creed.resource.lbhealth.api;

import com.creed.resource.lbhealth.downstream.DownstreamCaller;
import com.creed.resource.lbhealth.downstream.LoadRun;
import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resilience experiment, per cluster (docs/resilience4j-circuit-breaker.md): one call, every cluster's breaker and
 * bulkhead, a load run. {@code cluster} defaults to {@value LbHealthConfiguration#TARGET_SERVICE_ID}.
 */
@RestController
@RequestMapping("/cb")
@RequiredArgsConstructor
public class CircuitBreakerController {

    private final DownstreamCaller caller;
    private final LoadRun loadRun;

    /** {@code GET /cb/call?cluster=lb-health-bulkhead}: the load-balanced call through that cluster's guard. */
    @GetMapping("/call")
    public ResponseEntity<?> call(@RequestParam(defaultValue = LbHealthConfiguration.TARGET_SERVICE_ID) String cluster) {
        DownstreamCaller.Guard guard = caller.guard(cluster); // 400 first, not a 502
        try {
            return ResponseEntity.ok(caller.call(cluster));
        } catch (CallNotPermittedException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("rejected", "circuit-breaker", "state", guard.breaker().getState().name()));
        } catch (BulkheadFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("rejected", "bulkhead", "maxConcurrentCalls",
                            guard.bulkhead().getBulkheadConfig().getMaxConcurrentCalls()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.toString()));
        }
    }

    /** Every cluster: breaker state, what its window holds now, bulkhead usage, and the settings both really run with. */
    @GetMapping
    public List<Map<String, Object>> clusters() {
        return caller.guards().values().stream().map(CircuitBreakerController::describe).toList();
    }

    /** Resets the breaker (CLOSED, empty window) and the peak-concurrency counter. */
    @PostMapping("/{cluster}/reset")
    public Map<String, Object> reset(@PathVariable String cluster) {
        DownstreamCaller.Guard guard = caller.guard(cluster);
        guard.breaker().reset();
        guard.peak().set(guard.inFlight().get());
        return describe(guard);
    }

    /**
     * {@code POST /cb/load?cluster=lb-health-target&rps=20&seconds=150}: start calling; {@code GET /cb/load} to watch.
     * {@code concurrent=true}: every call on its own thread, not waiting for the previous one - for the bulkhead.
     */
    @PostMapping("/load")
    public LoadRun.Status startLoad(@RequestParam(defaultValue = LbHealthConfiguration.TARGET_SERVICE_ID) String cluster,
                                    @RequestParam(defaultValue = "20") int rps,
                                    @RequestParam(defaultValue = "150") int seconds,
                                    @RequestParam(defaultValue = "false") boolean concurrent) {
        if (rps < 1 || rps > 1000 || seconds < 1 || seconds > 3600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rps 1..1000, seconds 1..3600");
        }
        return loadRun.start(cluster, rps, seconds, concurrent);
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

    private static Map<String, Object> describe(DownstreamCaller.Guard guard) {
        CircuitBreaker cb = guard.breaker();
        CircuitBreaker.Metrics m = cb.getMetrics();
        CircuitBreakerConfig c = cb.getCircuitBreakerConfig();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cluster", guard.cluster());
        d.put("state", cb.getState().name());
        d.put("failureRate", m.getFailureRate()); // -1 = below minimumNumberOfCalls: not evaluated
        d.put("slowCallRate", m.getSlowCallRate());
        d.put("bufferedCalls", m.getNumberOfBufferedCalls());
        d.put("failedCalls", m.getNumberOfFailedCalls());
        d.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("slidingWindowType", c.getSlidingWindowType().name());
        config.put("slidingWindowSize", c.getSlidingWindowSize());
        config.put("minimumNumberOfCalls", c.getMinimumNumberOfCalls());
        config.put("failureRateThreshold", c.getFailureRateThreshold());
        config.put("slowCallRateThreshold", c.getSlowCallRateThreshold());
        config.put("slowCallDurationThreshold", c.getSlowCallDurationThreshold().toString());
        config.put("waitDurationInOpenState", c.getWaitIntervalFunctionInOpenState().apply(1) + "ms");
        config.put("permittedNumberOfCallsInHalfOpenState", c.getPermittedNumberOfCallsInHalfOpenState());
        config.put("automaticTransitionFromOpenToHalfOpenEnabled", c.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        d.put("config", config);
        Bulkhead bh = guard.bulkhead();
        Map<String, Object> bulkhead = new LinkedHashMap<>();
        bulkhead.put("maxConcurrentCalls", bh.getBulkheadConfig().getMaxConcurrentCalls());
        bulkhead.put("maxWaitDuration", bh.getBulkheadConfig().getMaxWaitDuration().toString());
        bulkhead.put("availableConcurrentCalls", bh.getMetrics().getAvailableConcurrentCalls());
        bulkhead.put("inFlight", guard.inFlight().get());
        bulkhead.put("peakInFlight", guard.peak().get());
        d.put("bulkhead", bulkhead);
        return d;
    }
}
