package com.creed.simple.config;

import com.creed.simple.lb.LoadBalancerRoutePlanner;
import com.creed.simple.lb.RestClientSuppliers;
import com.creed.simple.lb.StickyContextThreadLocalAccessor;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.ObservationExecChainHandler;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
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
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient5.LogbookHttpExecHandler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * camel-http components ({@code http} / {@code https}) with Spring Cloud LoadBalancer routing — the
 * replacement for the removed ServiceCall EIP: routes keep plain
 * {@code https://<service-id>/...} endpoint URIs and the {@link LoadBalancerRoutePlanner} resolves the
 * service-id to an alive instance at connect time (see {@code fetch-catalog} / {@code fetch-order} in
 * {@code camel-context.xml}).
 *
 * <p>Camel resolves endpoint schemes against registry bean <em>names</em>, so the component bean is
 * registered under both {@code http} and {@code https}; without the {@code https} name Camel would fall
 * back to the component factory and silently create an unconfigured {@code HttpComponent} for https
 * endpoints.
 *
 * <p>The shared connection pool carries the same mTLS material ({@code creed.partner.client-bundle})
 * as the {@code @LoadBalanced} RestClient, with the same degraded non-mTLS fallback when the bundle is
 * missing. The pool bean declares {@code destroyMethod=""}: {@code HttpComponent.doStop()} closes the
 * pool it was handed, so Camel owns the shutdown (endpoints mark it {@code connectionManagerShared} and
 * never close it themselves).
 */
@Configuration(proxyBeanMethods = false)
public class CamelConfig {

    private static final Logger log = LoggerFactory.getLogger(CamelConfig.class);

    /**
     * Replaces the camel-spring-boot auto-configured template so its async methods
     * ({@code asyncRequestBody*}) run on a context-propagating executor: {@code captureAll()} snapshots
     * the caller thread's ThreadLocals (the Micrometer Observation scope) at submit time and restores
     * them on the pool thread, where reopening the Observation scope reopens the Brave span scope and
     * the {@code MyMDCScopeDecorator} MDC keys. With {@code correlationTraceId} now a <em>local</em>
     * baggage field it no longer rides on HTTP headers, so this in-process snapshot is the only way the
     * pool thread inherits it. The raw pool is created through the {@link
     * org.apache.camel.spi.ExecutorServiceManager} so Camel still owns its shutdown; only the wrapper is
     * handed to the template.
     */
    @Bean
    ProducerTemplate producerTemplate(CamelContext camelContext) {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(new StickyContextThreadLocalAccessor());
        ProducerTemplate template = camelContext.createProducerTemplate();
        ExecutorService pool = camelContext.getExecutorServiceManager()
                .newDefaultThreadPool(template, "ProducerTemplate");
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        template.setExecutorService(ContextExecutorService.wrap(pool, snapshotFactory::captureAll));
        return template;
    }

    @Bean
    LoadBalancerRoutePlanner camelLoadBalancerRoutePlanner(
            LoadBalancerClient loadBalancerClient, DiscoveryClient discoveryClient) {
        return new LoadBalancerRoutePlanner(loadBalancerClient, discoveryClient);
    }

    /** mTLS pool for the camel-http components; closed by {@code HttpComponent.doStop()}, not Spring. */
    @Bean(destroyMethod = "")
    PoolingHttpClientConnectionManager camelHttpConnectionManager(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName,
            @Value("${creed.camel.http.max-total:50}") int maxTotal,
            @Value("${creed.camel.http.max-per-route:20}") int maxPerRoute,
            @Value("${creed.camel.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${creed.camel.http.socket-timeout:10s}") Duration socketTimeout) {
        return RestClientSuppliers.connectionManagerFrom(
                resolveClientBundleOrNull(sslBundles, bundleName),
                maxTotal,
                maxPerRoute,
                connectTimeout,
                socketTimeout);
    }

    @Bean
    MeterBinder camelHttpPoolMetrics(
            @Qualifier("camelHttpConnectionManager") PoolingHttpClientConnectionManager camelHttpConnectionManager) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(camelHttpConnectionManager, "camelHttpPool");
    }

    /**
     * One instance, two registry names: Camel resolves components by scheme against the bean
     * name/alias, and {@code HttpComponent} itself is scheme-agnostic (each endpoint derives its
     * security from the endpoint URI), so the same component serves {@code http://} and
     * {@code https://} endpoints.
     */
    @Bean(name = {"http", "https"})
    public HttpComponent httpComponent(
            @Qualifier("camelHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
            LoadBalancerRoutePlanner routePlanner,
            ObservationRegistry observationRegistry,
            Logbook logbook,
            @Value("${creed.camel.http.connection-request-timeout:3s}") Duration connectionRequestTimeout,
            @Value("${creed.camel.http.response-timeout:10s}") Duration responseTimeout) {
        HttpComponent component = new HttpComponent();
        component.setClientConnectionManager(connectionManager);
        // HttpEndpoint applies the configurer after its own builder setup, so the planner is not overridden
        component.setHttpClientConfigurer(builder -> builder
                .setRoutePlanner(routePlanner)
                // Zalando Logbook: the FULL request/response audit (headers/cookies/bodies with
                // obfuscation, `logbook.*` config). First per the Logbook docs, so it sees the
                // message before other interceptors touch it.
                .addExecInterceptorFirst("logbook", new LogbookHttpExecHandler(logbook))
                // Micrometer's hc5 instrumentation: an `httpcomponents.httpclient.request` Observation
                // (timer + trace propagation) per attempt — the camel-http twin of the RestClient's
                // `http.client.requests`. Placed right inside RETRY per the Micrometer docs.
                .addExecInterceptorAfter(ChainElement.RETRY.name(), "micrometer",
                        new ObservationExecChainHandler(observationRegistry))
                // innermost (inside retry): logs the instance the route planner picked, per attempt,
                // plus route/total occupancy of this pool
                .addExecInterceptorLast("lbAudit", new CamelLoadBalancerAuditExecHandler(connectionManager)));
        component.setConnectionRequestTimeout(connectionRequestTimeout.toMillis());
        component.setResponseTimeout(responseTimeout.toMillis());
        return component;
    }

    /**
     * Same graceful lookup as {@code LoadBalancedRestClientConfiguration}: a missing bundle degrades to a
     * non-mTLS pool (the service boots; downstream handshakes may fail at runtime) instead of failing the
     * context.
     */
    private static SslBundle resolveClientBundleOrNull(SslBundles sslBundles, String bundleName) {
        try {
            return sslBundles.getBundle(bundleName);
        } catch (NoSuchSslBundleException ex) {
            log.warn("mTLS SSL bundle '{}' is not registered — building a NON-mTLS camel-http connection"
                    + " manager; downstream HTTPS calls may fail the handshake at runtime.", bundleName);
            return null;
        }
    }
}
