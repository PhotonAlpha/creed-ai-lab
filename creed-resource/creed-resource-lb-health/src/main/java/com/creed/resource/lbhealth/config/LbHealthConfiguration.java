package com.creed.resource.lbhealth.config;

import com.creed.resource.lbhealth.lb.TargetLoadBalancerClientConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Registers the health-checked supplier for {@value #TARGET_SERVICE_ID} only; every other service id keeps
 * Spring's default LoadBalancer configuration.
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClient(name = LbHealthConfiguration.TARGET_SERVICE_ID,
        configuration = TargetLoadBalancerClientConfiguration.class)
public class LbHealthConfiguration {

    /** Must equal the key under {@code spring.cloud.discovery.client.simple.instances} and {@code eager-load.clients}. */
    public static final String TARGET_SERVICE_ID = "lb-health-target";

    /**
     * The business client: {@code http://lb-health-target/...} is resolved by the load balancer.
     *
     * <p>It is the ONLY {@code RestTemplate} bean on purpose, and it must never reach the health check: the
     * no-arg {@code withBlockingHealthChecks()} does {@code context.getBean(RestTemplate.class)} and would pick it
     * up. Its interceptor then treats the probe URL's host ({@code localhost}) as a service id, finds no
     * instances, the stock probe swallows the exception as {@code false}, and every round yields an EMPTY list.
     * The probe client is built inside {@link TargetLoadBalancerClientConfiguration} and handed over explicitly.
     */
    @Bean
    @LoadBalanced
    RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }
}
