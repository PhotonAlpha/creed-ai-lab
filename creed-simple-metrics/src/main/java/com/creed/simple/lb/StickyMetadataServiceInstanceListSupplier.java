package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Sticky-session selection layer on top of the health-checked alive list: when the current request
 * carries a sticky id (lifted from the {@code stickyId} cookie into {@link StickyContextHolder} by the
 * Camel {@code paymentStickyProcessor}), the alive instances are narrowed to those whose registry
 * {@code metadata.stickyId} equals it — so the caller keeps landing on "its" instance.
 *
 * <p>Selection contract:
 * <ul>
 *   <li>no sticky id on the request → the full alive list (plain round-robin);</li>
 *   <li>sticky id matches ≥1 alive instance → only the matching instance(s);</li>
 *   <li>sticky id matches nothing alive (unknown id, or the pinned instance failed its health check)
 *       → <em>fall back</em> to the full alive list with a WARN, availability over stickiness.</li>
 * </ul>
 *
 * <p>The sticky id is captured in the {@code get()} body — i.e. on the thread calling
 * {@code LoadBalancerClient.choose(...)} (the Camel route thread, see {@link StickyContextHolder}) —
 * not inside the reactive chain, where the ThreadLocal would no longer be reliable.
 */
@Slf4j
public class StickyMetadataServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    /** Registry metadata key carrying an instance's sticky id (see application.yml simple instances). */
    public static final String STICKY_METADATA_KEY = "stickyId";

    public StickyMetadataServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        super(delegate);
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return filteredByCurrentThreadStickyId();
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        // The route-planner path goes through choose(serviceId) with a default request carrying no
        // usable context, so the cookie value travels via StickyContextHolder either way.
        return filteredByCurrentThreadStickyId();
    }

    private Flux<List<ServiceInstance>> filteredByCurrentThreadStickyId() {
        String stickyId = StickyContextHolder.get();
        return this.delegate.get().map(alive -> select(alive, stickyId));
    }

    static List<ServiceInstance> select(List<ServiceInstance> alive, String stickyId) {
        if (!StringUtils.hasText(stickyId)) {
            return alive;
        }
        List<ServiceInstance> matched = alive.stream()
                .filter(instance -> stickyId.equals(instance.getMetadata().get(STICKY_METADATA_KEY)))
                .toList();
        if (matched.isEmpty()) {
            log.warn("[LB-STICKY] no alive instance with metadata.{}={} — falling back to all {} alive instance(s)",
                    STICKY_METADATA_KEY, stickyId, alive.size());
            return alive;
        }
        if (log.isDebugEnabled()) {
            log.debug("[LB-STICKY] {}={} pinned to {}", STICKY_METADATA_KEY, stickyId,
                    matched.stream().map(i -> i.getHost() + ":" + i.getPort()).toList());
        }
        return matched;
    }
}
