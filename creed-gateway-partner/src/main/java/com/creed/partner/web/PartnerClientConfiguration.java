package com.creed.partner.web;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires the HTTP clients the partner gateway uses to reach the resource servers, with two
 * <strong>separate</strong> Apache HttpClient 5 connection pools so business traffic and health-check
 * traffic are isolated (and monitored independently):
 *
 * <ul>
 *   <li>{@code partnerRestClient} — the aggregator's client (requirement 1) carrying the
 *       {@link LoadBalancerAuditInterceptor} (requirement 3), backed by the
 *       {@code aggregateHttpConnectionManager} pool ({@code creed.partner.http.*}).</li>
 *   <li>{@code healthCheckRestClient} — used by Spring Cloud LoadBalancer's blocking health checks
 *       (requirement 2), backed by the smaller {@code healthCheckHttpConnectionManager} pool
 *       ({@code creed.partner.health-check.http.*}).</li>
 * </ul>
 *
 * <p>Both pools trust the resource servers' self-signed certs via the same SSL bundle, and both export
 * their stats to Micrometer/actuator under {@code httpcomponents.httpclient.pool.*}, distinguished by the
 * {@code httpclient} tag ({@code creed-partner-aggregate} vs {@code creed-partner-health}). The aggregate
 * factory is buffered so the audit interceptor can read the response body and still hand it to the
 * caller.
 *
 * <p>{@code partnerRestClient} is {@code @LoadBalanced}, so {@code https://<service-id>} URLs are
 * resolved to a concrete instance by the load-balancer interceptor. Using the {@code https} scheme (not
 * {@code lb}) avoids the {@code SimpleDiscoveryClient} scheme leak — the framework's URI reconstruct
 * keeps {@code https}. The {@link LoadBalancerAuditInterceptor} is added <em>after</em> the LB
 * interceptor (innermost) so it observes the resolved instance URL, request timing and status.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerClientConfiguration {

    private static final String POOL_AGGREGATE = "creed-partner-aggregate";
    private static final String POOL_HEALTH = "creed-partner-health";

    // ---- aggregation pool / client -------------------------------------------------------------

    /**
     *
     * @param sslBundles
     * @param bundleName
     * @param maxTotal
     * @param maxPerRoute
     * @param connectTimeout
     * @param socketTimeout
     * @return
     *  严格来说不是必须的，但应该加 —— 我已加上，理由：
     * <p>
     *   - 默认其实也会关：
     *   @Bean 的 destroyMethod 默认值是 INFER，Spring 会自动探测并调用 bean 上的 close()/shutdown()。
     *   PoolingHttpClientConnectionManager 实现了 Closeable，所以即使不写，关停时 Spring 也会调
     *   close()。
     *   - 为什么仍要显式写：
     *   ① 与 creed-author-server 的 GatewayRestTemplateConfiguration 约定一致；
     *   ② 意图明确，不依赖"推断"这个隐式行为。
     * <p>
     *   更关键的是配套的那一半——我同时在 HttpClients.custom() 上加了 .setConnectionManagerShared(true)（也是 author-server 的做法）：
     * <p>
     *   - 连接池现在是 Spring 管理的 bean，由 Spring 负责 close()；
     *   - shared(true) 让 CloseableHttpClient.close() 不再去关这个池 —— 避免"客户端和 Spring 都关一次"的双重关闭，单一 owner 模型干净。
     */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager aggregateHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.partner.http.max-total:50}") int maxTotal,
            @Value("${creed.partner.http.max-per-route:20}") int maxPerRoute,
            @Value("${creed.partner.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${creed.partner.http.socket-timeout:10s}") Duration socketTimeout) {
        return connectionManager(sslBundles.getBundle(bundleName), maxTotal, maxPerRoute, connectTimeout, socketTimeout);
    }

    @Bean
    ClientHttpRequestFactory aggregateClientHttpRequestFactory(
            @Qualifier("aggregateHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
            @Value("${creed.partner.http.connection-request-timeout:3s}") Duration connectionRequestTimeout,
            @Value("${creed.partner.http.response-timeout:10s}") Duration responseTimeout) {
        return new BufferingClientHttpRequestFactory(
                requestFactory(connectionManager, connectionRequestTimeout, responseTimeout));
    }

    @Bean
    @LoadBalanced
    RestClient.Builder partnerRestClientBuilder(
            @Qualifier("aggregateClientHttpRequestFactory") ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    RestClient partnerRestClient(
            @LoadBalanced RestClient.Builder partnerRestClientBuilder,
            LoadBalancerAuditInterceptor auditInterceptor) {
        // Add the audit interceptor AFTER the @LoadBalanced post-processor has added the load-balancer
        // interceptor, so it runs innermost and sees the *resolved* instance URL (host:port), not the
        // logical lb/service URL — which lets it log the real target, timing and status.
        return partnerRestClientBuilder
                .requestInterceptor(auditInterceptor)
                .build();
    }

    // ---- health-check pool / client ------------------------------------------------------------

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager healthCheckHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.partner.health-check.http.max-total:10}") int maxTotal,
            @Value("${creed.partner.health-check.http.max-per-route:5}") int maxPerRoute,
            @Value("${creed.partner.health-check.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${creed.partner.health-check.http.socket-timeout:2s}") Duration socketTimeout) {
        return connectionManager(sslBundles.getBundle(bundleName), maxTotal, maxPerRoute, connectTimeout, socketTimeout);
    }

    @Bean
    ClientHttpRequestFactory healthCheckClientHttpRequestFactory(
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
            @Value("${creed.partner.health-check.http.connection-request-timeout:2s}") Duration connectionRequestTimeout,
            @Value("${creed.partner.health-check.http.response-timeout:2s}") Duration responseTimeout) {
        return requestFactory(connectionManager, connectionRequestTimeout, responseTimeout);
    }

    @Bean
    RestClient healthCheckRestClient(
            @Qualifier("healthCheckClientHttpRequestFactory") ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    // ---- metrics ------------------------------------------------------------------------------

    /**
     * Exports each pool's stats via Micrometer's built-in
     * {@link PoolingHttpClientConnectionManagerMetricsBinder}, visible under
     * {@code /actuator/metrics/httpcomponents.httpclient.pool.*}. The pool name becomes the value of
     * the {@code httpclient} tag, so the two pools are distinguished there.
     */
    @Bean
    MeterBinder aggregateHttpPoolMetrics(
            @Qualifier("aggregateHttpConnectionManager") PoolingHttpClientConnectionManager aggregatePool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(aggregatePool, POOL_AGGREGATE);
    }

    @Bean
    MeterBinder healthCheckHttpPoolMetrics(
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager healthPool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(healthPool, POOL_HEALTH);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     *
     * @param bundle
     * @param maxTotal
     * @param maxPerRoute
     * @param connectTimeout
     * @param socketTimeout
     * @return
     *
     * 你发出请求  ──────►  对方开始回数据  ──►  数据一段段传回来  ──►  收完
     *               ↑                        ↑
     *           responseTimeout          socketTimeout
     *          （第188行：等"开口"）    （第173行：等"下一口"）
     *
     *   一句话区分
     *
     *   - 第 188 行 responseTimeout：我把请求发出去了，对方多久内得开始回我。比如设 5 秒，5 秒还没听到任何动静（第一个字节都没回），就报超时。
     *   - 第 173 行 socketTimeout：对方已经在回数据了，但数据是一段一段来的。两段数据之间最多能卡多久。比如设 10 秒，对方传了一半突然不传了，卡超过 10 秒就报超时。
     *
     *   为什么看起来"一样"
     *
     *   因为它们单位都是毫秒、写法长得像（都是 Timeout.ofMilliseconds(...)），而且在很多简单场景下，如果对方一直不回数据，两个超时都可能触发，所以容易误以为重复。但本质上：
     *
     *   - 一个是连接这条"水管"本身的默认读超时（173 行，连接管理器级别）。
     *   - 一个是单次请求等响应的超时（188 行，请求级别），且会在请求期间临时盖过前者。
     *
     *   所以不是配了两遍同一个东西，而是给"等开头"和"防中途卡死"分别设了值。
     */
    private static PoolingHttpClientConnectionManager connectionManager(
            SslBundle bundle, int maxTotal, int maxPerRoute, Duration connectTimeout, Duration socketTimeout) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(socketTimeout.toMillis()))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(bundle.createSslContext()))
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
    }

    private static HttpComponentsClientHttpRequestFactory requestFactory(
            PoolingHttpClientConnectionManager connectionManager,
            Duration connectionRequestTimeout, Duration responseTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                // The pool is a Spring-managed bean (destroyMethod="close"); mark it shared so closing
                // the client does not also close the bean (Spring owns its lifecycle).
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
