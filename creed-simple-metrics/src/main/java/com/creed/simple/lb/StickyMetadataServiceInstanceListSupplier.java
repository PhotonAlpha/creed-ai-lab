package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Sticky-session selection layer on top of the health-checked alive list: when the request being routed
 * carries a {@code stickyId} cookie, the alive instances are narrowed to those whose registry
 * {@code metadata.stickyId} equals it — so the caller keeps landing on "its" instance.
 *
 * <p>The id is read off the {@link RequestDataContext} of the current selection, exactly like Spring's
 * own {@code RequestBasedStickySessionServiceInstanceListSupplier} — i.e. from the <em>outgoing</em>
 * request's cookies. Both downstream paths supply that context: the {@code @LoadBalanced} RestClient
 * gets it from {@code BlockingLoadBalancerClient.execute(...)}, and the camel-http path from
 * {@link LoadBalancerRoutePlanner}'s three-arg {@code determineRoute}. Nothing here is thread-bound, so
 * Camel's multicast/aggregation pools and the async {@code ProducerTemplate} need no context
 * propagation for stickiness to hold.
 *
 * <p>Selection contract:
 * <ul>
 *   <li>no sticky cookie (or no request context at all) → the full alive list (plain round-robin);</li>
 *   <li>sticky id matches ≥1 alive instance → only the matching instance(s);</li>
 *   <li>sticky id matches nothing alive (unknown id, or the pinned instance failed its health check)
 *       → <em>fall back</em> to the full alive list with a WARN, availability over stickiness.</li>
 * </ul>
 */
@Slf4j
public class StickyMetadataServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    /** Registry metadata key carrying an instance's sticky id (see application.yml simple instances). */
    public static final String STICKY_METADATA_KEY = "stickyId";

    /** Cookie the caller pins with; same name as the metadata key. */
    public static final String STICKY_COOKIE = "stickyId";

    public StickyMetadataServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        super(delegate);
    }

    /** No request in scope (eager cache warm-up, direct supplier calls) — nothing to pin on. */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return this.delegate.get();
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        String stickyId = stickyIdOf(request);
        return this.delegate.get(request).map(alive -> select(alive, stickyId));
    }

    /** The {@code stickyId} cookie of the request being routed, or {@code null} when it carries none. */
    static String stickyIdOf(Request<?> request) {
        if (request == null || !(request.getContext() instanceof RequestDataContext context)) {
            return null;
        }
        RequestData clientRequest = context.getClientRequest();
        if (clientRequest == null) {
            return null;
        }
        MultiValueMap<String, String> cookies = clientRequest.getCookies();
        return cookies == null ? null : cookies.getFirst(STICKY_COOKIE);
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
