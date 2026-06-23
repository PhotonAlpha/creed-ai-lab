package com.creed.partner;

import com.creed.partner.lb.PartnerLoadBalancerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;

/**
 * Partner gateway: aggregates the catalog and order resource servers through Spring Cloud
 * LoadBalancer ({@code lb://} service IDs) with health checks and per-call audit logging.
 *
 * <p>{@link LoadBalancerClients} applies {@link PartnerLoadBalancerConfiguration} (the health-check
 * supplier) as the default configuration for every load-balanced service.
 */
@SpringBootApplication(scanBasePackages = "com.creed")
@LoadBalancerClients(defaultConfiguration = PartnerLoadBalancerConfiguration.class)
public class CreedGatewayPartnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreedGatewayPartnerApplication.class, args);
    }
}
