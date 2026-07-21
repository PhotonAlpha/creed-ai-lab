package com.creed.simple.lb;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link PartnerLoadBalancerConfiguration}. The default supplier bean simply delegates to
 * the shared {@code healthCheckedSupplier(...)} base chain; that delegation is verified here with the
 * static base-chain builder mocked (building the real chain needs a full load-balancer child context).
 * The health-probe verdict/URI logic it layers on is an exact mirror of {@link HealthCheckServiceSupplier}
 * and is covered by {@code HealthCheckServiceSupplierTest}.
 */
class PartnerLoadBalancerConfigurationTest {

    private final PartnerLoadBalancerConfiguration config = new PartnerLoadBalancerConfiguration();
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

    @Test
    void partnerSupplierBeanDelegatesToTheSharedHealthCheckedChain() {
        ServiceInstanceListSupplier base = mock(ServiceInstanceListSupplier.class);

        try (MockedStatic<PartnerLoadBalancerConfiguration> mocked =
                     mockStatic(PartnerLoadBalancerConfiguration.class)) {
            mocked.when(() -> PartnerLoadBalancerConfiguration.healthCheckedSupplier(context)).thenReturn(base);

            ServiceInstanceListSupplier supplier = config.partnerServiceInstanceListSupplier(context);

            assertThat(supplier).isSameAs(base);
            mocked.verify(() -> PartnerLoadBalancerConfiguration.healthCheckedSupplier(context));
        }
    }
}
