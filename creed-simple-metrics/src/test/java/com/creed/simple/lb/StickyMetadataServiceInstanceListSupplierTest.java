package com.creed.simple.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StickyMetadataServiceInstanceListSupplier}: the select contract (no sticky id /
 * matching id / unknown id fallback) and the ThreadLocal capture through {@code get()}.
 */
class StickyMetadataServiceInstanceListSupplierTest {

    private static final String STICKY_PRIMARY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";
    private static final String STICKY_SECONDARY = "844D8CF83FBB091AB9E7F13E53E211668A36FE9761F3D22F593102CDE267A255";

    private final ServiceInstance primary = instance("payment-1", 18093, STICKY_PRIMARY);
    private final ServiceInstance secondary = instance("payment-2", 18094, STICKY_SECONDARY);
    private final List<ServiceInstance> alive = List.of(primary, secondary);

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    private static ServiceInstance instance(String id, int port, String stickyId) {
        return new DefaultServiceInstance(id, "payment-resource", "localhost", port, true,
                Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, stickyId));
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
    void getCapturesTheStickyIdFromTheCallingThread() {
        StickyContextHolder.set(STICKY_SECONDARY);
        List<ServiceInstance> selected = supplierOver(alive).get().blockFirst();
        assertThat(selected).containsExactly(secondary);

        StickyContextHolder.set(null);
        assertThat(supplierOver(alive).get().blockFirst()).isEqualTo(alive);
    }
}
