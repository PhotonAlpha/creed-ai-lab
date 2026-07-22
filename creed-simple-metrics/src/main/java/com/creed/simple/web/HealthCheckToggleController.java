package com.creed.simple.web;

import com.creed.simple.lb.HealthCheckToggle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API over {@link HealthCheckToggle}: runtime on/off for the LB health-check layer, served by
 * the Spring MVC dispatcher (camel-servlet only owns {@code /camel/*}, so {@code /admin/**} routes
 * here like {@code /actuator/**} does).
 *
 * <p>The flip is two-phase by design: the probe loop starts/stops immediately (see
 * {@code ToggleableHealthCheckServiceInstanceListSupplier}), but {@code choose()} keeps serving the
 * previously cached list until the LB cache entry expires
 * ({@code spring.cloud.loadbalancer.cache.ttl}, default 35s).
 */
@RestController
@RequestMapping("/admin/lb/health-check")
public class HealthCheckToggleController {

    private final HealthCheckToggle toggle;

    public HealthCheckToggleController(HealthCheckToggle toggle) {
        this.toggle = toggle;
    }

    @GetMapping
    Map<String, Object> status() {
        return Map.of(
                "enabled", toggle.isEnabled(),
                "services", toggle.registeredServiceIds());
    }

    @PutMapping
    Map<String, Object> set(@RequestParam boolean enabled) {
        boolean previous = toggle.setEnabled(enabled);
        return Map.of(
                "enabled", enabled,
                "previous", previous,
                "services", toggle.registeredServiceIds(),
                "note", "probe loops start/stop immediately; choose() picks up the change at the next"
                        + " LB cache refresh (spring.cloud.loadbalancer.cache.ttl, default 35s)");
    }
}
