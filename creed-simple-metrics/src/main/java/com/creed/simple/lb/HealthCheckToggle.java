package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime on/off switch for the LB health-check layer, shared by every load-balancer child context.
 *
 * <p>Lives in the <em>main</em> application context (component-scanned), so all lazily-created LB child
 * contexts resolve the same instance through their parent. Each
 * {@link ToggleableHealthCheckServiceInstanceListSupplier} registers itself here on construction;
 * {@link #setEnabled(boolean)} flips the flag and immediately starts/stops the registered suppliers'
 * probe loops — necessary because {@code HealthCheckServiceInstanceListSupplier.afterPropertiesSet()}
 * holds a permanent internal subscription ({@code aliveInstancesReplay.subscribe()}) that keeps the
 * {@code replay(1).refCount(1)} pipeline hot regardless of traffic; merely switching the {@code get()}
 * branch would leave that loop pinging forever.
 *
 * <p>Initial state comes from {@code creed.lb.health-check.enabled} (default {@code true}). Toggled at
 * runtime via {@code GET/PUT /admin/lb/health-check} (see
 * {@code com.creed.simple.web.HealthCheckToggleController}).
 */
@Slf4j
@Component
public class HealthCheckToggle {

    private final AtomicBoolean enabled;
    private final List<ToggleableHealthCheckServiceInstanceListSupplier> suppliers = new CopyOnWriteArrayList<>();

    public HealthCheckToggle(@Value("${creed.lb.health-check.enabled:true}") boolean initiallyEnabled) {
        this.enabled = new AtomicBoolean(initiallyEnabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Service-ids whose LB child context has been created so far. Child contexts are lazy (first
     * {@code choose()} per service), so a service missing here simply has not been called yet — it will
     * pick up the current flag when its supplier chain is built.
     */
    public List<String> registeredServiceIds() {
        return suppliers.stream().map(ServiceInstanceListSupplier::getServiceId).toList();
    }

    /** Flips the flag and starts/stops every registered probe loop. Returns the previous state. */
    public boolean setEnabled(boolean newValue) {
        boolean previous = enabled.getAndSet(newValue);
        if (previous != newValue) {
            log.info("[LB-HEALTH] health checks turned {} (was {}) for LB contexts {}",
                    newValue ? "ON" : "OFF", previous ? "ON" : "OFF", registeredServiceIds());
            suppliers.forEach(supplier -> supplier.onHealthCheckToggled(newValue));
        }
        return previous;
    }

    void register(ToggleableHealthCheckServiceInstanceListSupplier supplier) {
        suppliers.add(supplier);
    }

    void unregister(ToggleableHealthCheckServiceInstanceListSupplier supplier) {
        suppliers.remove(supplier);
    }
}
