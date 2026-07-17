package com.creed.simple.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style tests that drive the sticky supplier through a real
 * {@link RoundRobinLoadBalancer#choose} — the exact path {@code LoadBalancerRoutePlanner} takes via
 * {@code BlockingLoadBalancerClient} — rather than poking {@code select(...)} directly. This pins the
 * riskiest part of the design: {@link StickyContextHolder}'s ThreadLocal must be captured on the
 * <em>calling</em> thread inside {@code get()}, so concurrent callers with different sticky ids must
 * each land on their own instance even though they share one supplier and one load balancer.
 */
class StickySelectionThroughLoadBalancerTest {

    private static final String STICKY_PRIMARY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";
    private static final String STICKY_SECONDARY = "844D8CF83FBB091AB9E7F13E53E211668A36FE9761F3D22F593102CDE267A255";

    private final ServiceInstance primary = instance("payment-1", 18093, STICKY_PRIMARY);
    private final ServiceInstance secondary = instance("payment-2", 18094, STICKY_SECONDARY);

    private final RoundRobinLoadBalancer loadBalancer = loadBalancerOver(List.of(primary, secondary));

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    private static ServiceInstance instance(String id, int port, String stickyId) {
        return new DefaultServiceInstance(id, "payment-resource", "localhost", port, true,
                Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, stickyId));
    }

    /** Sticky supplier over a static alive list, plugged into a real round-robin balancer. */
    private static RoundRobinLoadBalancer loadBalancerOver(List<ServiceInstance> alive) {
        ServiceInstanceListSupplier delegate = new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "payment-resource";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(alive);
            }
        };
        StickyMetadataServiceInstanceListSupplier sticky = new StickyMetadataServiceInstanceListSupplier(delegate);
        return new RoundRobinLoadBalancer(
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        Map.of("supplier", (ServiceInstanceListSupplier) sticky))
                        .getBeanProvider(ServiceInstanceListSupplier.class),
                "payment-resource");
    }

    /** Mirrors BlockingLoadBalancerClient.choose: block on the reactive choose from the caller thread. */
    private ServiceInstance choose() {
        Response<ServiceInstance> response = Mono.from(loadBalancer.choose(new DefaultRequest<>())).block();
        return response != null && response.hasServer() ? response.getServer() : null;
    }

    @Test
    void chooseHonoursTheCallingThreadsStickyId() {
        StickyContextHolder.set(STICKY_SECONDARY);
        for (int i = 0; i < 5; i++) {
            assertThat(choose()).as("call %d must stay pinned", i).isEqualTo(secondary);
        }

        StickyContextHolder.set(STICKY_PRIMARY);
        for (int i = 0; i < 5; i++) {
            assertThat(choose()).isEqualTo(primary);
        }
    }

    @Test
    void chooseRoundRobinsWhenNoStickyIdIsSet() {
        StickyContextHolder.set(null);
        Set<Integer> ports = IntStream.range(0, 6)
                .mapToObj(i -> choose().getPort())
                .collect(Collectors.toSet());
        assertThat(ports).containsExactlyInAnyOrder(18093, 18094);
    }

    @Test
    void chooseFallsBackToRoundRobinForAnUnknownStickyId() {
        StickyContextHolder.set("DEADBEEF");
        Set<Integer> ports = IntStream.range(0, 6)
                .mapToObj(i -> choose().getPort())
                .collect(Collectors.toSet());
        assertThat(ports).containsExactlyInAnyOrder(18093, 18094);
    }

    @Test
    void concurrentCallersWithDifferentStickyIdsAreIsolatedPerThread() throws Exception {
        int callsPerThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Set<ServiceInstance>> pinnedToPrimary = pool.submit(
                    stickyCaller(start, STICKY_PRIMARY, callsPerThread));
            Future<Set<ServiceInstance>> pinnedToSecondary = pool.submit(
                    stickyCaller(start, STICKY_SECONDARY, callsPerThread));
            start.countDown();

            assertThat(pinnedToPrimary.get()).containsExactly(primary);
            assertThat(pinnedToSecondary.get()).containsExactly(secondary);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A caller thread that sets its own sticky id once, then records every instance choose() returns. */
    private java.util.concurrent.Callable<Set<ServiceInstance>> stickyCaller(
            CountDownLatch start, String stickyId, int calls) {
        return () -> {
            start.await();
            StickyContextHolder.set(stickyId);
            try {
                return IntStream.range(0, calls)
                        .mapToObj(i -> choose())
                        .collect(Collectors.toSet());
            } finally {
                StickyContextHolder.clear();
            }
        };
    }
}
