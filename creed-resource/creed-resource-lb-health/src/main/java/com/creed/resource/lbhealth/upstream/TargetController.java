package com.creed.resource.lbhealth.upstream;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The target service. Every connector of this app serves it; the local port tells which "instance" was hit.
 */
@RestController
@RequestMapping("/target")
@RequiredArgsConstructor
public class TargetController {

    private final TargetInstanceStates states;

    /** The path the load balancer probes ({@code health-check.path.lb-health-target}). */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(HttpServletRequest request) throws InterruptedException {
        int port = request.getLocalPort();
        TargetInstanceStates.State state = states.get(port);
        if (state.delay().isPositive()) {
            Thread.sleep(state.delay());
        }
        return ResponseEntity.status(state.up() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("port", port, "status", state.up() ? "UP" : "DOWN"));
    }

    /**
     * The business API the load-balanced client calls. Follows the instance's state like {@code /health} (asked for,
     * 2026-11-17: a "down" server must fail its callers too, or the circuit breaker has nothing to see).
     */
    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(HttpServletRequest request) throws InterruptedException {
        int port = request.getLocalPort();
        TargetInstanceStates.State state = states.get(port);
        if (state.delay().isPositive()) {
            Thread.sleep(state.delay());
        }
        return state.up() ? ResponseEntity.ok(Map.of("port", port))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("port", port, "status", "DOWN"));
    }

    /** {@code PUT /target/instances/18103?up=false} or {@code ?delay=5s} (anything longer than the read timeout). */
    @PutMapping("/instances/{port}")
    public Map<Integer, TargetInstanceStates.State> set(@PathVariable int port,
            @RequestParam(defaultValue = "true") boolean up,
            @RequestParam(defaultValue = "0s") String delay) {
        // Parsed by hand: MVC's converter only takes ISO-8601 (PT4S), not the "4s" style used in application.yml.
        states.set(port, new TargetInstanceStates.State(up, DurationStyle.detectAndParse(delay)));
        return states.overrides();
    }

    @GetMapping("/instances")
    public Map<Integer, TargetInstanceStates.State> overrides() {
        return states.overrides();
    }

    @DeleteMapping("/instances")
    public Map<Integer, TargetInstanceStates.State> reset() {
        states.reset();
        return states.overrides();
    }
}
