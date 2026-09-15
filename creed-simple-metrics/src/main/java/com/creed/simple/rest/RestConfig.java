package com.creed.simple.rest;

import com.creed.simple.config.PoolingHttpClientConnectionManagerAuditExecHandler;
import com.creed.simple.lb.LoadBalancerRoutePlanner;
import com.creed.simple.lb.RestClientSuppliers;
import com.creed.simple.pipeline.CheckoutPayloads;
import com.creed.simple.pipeline.FulfillmentFilter;
import com.creed.simple.route.FulfillmentEnricher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.ObservationExecChainHandler;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient5.LogbookHttpExecHandler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * The non-Camel half of the module: the same REST surface ({@code /camel/api/*}) and the same
 * downstream behaviour, implemented with Spring MVC controllers and a {@code RestClient}.
 *
 * <p>Active exactly when {@link com.creed.simple.config.CamelConfig} is not —
 * {@code creed.camel.enabled=false} — so the two can never both own {@code /camel/api/*}. Switching
 * also has to take the camel-servlet mapping down ({@code camel.servlet.mapping.enabled}), which
 * {@code application.yml} drives from the same {@code CREED_CAMEL_ENABLED} variable: a servlet mapped
 * at {@code /camel/*} beats the {@code /} DispatcherServlet, so a leftover Camel servlet would swallow
 * every request meant for {@link RestApiController}.
 *
 * <h2>The HTTP client is deliberately built like the camel-http one, not like the RestClients in
 * {@code lb/}</h2>
 * Same mTLS pool, same {@link LoadBalancerRoutePlanner} (so endpoint URIs keep the logical
 * {@code https://<service-id>/...} form and the sticky-cookie selection keeps working), and the same
 * three-layer hc5 exec chain — Logbook audit, Micrometer observation, LB/pool audit — in the same
 * order. That is what makes the two implementations comparable in the logs and dashboards; a plain
 * {@code RestClient} would quietly lose the downstream audit trail.
 *
 * <p>One deliberate difference: this pool is a Spring bean with {@code destroyMethod="close"}. The
 * camel-http pool is closed by {@code HttpComponent.doStop()} instead, which is why that one declares
 * {@code destroyMethod=""}.
 */
//@Configuration(proxyBeanMethods = false)
//@ConditionalOnProperty(prefix = "creed.camel", name = "enabled", havingValue = "false")
public class RestConfig {

    private static final Logger log = LoggerFactory.getLogger(RestConfig.class);

    /** mTLS pool for the downstream calls; mirrors {@code camelHttpConnectionManager}. */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager restHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.rest.http.max-total:50}") int maxTotal,
            @Value("${creed.rest.http.max-per-route:20}") int maxPerRoute,
            @Value("${creed.rest.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${creed.rest.http.socket-timeout:10s}") Duration socketTimeout) {
        return RestClientSuppliers.connectionManagerFrom(
                resolveClientBundleOrNull(sslBundles, bundleName),
                maxTotal, maxPerRoute, connectTimeout, socketTimeout);
    }

    @Bean
    MeterBinder restHttpPoolMetrics(
            @Qualifier("restHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "restHttpPool");
    }

    @Bean
    LoadBalancerRoutePlanner restLoadBalancerRoutePlanner(
            LoadBalancerClient loadBalancerClient, DiscoveryClient discoveryClient) {
        return new LoadBalancerRoutePlanner(loadBalancerClient, discoveryClient);
    }

    /**
     * The downstream client. {@code https://<service-id>/...} URIs are resolved by the route planner at
     * connect time — the same mechanism the camel-http endpoints use, including the cookie-based sticky
     * selection for {@code payment-resource} (the planner reads the {@code Cookie} header off the
     * outgoing request).
     */
    @Bean
    RestClient downstreamRestClient(
            @Qualifier("restHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
            LoadBalancerRoutePlanner routePlanner,
            ObservationRegistry observationRegistry,
            Logbook logbook,
            @Value("${creed.rest.http.connection-request-timeout:3s}") Duration connectionRequestTimeout,
            @Value("${creed.rest.http.response-timeout:10s}") Duration responseTimeout) {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                // The pool is a Spring bean (destroyMethod="close"); mark it shared so closing the
                // client does not also close the bean.
                .setConnectionManagerShared(true)
                .setRoutePlanner(routePlanner)
                // Same three handlers, same order as CamelConfig#httpComponent — see its comments.
                .addExecInterceptorFirst("logbook", new LogbookHttpExecHandler(logbook))
                .addExecInterceptorAfter(ChainElement.RETRY.name(), "micrometer",
                        new ObservationExecChainHandler(observationRegistry))
                .addExecInterceptorLast("lbAudit",
                        new PoolingHttpClientConnectionManagerAuditExecHandler(connectionManager))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                        .build())
                .build();
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .observationRegistry(observationRegistry)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // The pipeline beans. Declared here rather than annotated @Component so that they live or die with
    // the condition on this class — component scanning would ignore it. ({@link RestApiController} is
    // the exception: it must keep its @RestController stereotype to be mapped at all, so it carries the
    // same condition itself.)
    // ---------------------------------------------------------------------------------------------

    @Bean
    DownstreamGateway downstreamGateway(@Qualifier("downstreamRestClient") RestClient downstreamRestClient) {
        return new DownstreamGateway(downstreamRestClient);
    }

    @Bean
    AggregationService aggregationService(
            DownstreamGateway gateway,
            @Qualifier("aggregatePoolA") ExecutorService aggregatePoolA,
            @Qualifier("notificationPoolB") ExecutorService notificationPoolB) {
        return new AggregationService(gateway, aggregatePoolA, notificationPoolB);
    }

    @Bean
    FulfillmentService fulfillmentService(
            DownstreamGateway gateway,
            FulfillmentFilter filter,
            FulfillmentEnricher enricher,
            @Qualifier("aggregatePoolA") ExecutorService aggregatePoolA,
            @Qualifier("notificationPoolB") ExecutorService notificationPoolB) {
        return new FulfillmentService(gateway, filter, enricher, aggregatePoolA, notificationPoolB);
    }

    @Bean
    CheckoutService checkoutService(DownstreamGateway gateway, CheckoutPayloads payloads, ObjectMapper mapper) {
        return new CheckoutService(gateway, payloads, mapper);
    }

    /** Same graceful lookup as {@code CamelConfig}: a missing bundle degrades to a non-mTLS pool. */
    private static SslBundle resolveClientBundleOrNull(SslBundles sslBundles, String bundleName) {
        try {
            return sslBundles.getBundle(bundleName);
        } catch (NoSuchSslBundleException ex) {
            log.warn("mTLS SSL bundle '{}' is not registered — building a NON-mTLS downstream connection"
                    + " manager; downstream HTTPS calls may fail the handshake at runtime.", bundleName);
            return null;
        }
    }
}
