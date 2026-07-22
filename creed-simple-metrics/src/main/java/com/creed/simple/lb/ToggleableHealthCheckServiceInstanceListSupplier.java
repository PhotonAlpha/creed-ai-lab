package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Wraps the health-checked supplier with a runtime on/off switch ({@link HealthCheckToggle}), sitting
 * between it and the caching layer in the {@code discovery → health check → caching} chain.
 *
 * <p>Two things have to switch together:
 * <ul>
 * <li><b>The list source</b> — {@link #get()} defers per subscription: toggle ON serves the
 * health-checked alive list, OFF serves the raw discovery list ({@code plain}). Because the caching
 * supplier sits outside, a flip becomes visible to {@code choose()} at the next cache refresh
 * ({@code spring.cloud.loadbalancer.cache.ttl}, default 35s).</li>
 * <li><b>The probe loop</b> — {@code HealthCheckServiceInstanceListSupplier.afterPropertiesSet()} holds
 * a permanent internal subscription that keeps the {@code replay(1).refCount(1)} ping pipeline running
 * every {@code health-check.interval} independent of traffic. So OFF must {@code destroy()} the
 * delegate (dispose → refCount hits 0 → the interval loop is torn down for real) and ON must call
 * {@code afterPropertiesSet()} again (fresh subscription, re-applying {@code initial-delay}). Both
 * superclass hooks already do exactly that per branch, so the flip methods reuse them.</li>
 * </ul>
 *
 * <p>Extends {@link DelegatingServiceInstanceListSupplier} with the health-checked supplier as the
 * delegate, so container lifecycle ({@code afterPropertiesSet}/{@code destroy}) and the
 * {@code SelectedInstanceCallback} pass-through keep working unchanged; startup honours the toggle's
 * initial state by simply not starting the probe loop when disabled.
 */
@Slf4j
public class ToggleableHealthCheckServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final ServiceInstanceListSupplier plain;
    private final HealthCheckToggle toggle;

    public ToggleableHealthCheckServiceInstanceListSupplier(HealthCheckServiceInstanceListSupplier healthChecked,
            ServiceInstanceListSupplier plain, HealthCheckToggle toggle) {
        super(healthChecked);
        this.plain = plain;
        this.toggle = toggle;
        toggle.register(this);
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return Flux.defer(() -> toggle.isEnabled() ? delegate.get() : plain.get());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (toggle.isEnabled()) {
            super.afterPropertiesSet();
        }
        else {
            log.info("[LB-HEALTH] {}: health checks disabled at startup — probe loop not started",
                    getServiceId());
        }
    }

    @Override
    public void destroy() throws Exception {
        toggle.unregister(this);
        super.destroy();
    }

    /** Called by {@link HealthCheckToggle} on a flip; starts or tears down this context's probe loop. */
    void onHealthCheckToggled(boolean enabled) {
        try {
            if (enabled) {
                super.afterPropertiesSet();
                log.info("[LB-HEALTH] {}: probe loop started (initial-delay applies before the first round)",
                        getServiceId());
            }
            else {
                ((DisposableBean) getDelegate()).destroy();
                log.info("[LB-HEALTH] {}: probe loop stopped; serving the raw discovery list from the"
                        + " next cache refresh", getServiceId());
            }
        }
        catch (Exception ex) {
            log.warn("[LB-HEALTH] {}: toggling health check to {} failed", getServiceId(), enabled, ex);
        }
    }
}
