package com.creed.simple.lb;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Per-client LoadBalancer configuration for the {@code payment-resource} cluster only — referenced from
 * {@code @LoadBalancerClient(name = "payment-resource", configuration = ...)} on
 * {@code LoadBalancedRestClientConfiguration}, while every other service keeps the default
 * {@link PartnerLoadBalancerConfiguration}.
 *
 * <p>It reuses the exact same {@code discovery → logging health check → caching} base chain and stacks
 * {@link StickyMetadataServiceInstanceListSupplier} on top (outside the cache, so the sticky filter
 * runs per request against the cached alive list, and the cache never stores a filtered view).
 *
 * <p>Like the default configuration, this class carries <strong>no</strong> {@code @Configuration}/
 * {@code @Component} stereotype: it is imported into the {@code payment-resource} LB child context and
 * must stay out of the main component scan. Its supplier bean is registered before the default one, so
 * the default's {@code @ConditionalOnMissingBean} supplier backs off in this child context.
 */
public class PaymentStickyLoadBalancerConfiguration {

    @Bean
    ServiceInstanceListSupplier paymentStickyServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        return new StickyMetadataServiceInstanceListSupplier(
                PartnerLoadBalancerConfiguration.healthCheckedSupplier(context));
    }
}
