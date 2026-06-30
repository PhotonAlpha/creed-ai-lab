package com.creed.simple.web;

import com.creed.simple.lb.PartnerLoadBalancerConfiguration;
import com.creed.simple.lb.RestClientSuppliers;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.NoSuchSslBundleException;
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

    private static final Logger log = LoggerFactory.getLogger(LoadBalancedRestClientConfiguration.class);

    /** Shared mTLS connection manager (Spring closes it via destroyMethod). */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager clusterHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.partner.http.max-total:50}") int maxTotal,
            @Value("${creed.partner.http.max-per-route:20}") int maxPerRoute,
            @Value("${creed.partner.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${creed.partner.http.socket-timeout:10s}") Duration socketTimeout) {
        return RestClientSuppliers.connectionManagerFrom(
                resolveClientBundleOrNull(sslBundles, bundleName),
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
                resolveClientBundleOrNull(sslBundles, bundleName),
                maxTotal,
                maxPerRoute,
                connectTimeout,
                socketTimeout);
    }

    /**
     * Resolves the outbound mTLS bundle by name, returning {@code null} when it is not registered.
     * {@code SslBundleConfiguration} deliberately skips registering {@code creed-partner-client} when its
     * keystore/truststore fails to load, so this graceful lookup lets the pool fall back to a non-mTLS
     * (plain) client instead of failing the context. A {@code null} bundle is the agreed degraded mode:
     * the service boots, but downstream HTTPS calls may fail the handshake at runtime.
     */
    private static SslBundle resolveClientBundleOrNull(SslBundles sslBundles, String bundleName) {
        try {
            return sslBundles.getBundle(bundleName);
        } catch (NoSuchSslBundleException ex) {
            log.warn("mTLS SSL bundle '{}' is not registered — building a NON-mTLS connection manager;"
                    + " downstream HTTPS calls may fail the handshake at runtime.", bundleName);
            return null;
        }
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

    /**
     * <p>
     *     Micrometer 的 http.client.requests（RestClient/RestTemplate 的 Observation）天然带 uri/status/outcome/host 标签，给你 per-下游 的延迟、错误率、QPS，不用碰连接池内部.
     * </p>
     *
     * <p>
     *     HttpClient5 的池不是按 host，而是按整个 HttpRoute 做 key。HttpRoute 的相等性包含：
     *   <li>目标 HttpHost = scheme + hostName + port（port 是其中一部分）</li>
     *   <li>本地绑定地址（local address）</li>
     *   <li>代理链（proxy hops）</li>
     *   <li>是否隧道（tunnelled）/ 是否分层加密（secure/layered）</li>
     * </p>
     *
     * Per-route gauges for the business pool — the {@code total.*} binder above only shows aggregates.
     * Concrete bean type (not {@code MeterBinder}) so the {@code @Scheduled} refresh method is detected.
     */
    /*@Bean
    PerRoutePoolMetrics clusterPerRoutePoolMetrics(
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager clusterHttpConnectionManager) {
        return new PerRoutePoolMetrics(clusterHttpConnectionManager, "loadBalancedPool");
    }*/

    /** Per-route gauges for the health-check pool. */
    /*@Bean
    PerRoutePoolMetrics healthCheckPerRoutePoolMetrics(
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager healthCheckHttpConnectionManager) {
        return new PerRoutePoolMetrics(healthCheckHttpConnectionManager, "healthCheckPool");
    }*/

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
    RestClient.Builder clusterRestClientBuilder(ClientHttpRequestFactory clusterRequestFactory,
                                                ObservationRegistry observationRegistry) {
        // Wire the ObservationRegistry so each call emits the `http.client.requests` Observation
        // (tags: method/uri/status/outcome/client.name). A bare RestClient.builder() is NOT
        // instrumented by Boot's auto-config — only the injected RestClient.Builder bean is.
        return RestClient.builder()
                .requestFactory(clusterRequestFactory)
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
    RestClient healthCheckRestClient(
            @Qualifier("healthCheckClientHttpRequestFactory") ClientHttpRequestFactory requestFactory,
            ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }
}
