package com.creed.simple.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link PaymentStickyLoadBalancerConfiguration}: it stacks a
 * {@link StickyMetadataServiceInstanceListSupplier} on top of the shared health-checked base chain
 * ({@link PartnerLoadBalancerConfiguration#healthCheckedSupplier}). The base chain is static-mocked so
 * the wiring — and the sticky filter actually running over the base's alive list — is verified in
 * isolation, without standing up a load-balancer child context.
 */
class PaymentStickyLoadBalancerConfigurationTest {

    private static final String STICKY = "STICKY-PRIMARY";

    private final PaymentStickyLoadBalancerConfiguration config = new PaymentStickyLoadBalancerConfiguration();
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    private static ServiceInstance instance(String id, int port, String stickyId) {
        return new DefaultServiceInstance(id, "payment-resource", "localhost", port, true,
                Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, stickyId));
    }

    @Test
    void wrapsTheHealthCheckedBaseInAStickyMetadataSupplier() {
        ServiceInstance primary = instance("payment-1", 18093, STICKY);
        ServiceInstance secondary = instance("payment-2", 18094, "OTHER");
        List<ServiceInstance> alive = List.of(primary, secondary);

        ServiceInstanceListSupplier base = mock(ServiceInstanceListSupplier.class);
        org.mockito.Mockito.when(base.get()).thenReturn(Flux.just(alive));

        try (MockedStatic<PartnerLoadBalancerConfiguration> mocked =
                     mockStatic(PartnerLoadBalancerConfiguration.class)) {
            mocked.when(() -> PartnerLoadBalancerConfiguration.healthCheckedSupplier(context)).thenReturn(base);

            ServiceInstanceListSupplier supplier = config.paymentStickyServiceInstanceListSupplier(context);

            assertThat(supplier).isInstanceOf(StickyMetadataServiceInstanceListSupplier.class);

            // With a sticky id set, the sticky layer narrows the base's alive list to the pinned instance.
            StickyContextHolder.set(STICKY);
            assertThat(supplier.get().blockFirst()).containsExactly(primary);

            // Without a sticky id it passes the full alive list straight through.
            StickyContextHolder.set(null);
            assertThat(supplier.get().blockFirst()).isEqualTo(alive);
        }
    }
}
