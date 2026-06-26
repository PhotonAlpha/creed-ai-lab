package com.creed.simple.web;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
 * <p>The request factory carries the mTLS material (client cert + CA truststore) from the SSL bundle
 * {@code creed.partner.client-bundle}, so the resource servers' self-signed HTTPS is accepted.
 */
@Configuration(proxyBeanMethods = false)
public class LoadBalancedRestClientConfiguration {

    /** Shared mTLS connection manager (Spring closes it via destroyMethod). */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager clusterHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-client}") String bundleName,
            @Value("${creed.partner.http.max-total:50}") int maxTotal,
            @Value("${creed.partner.http.max-per-route:20}") int maxPerRoute) {
        SslBundle bundle = sslBundles.getBundle(bundleName);
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(bundle.createSslContext()))
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .build();
    }

    @Bean
    MeterBinder healthCheckHttpPoolMetrics(
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager healthPool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(healthPool, "loadBalancedPool");
    }

    @Bean
    ClientHttpRequestFactory clusterRequestFactory(
            PoolingHttpClientConnectionManager clusterHttpConnectionManager) {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(clusterHttpConnectionManager)
                // The pool is a Spring-managed bean; mark it shared so closing the client does not also
                // close the bean (Spring owns its lifecycle).
                .setConnectionManagerShared(true)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
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
}
