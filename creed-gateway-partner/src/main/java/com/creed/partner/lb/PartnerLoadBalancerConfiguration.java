package com.creed.partner.lb;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplierBuilder;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.loadbalancer.support.ServiceInstanceListSuppliers;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;

import java.util.List;

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
 * no status — we plug in our own {@link HealthCheckServiceInstanceListSupplier} via {@code .with(...)}. The
 * probe is a faithful copy of Spring Cloud's blocking RestClient health check
 * ({@code ServiceInstanceListSupplierBuilder#blockingHealthCheckServiceInstanceListSupplier}) plus logging:
 * it prints the HTTP status returned by each instance and, when the call throws, logs the otherwise-ignored
 * exception. Only instances answering {@code 200 OK} are handed to the load balancer.
 */
@Slf4j
public class PartnerLoadBalancerConfiguration implements HealthCheckServiceSupplier {
    @Value("${custom.cluster.ping-url:}")
    private String selfHealthCheckPath;



    @Bean
    ServiceInstanceListSupplier partnerServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        RestClient healthCheckRestClient = context.getBean("healthCheckRestClient", RestClient.class);
        ServiceInstanceListSupplierBuilder.DelegateCreator healthCheckCreator = (ctx, delegate) -> {
            LoadBalancerClientFactory loadBalancerClientFactory = ctx.getBean(LoadBalancerClientFactory.class);
            return healthCheckServiceInstanceListSupplierBuilder(healthCheckRestClient,
                    delegate, loadBalancerClientFactory, (instance, alive) -> {});
        };

        List<ServiceInstance> instances = List.of(
                new DefaultServiceInstance("creed-1", "creed-service", "10.0.0.1", 8443, true),
                new DefaultServiceInstance("creed-2", "creed-service", "10.0.0.2", 8443, true)
        );
        ServiceInstanceListSupplier baseInstanceListSupplier = ServiceInstanceListSuppliers.from("creed-service", instances.toArray(new ServiceInstance[0]));

        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()//这是从默认配置文件读取
//                .withBase(baseInstanceListSupplier) // 添加自定义的cluster
                .with(healthCheckCreator)
                .withCaching()
                .build(context);
    }

    @Override
    public Mono<Boolean> isAlive(ServiceInstance serviceInstance, String healthCheckPath, RestClient restClient) {
        return HealthCheckServiceSupplier.super.isAlive(serviceInstance, StringUtils.defaultIfBlank(selfHealthCheckPath, healthCheckPath), restClient);
    }

}
