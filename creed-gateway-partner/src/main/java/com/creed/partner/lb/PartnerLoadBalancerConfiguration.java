package com.creed.partner.lb;

import com.creed.partner.web.PartnerClusterProperties;
import com.creed.partner.web.PartnerClusterProperties.ClusterSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplierBuilder;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import java.util.Map;

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
 * <p>Instead of the stock {@code ServiceInstanceListSupplier.builder().withBlockingHealthChecks(RestClient)}
 * — whose alive-probe lambda swallows every error in a bare {@code catch (Exception ignored)} and reports
 * no status — we plug in our own {@code HealthCheckServiceInstanceListSupplier} via {@code .with(...)}. The
 * probe is a faithful copy of Spring Cloud's blocking RestClient health check plus logging.
 *
 * <p>Both the health-check {@link RestClient} and the probe path come from the per-cluster configuration
 * ({@link PartnerClusterProperties}, bound from {@code creed.partner.clusters.<name>}): each load-balancer
 * child context is keyed by service-id, so we find the matching cluster and use its dynamically-registered
 * {@code <cluster>HealthCheckRestClient} bean and its {@code health-check.path}.
 */
@Slf4j
public class PartnerLoadBalancerConfiguration implements HealthCheckServiceSupplier {

    @Bean
    ServiceInstanceListSupplier partnerServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        String serviceId = context.getEnvironment().getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        Map.Entry<String, ClusterSpec> cluster = findClusterByServiceId(context, serviceId);
        if (cluster == null) {
            // No cluster declared for this service-id: fall back to plain discovery (no health probe).
            log.warn("[LB] no cluster configured for service-id '{}'; skipping health checks", serviceId);
            return ServiceInstanceListSupplier.builder()
                    .withBlockingDiscoveryClient()
                    .withCaching()
                    .build(context);
        }

        String healthCheckPath = cluster.getValue().healthCheck().path();
        RestClient healthCheckRestClient =
                context.getBean(cluster.getKey() + "HealthCheckRestClient", RestClient.class);
        ServiceInstanceListSupplierBuilder.DelegateCreator healthCheckCreator = (ctx, delegate) -> {
            LoadBalancerClientFactory loadBalancerClientFactory = ctx.getBean(LoadBalancerClientFactory.class);
            return healthCheckServiceInstanceListSupplierBuilder(healthCheckRestClient, healthCheckPath,
                    delegate, loadBalancerClientFactory, (instance, alive) -> {});
        };

        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient() // instances come from spring.cloud.discovery.client.simple
                .with(healthCheckCreator)
                .withCaching()
                .build(context);
    }

    private static Map.Entry<String, ClusterSpec> findClusterByServiceId(
            ConfigurableApplicationContext context, String serviceId) {
        return context.getBean(PartnerClusterProperties.class).clusters().entrySet().stream()
                .filter(entry -> entry.getValue().serviceId().equals(serviceId))
                .findFirst()
                .orElse(null);
    }

}
