package com.creed.simple.lb;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StickyMetadataServiceInstanceListSupplier}: the select contract (no sticky id /
 * matching id / unknown id fallback) and the sticky id being read off the request's cookies.
 */
class StickyMetadataServiceInstanceListSupplierTest {

    private static final String STICKY_PRIMARY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";
    private static final String STICKY_SECONDARY = "844D8CF83FBB091AB9E7F13E53E211668A36FE9761F3D22F593102CDE267A255";

    private final ServiceInstance primary = instance("payment-1", 18093, STICKY_PRIMARY);
    private final ServiceInstance secondary = instance("payment-2", 18094, STICKY_SECONDARY);
    private final List<ServiceInstance> alive = List.of(primary, secondary);

    private static ServiceInstance instance(String id, int port, String stickyId) {
        return new DefaultServiceInstance(id, "payment-resource", "localhost", port, true,
                Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, stickyId));
    }

    /** A request carrying the given sticky cookie, shaped like the one the route planner builds. */
    static Request<RequestDataContext> requestWithSticky(String stickyId) {
        MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
        if (stickyId != null) {
            cookies.add(StickyMetadataServiceInstanceListSupplier.STICKY_COOKIE, stickyId);
        }
        RequestData data = new RequestData(HttpMethod.GET, URI.create("https://payment-resource/api/payment"),
                new HttpHeaders(), cookies, new HashMap<>());
        return new DefaultRequest<>(new RequestDataContext(data));
    }

    /** Delegate standing in for the cached health-check chain. */
    private StickyMetadataServiceInstanceListSupplier supplierOver(List<ServiceInstance> aliveList) {
        ServiceInstanceListSupplier delegate = new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "payment-resource";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(aliveList);
            }
        };
        return new StickyMetadataServiceInstanceListSupplier(delegate);
    }

    @Test
    void withoutStickyIdReturnsTheFullAliveList() {
        assertThat(StickyMetadataServiceInstanceListSupplier.select(alive, null)).isEqualTo(alive);
        assertThat(StickyMetadataServiceInstanceListSupplier.select(alive, " ")).isEqualTo(alive);
    }

    @Test
    void matchingStickyIdNarrowsToThePinnedInstance() {
        assertThat(StickyMetadataServiceInstanceListSupplier.select(alive, STICKY_PRIMARY))
                .containsExactly(primary);
        assertThat(StickyMetadataServiceInstanceListSupplier.select(alive, STICKY_SECONDARY))
                .containsExactly(secondary);
    }

    @Test
    void unknownStickyIdFallsBackToTheFullAliveList() {
        assertThat(StickyMetadataServiceInstanceListSupplier.select(alive, "DEADBEEF")).isEqualTo(alive);
    }

    @Test
    void pinnedInstanceGoneFromAliveListFallsBackToSurvivors() {
        // The pinned (primary) instance failed its health check — availability wins over stickiness.
        assertThat(StickyMetadataServiceInstanceListSupplier.select(List.of(secondary), STICKY_PRIMARY))
                .containsExactly(secondary);
    }

    @Test
    void stickyIdIsReadFromTheRequestCookies() {
        assertThat(StickyMetadataServiceInstanceListSupplier.stickyIdOf(requestWithSticky(STICKY_PRIMARY)))
                .isEqualTo(STICKY_PRIMARY);
        assertThat(StickyMetadataServiceInstanceListSupplier.stickyIdOf(requestWithSticky(null))).isNull();
    }

    @Test
    void aRequestWithoutRequestDataContextYieldsNoStickyId() {
        assertThat(StickyMetadataServiceInstanceListSupplier.stickyIdOf(null)).isNull();
        assertThat(StickyMetadataServiceInstanceListSupplier.stickyIdOf(new DefaultRequest<>())).isNull();
    }

    @Test
    void getFiltersByTheRequestsStickyCookie() {
        assertThat(supplierOver(alive).get(requestWithSticky(STICKY_SECONDARY)).blockFirst())
                .containsExactly(secondary);
        assertThat(supplierOver(alive).get(requestWithSticky(null)).blockFirst()).isEqualTo(alive);
    }

    @Test
    void theNoArgGetHasNoRequestToPinOnAndPassesTheAliveListThrough() {
        assertThat(supplierOver(alive).get().blockFirst()).isEqualTo(alive);
    }
}
