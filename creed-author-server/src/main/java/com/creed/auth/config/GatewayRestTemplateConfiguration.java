package com.creed.auth.config;

import java.time.Duration;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import io.micrometer.core.instrument.MeterRegistry;
import com.creed.metrics.ConnectionPoolMetrics;

/**
 * Outbound {@link RestTemplate} for calling creed-gateway over HTTPS, backed by Apache HttpClient 5.
 *
 * <p>Unlike the SSL-bundle-driven {@code RestTemplateBuilder.sslBundle(..)} approach (where Boot
 * builds and hides the HttpClient), here we construct the {@link PoolingHttpClientConnectionManager}
 * ourselves so we can hand the very same instance to
 * {@link ConnectionPoolMetrics}. That binder registers
 * {@code httpcomponents.httpclient.pool.*} gauges (total max / available / leased / pending and the
 * per-route max) against the {@link MeterRegistry}, so the connection-pool occupancy shows up in
 * {@code /actuator/metrics}, the Prometheus scrape and the OTLP bridge — and is logged by
 * {@code ActuatorMetricsLogger.loggingHttpClientPoolMetrics()}.
 *
 * <p>TLS trust comes from the {@code creed-gateway-trust} SSL bundle (truststore.p12), which holds
 * the dev CA that signed the gateway's self-signed server certificate.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRestTemplateConfiguration {

    /** Tag value ({@code httpclient=<name>}) carried by every pool metric this binder emits. */
    public static final String GATEWAY_POOL_NAME = "creed-gateway";

    @Bean
    @ConfigurationProperties("creed.gateway")
    GatewayClientProperties gatewayClientProperties() {
        return new GatewayClientProperties();
    }

    /**
     * The pool is a separate bean so its lifecycle (and {@code close()} on shutdown) is managed by
     * Spring, and so the metrics binder and the HttpClient share one instance.
     */
    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager gatewayConnectionManager(SslBundles sslBundles,
                                                                GatewayClientProperties props) {
        SslBundle bundle = sslBundles.getBundle(props.getSslBundle());
        SSLContext sslContext = bundle.createSslContext();

        TlsSocketStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .buildClassic();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsStrategy)
                .setMaxConnTotal(props.getMaxTotalConnections())
                .setMaxConnPerRoute(props.getMaxConnectionsPerRoute())
                .build();
        // Connect timeout lives on the connection manager in HttpClient 5 (RequestConfig owns the
        // pool-lease and response timeouts).
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(props.getConnectTimeout().toMillis()))
                .build());
        return connectionManager;
    }

    /**
     * Binds the pool to Micrometer. Registering it as its own bean keeps the binding eager and
     * lets Spring inject the auto-configured {@link MeterRegistry}.
     */
    @Bean
    ConnectionPoolMetrics gatewayConnectionPoolMetrics(
            PoolingHttpClientConnectionManager gatewayConnectionManager, MeterRegistry meterRegistry) {
        ConnectionPoolMetrics binder =
                new ConnectionPoolMetrics(gatewayConnectionManager, GATEWAY_POOL_NAME);
        binder.bindTo(meterRegistry);
        return binder;
    }

    @Bean
    RestTemplate gatewayRestTemplate(HttpClientConnectionManager gatewayConnectionManager,
                                     GatewayClientProperties props) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(props.getConnectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(props.getReadTimeout().toMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(gatewayConnectionManager)
                // The pool is owned by the gatewayConnectionManager bean (closed by Spring); don't let
                // the client close it when it is itself closed.
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        // Relative paths passed to the template resolve against the gateway base URL.
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(props.getBaseUrl()));
        return restTemplate;
    }

    public static class GatewayClientProperties {
        /** Gateway HTTPS base URL, e.g. https://localhost:8080. */
        private String baseUrl = "https://localhost:8080";
        /** SSL bundle (truststore) used to trust the gateway's server certificate. */
        private String sslBundle = "creed-gateway-trust";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
        private int maxTotalConnections = 50;
        private int maxConnectionsPerRoute = 20;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSslBundle() {
            return sslBundle;
        }

        public void setSslBundle(String sslBundle) {
            this.sslBundle = sslBundle;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getMaxTotalConnections() {
            return maxTotalConnections;
        }

        public void setMaxTotalConnections(int maxTotalConnections) {
            this.maxTotalConnections = maxTotalConnections;
        }

        public int getMaxConnectionsPerRoute() {
            return maxConnectionsPerRoute;
        }

        public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        }
    }
}
