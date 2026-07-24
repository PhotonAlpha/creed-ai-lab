package com.creed.simple.lb;

import lombok.experimental.UtilityClass;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

@UtilityClass
public final class RestClientSuppliers {

    private static final Logger log = LoggerFactory.getLogger(RestClientSuppliers.class);

    /**
     * Builds a complete {@link ManagedHttpClientPool} (pool + buffering request factory + pool metrics)
     * from a {@link HttpPoolProperties} block and the shared mTLS bundle name. This is the single-call
     * template that collapses the former connection-manager / request-factory / meter-binder bean trio
     * into one self-managing bean.
     */
    public static ManagedHttpClientPool pool(
            SslBundles sslBundles, String bundleName, HttpPoolProperties props, String metricsName) {
        PoolingHttpClientConnectionManager connectionManager = connectionManagerFrom(
                resolveClientBundleOrNull(sslBundles, bundleName),
                props.maxTotal(), props.maxPerRoute(),
                props.connectTimeout(), props.socketTimeout());
        var requestFactory = new BufferingClientHttpRequestFactory(
                requestFactoryFrom(connectionManager,
                        props.connectionRequestTimeout(), props.responseTimeout()));
        return new ManagedHttpClientPool(connectionManager, requestFactory, metricsName);
    }

    /**
     * Resolves the outbound mTLS bundle by name, returning {@code null} when it is not registered.
     * {@code SslBundleConfiguration} deliberately skips registering the client bundle when its
     * keystore/truststore fails to load, so this graceful lookup lets the pool fall back to a non-mTLS
     * (plain) client instead of failing the context. A {@code null} bundle is the agreed degraded mode:
     * the service boots, but downstream HTTPS calls may fail the handshake at runtime.
     */
    public static SslBundle resolveClientBundleOrNull(SslBundles sslBundles, String bundleName) {
        try {
            return sslBundles.getBundle(bundleName);
        } catch (NoSuchSslBundleException ex) {
            log.warn("mTLS SSL bundle '{}' is not registered — building a NON-mTLS connection manager;"
                    + " downstream HTTPS calls may fail the handshake at runtime.", bundleName);
            return null;
        }
    }

    /**
     * Builds a pooled connection manager. When {@code bundle} is non-null the pool carries the bundle's
     * mTLS material (client cert + CA truststore); when it is {@code null} no custom TLS strategy is
     * installed, so HttpClient falls back to the JVM system-default SSLContext — the degraded, non-mTLS
     * mode used when the client SSL bundle could not be loaded at startup. Downstream HTTPS calls to the
     * Creed-CA-signed resource servers will then fail the handshake at runtime.
     */
    public static PoolingHttpClientConnectionManager connectionManagerFrom(
            SslBundle bundle, int maxTotal, int maxPerRoute, Duration connectTimeout, Duration socketTimeout) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(socketTimeout.toMillis()))
                .build();
        PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig);
        if (bundle != null) {
            builder.setTlsSocketStrategy(new DefaultClientTlsStrategy(bundle.createSslContext()));
        }
        return builder.build();
    }

    public static HttpComponentsClientHttpRequestFactory requestFactoryFrom(
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
