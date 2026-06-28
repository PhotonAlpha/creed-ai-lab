package com.creed.partner.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the partner gateway's downstream HTTP clients <strong>per declared cluster</strong>
 * ({@code creed.partner.clusters.<name>}) so business aggregation traffic and load-balancer health-check
 * traffic use two isolated Apache HttpClient 5 pools, and each partner can be tuned (pool size, timeouts,
 * SSL bundle) independently.
 *
 * <p>Rather than a static {@code @Bean} per pool/factory/client/metric (eight beans × N clusters), the
 * {@link ClusterClientBeanRegistrar} registers those beans <em>dynamically</em> from
 * {@link PartnerClusterProperties}. This class only contributes the cross-cluster pieces:
 * <ul>
 *   <li>the single {@code @LoadBalanced} {@link RestClient.Builder} template every per-cluster business
 *       client is cloned from (so each resolves {@code https://<service-id>} URLs while keeping its own
 *       request factory); and</li>
 *   <li>the {@code static} registrar bean itself.</li>
 * </ul>
 *
 * @see ClusterClientBeanRegistrar
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PartnerClusterProperties.class)
public class PartnerClientConfiguration {

    /**
     * The single {@code @LoadBalanced} template. The {@code @LoadBalanced} post-processor appends the
     * load-balancer interceptor to this builder; each {@code <cluster>RestClient} bean clones it so its
     * business calls resolve {@code https://<service-id>} URLs to a concrete instance.
     */
    @Bean
    @LoadBalanced
    RestClient.Builder partnerRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * {@code static} so this {@code BeanDefinitionRegistryPostProcessor} is instantiated early (before
     * regular beans) and can register the per-cluster bean definitions.
     */
    @Bean
    static ClusterClientBeanRegistrar clusterClientBeanRegistrar() {
        return new ClusterClientBeanRegistrar();
    }
}
