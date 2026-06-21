package com.creed.partner.lb;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Requirement 2: per-client Spring Cloud LoadBalancer configuration that adds health checks on top of
 * the {@code SimpleDiscoveryClient} registry. Referenced from {@code @LoadBalancerClients(
 * defaultConfiguration = PartnerLoadBalancerConfiguration.class)} so it applies to every {@code lb://}
 * service.
 *
 * <p>This class is intentionally <strong>not</strong> annotated with {@code @Configuration}/
 * {@code @Component}: Spring Cloud imports it into each load-balancer child context, and keeping it out
 * of the main {@code @ComponentScan} avoids the supplier leaking into the primary application context.
 *
 * <p>{@link ServiceInstanceListSupplier#builder()}.withBlockingHealthChecks(RestClient)} pings each
 * instance over the SSL-trusting {@code healthCheckRestClient} (resolved from the parent context) at the
 * path/interval configured under {@code spring.cloud.loadbalancer.health-check.*}; only instances that
 * answer 2xx are handed to the load balancer.
 */
public class PartnerLoadBalancerConfiguration {

    @Bean
    ServiceInstanceListSupplier partnerServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        RestClient healthCheckRestClient = context.getBean("healthCheckRestClient", RestClient.class);
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withBlockingHealthChecks(healthCheckRestClient)
                .withCaching()
                .build(context);
    }
}
