package com.creed.simple.lb;

import com.creed.simple.lb.config.PartnerLoadBalancerConfiguration;
import com.creed.simple.lb.config.PaymentStickyLoadBalancerConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@link LoadBalanced} {@link RestClient} the {@link com.creed.simple.route.RemoteClusterProcessor}
 * uses to call the downstream resource-server <em>cluster</em>. Spring Cloud LoadBalancer resolves a
 * {@code https://<service-id>/...} URL (e.g. {@code https://catalog-resource/...}) to a concrete,
 * registered instance (the {@code SimpleDiscoveryClient} registry in {@code application.yml}),
 * replacing Camel's {@code <loadBalance>} round-robin EIP.
 *
 * <p>Using the {@code https} scheme (not {@code lb}) keeps a valid scheme through the LB URI
 * reconstruct, avoiding the {@code SimpleDiscoveryClient} scheme leak.
 *
 * <p><strong>The two HTTP client stacks are built from a single template.</strong> Each downstream
 * client (business/cluster and health-check) is one {@link ManagedHttpClientPool} bean — pool +
 * buffering request factory + pool metrics in one self-managing object (see
 * {@link RestClientSuppliers#pool}). Tunables come from the type-safe {@link PartnerProps} binding, so
 * adding another client stack is one {@code pool(...)} call instead of four hand-wired beans with
 * {@code @Qualifier} strings. The pools carry the mTLS material (client cert + CA truststore) from the
 * SSL bundle {@code creed.partner.client-bundle}, so the resource servers' self-signed HTTPS is accepted.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PartnerProps.class)
// payment-resource gets the sticky-metadata supplier; every other service the default health-checked
// one. The default's supplier is @ConditionalOnMissingBean, so the two configs never clash in the
// payment child context.
@LoadBalancerClients(
        value = @LoadBalancerClient(name = "payment-resource",
                configuration = PaymentStickyLoadBalancerConfiguration.class),
        defaultConfiguration = PartnerLoadBalancerConfiguration.class)
public class LoadBalancedRestClientConfiguration {

    /** Business/cluster stack: the larger pool + buffering factory + {@code loadBalancedPool} metrics. */
    @Bean(destroyMethod = "close")
    ManagedHttpClientPool clusterPool(SslBundles sslBundles, PartnerProps props) {
        return RestClientSuppliers.pool(sslBundles, props.clientBundle(), props.http(), "loadBalancedPool");
    }

    /** Health-check stack: the smaller/tighter pool + buffering factory + {@code healthCheckPool} metrics. */
    @Bean(destroyMethod = "close")
    ManagedHttpClientPool healthCheckPool(SslBundles sslBundles, PartnerProps props) {
        return RestClientSuppliers.pool(
                sslBundles, props.clientBundle(), props.healthCheck().http(), "healthCheckPool");
    }

    /**
     * {@code @LoadBalanced} so {@code https://<service-id>} URLs are resolved by the load-balancer
     * interceptor before the request leaves.
     */
    @Bean
    @LoadBalanced
    RestClient.Builder clusterRestClientBuilder(ManagedHttpClientPool clusterPool,
                                                ObservationRegistry observationRegistry) {
        // Wire the ObservationRegistry so each call emits the `http.client.requests` Observation
        // (tags: method/uri/status/outcome/client.name). A bare RestClient.builder() is NOT
        // instrumented by Boot's auto-config — only the injected RestClient.Builder bean is.
        return RestClient.builder()
                .requestFactory(clusterPool.requestFactory())
                .observationRegistry(observationRegistry);
    }

    /**
     * Adds the audit interceptor AFTER the {@code @LoadBalanced} post-processor has added the
     * load-balancer interceptor, so it runs innermost and sees the resolved instance {@code host:port}.
     */
    @Bean
    RestClient clusterRestClient(
            @LoadBalanced RestClient.Builder clusterRestClientBuilder,
            LoadBalancerAuditInterceptor auditInterceptor) {
        return clusterRestClientBuilder
                .requestInterceptor(auditInterceptor)
                .build();
    }

    @Bean
    RestClient healthCheckRestClient(ManagedHttpClientPool healthCheckPool,
                                     ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .requestFactory(healthCheckPool.requestFactory())
                .observationRegistry(observationRegistry)
                .build();
    }
}
