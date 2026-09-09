package com.creed.simple.lb;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.Request;
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

import static com.creed.simple.lb.StickyMetadataServiceInstanceListSupplierTest.requestWithSticky;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style tests that drive the sticky supplier through a real
 * {@link RoundRobinLoadBalancer#choose(Request)} — the exact path {@code LoadBalancerRoutePlanner} takes
 * via {@code BlockingLoadBalancerClient}. This pins the core of the design: selection follows the
 * <em>request</em>, so concurrent callers with different sticky ids each land on their own instance
 * while sharing one supplier and one load balancer — and, unlike the old ThreadLocal carrier, so does a
 * caller whose request is chosen on some other pool's thread.
 */
class StickySelectionThroughLoadBalancerTest {

    private static final String STICKY_PRIMARY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";
    private static final String STICKY_SECONDARY = "844D8CF83FBB091AB9E7F13E53E211668A36FE9761F3D22F593102CDE267A255";

    private final ServiceInstance primary = instance("payment-1", 18093, STICKY_PRIMARY);
    private final ServiceInstance secondary = instance("payment-2", 18094, STICKY_SECONDARY);

    private final RoundRobinLoadBalancer loadBalancer = loadBalancerOver(List.of(primary, secondary));

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

    /** Mirrors BlockingLoadBalancerClient.choose(serviceId, request): block on the reactive choose. */
    private ServiceInstance choose(Request<?> request) {
        Response<ServiceInstance> response = Mono.from(loadBalancer.choose(request)).block();
        return response != null && response.hasServer() ? response.getServer() : null;
    }

    @Test
    void chooseHonoursTheRequestsStickyCookie() {
        for (int i = 0; i < 5; i++) {
            assertThat(choose(requestWithSticky(STICKY_SECONDARY))).as("call %d must stay pinned", i)
                    .isEqualTo(secondary);
        }
        for (int i = 0; i < 5; i++) {
            assertThat(choose(requestWithSticky(STICKY_PRIMARY))).isEqualTo(primary);
        }
    }

    @Test
    void chooseRoundRobinsWhenTheRequestCarriesNoStickyCookie() {
        Set<Integer> ports = IntStream.range(0, 6)
                .mapToObj(i -> choose(requestWithSticky(null)).getPort())
                .collect(Collectors.toSet());
        assertThat(ports).containsExactlyInAnyOrder(18093, 18094);
    }

    @Test
    void chooseRoundRobinsWhenThereIsNoRequestContextAtAll() {
        // The two-arg determineRoute path (RedirectExec): choose(serviceId) with an empty context.
        Set<Integer> ports = IntStream.range(0, 6)
                .mapToObj(i -> choose(new DefaultRequest<>()).getPort())
                .collect(Collectors.toSet());
        assertThat(ports).containsExactlyInAnyOrder(18093, 18094);
    }

    @Test
    void chooseFallsBackToRoundRobinForAnUnknownStickyId() {
        Set<Integer> ports = IntStream.range(0, 6)
                .mapToObj(i -> choose(requestWithSticky("DEADBEEF")).getPort())
                .collect(Collectors.toSet());
        assertThat(ports).containsExactlyInAnyOrder(18093, 18094);
    }

    @Test
    void concurrentCallersWithDifferentStickyIdsAreIsolatedPerRequest() throws Exception {
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

    /** A caller that records every instance choose() returns for its own sticky request. */
    private java.util.concurrent.Callable<Set<ServiceInstance>> stickyCaller(
            CountDownLatch start, String stickyId, int calls) {
        return () -> {
            start.await();
            return IntStream.range(0, calls)
                    .mapToObj(i -> choose(requestWithSticky(stickyId)))
                    .collect(Collectors.toSet());
        };
    }
}
