package com.creed.simple.web;

import com.creed.simple.lb.PartnerLoadBalancerConfiguration;
import com.creed.simple.lb.RestClientSuppliers;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

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
 * <p>The request factory carries the mTLS material (client cert + CA truststore) from the SSL bundle
 * {@code creed.partner.client-bundle}, so the resource servers' self-signed HTTPS is accepted.
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = PartnerLoadBalancerConfiguration.class)
public class LoadBalancedRestClientConfiguration {

    /** Shared mTLS connection manager (Spring closes it via destroyMethod). */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager clusterHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.partner.http.max-total:50}") int maxTotal,
            @Value("${creed.partner.http.max-per-route:20}") int maxPerRoute,
            @Value("${creed.partner.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${creed.partner.http.socket-timeout:10s}") Duration socketTimeout) {
        SslBundle bundle = sslBundles.getBundle(bundleName);
        return RestClientSuppliers.connectionManagerFrom(
                bundle,
                maxTotal,
                maxPerRoute,
                connectTimeout, socketTimeout);
    }

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager healthCheckHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.partner.health-check.http.max-total:10}") int maxTotal,
            @Value("${creed.partner.health-check.http.max-per-route:5}") int maxPerRoute,
            @Value("${creed.partner.health-check.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${creed.partner.health-check.http.socket-timeout:2s}") Duration socketTimeout) {
        return RestClientSuppliers.connectionManagerFrom(
                sslBundles.getBundle(bundleName),
                maxTotal,
                maxPerRoute,
                connectTimeout,
                socketTimeout);
    }

    @Bean
    MeterBinder clusterCheckHttpPoolMetrics(
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager healthPool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(healthPool, "loadBalancedPool");
    }

    @Bean
    MeterBinder healthCheckHttpPoolMetrics(
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager healthCheckHttpConnectionManager) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(healthCheckHttpConnectionManager, "healthCheckPool");
    }

    @Bean
    ClientHttpRequestFactory clusterRequestFactory(
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager clusterHttpConnectionManager,
            @Value("${creed.partner.http.connection-request-timeout:3s}") Duration connectionRequestTimeout,
            @Value("${creed.partner.http.response-timeout:10s}") Duration responseTimeout) {
        HttpComponentsClientHttpRequestFactory requestFactory = RestClientSuppliers.requestFactoryFrom(clusterHttpConnectionManager,
                connectionRequestTimeout,
                responseTimeout
        );
        return new BufferingClientHttpRequestFactory(requestFactory);
    }

    @Bean
    ClientHttpRequestFactory healthCheckClientHttpRequestFactory(
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager clusterHttpConnectionManager,
            @Value("${creed.partner.health-check.http.connection-request-timeout:2s}") Duration connectionRequestTimeout,
            @Value("${creed.partner.health-check.http.response-timeout:2s}") Duration responseTimeout) {
        HttpComponentsClientHttpRequestFactory requestFactory = RestClientSuppliers.requestFactoryFrom(clusterHttpConnectionManager,
                connectionRequestTimeout,
                responseTimeout
        );
        return new BufferingClientHttpRequestFactory(requestFactory);
    }

    /**
     * {@code @LoadBalanced} so {@code https://<service-id>} URLs are resolved by the load-balancer
     * interceptor before the request leaves.
     */
    @Bean
    @LoadBalanced
    RestClient.Builder clusterRestClientBuilder(ClientHttpRequestFactory clusterRequestFactory) {
        return RestClient.builder().requestFactory(clusterRequestFactory);
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
    RestClient healthCheckRestClient(
            @Qualifier("healthCheckClientHttpRequestFactory") ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
