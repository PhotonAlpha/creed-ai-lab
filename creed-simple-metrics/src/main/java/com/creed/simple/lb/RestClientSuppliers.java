package com.creed.simple.lb;

import lombok.experimental.UtilityClass;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.time.Duration;

@UtilityClass
public final class RestClientSuppliers {

    private static final Logger log = LoggerFactory.getLogger(RestClientSuppliers.class);

    /**
     * Builds a complete {@link ManagedHttpClientPool} (pool + buffering request factory + idle evictor +
     * pool metrics) from a {@link HttpPoolProperties} block and the shared mTLS bundle name. This is the
     * single-call template that collapses the former connection-manager / request-factory / meter-binder
     * bean trio into one self-managing bean. The evictor is started here and stopped by
     * {@link ManagedHttpClientPool#close()}.
     */
    public static ManagedHttpClientPool pool(
            SslBundles sslBundles, String bundleName, HttpPoolProperties props, String metricsName) {
        PoolingHttpClientConnectionManager connectionManager =
                connectionManagerFrom(resolveClientBundleOrNull(sslBundles, bundleName), props);
        ConnectionKeepAliveStrategy keepAliveStrategy = keepAliveStrategy(props.keepAlive());
        var requestFactory = new BufferingClientHttpRequestFactory(
                requestFactoryFrom(connectionManager, keepAliveStrategy,
                        props.connectionRequestTimeout(), props.responseTimeout()));
        IdleConnectionEvictor evictor = evictorFor(connectionManager, props);
        evictor.start();
        return new ManagedHttpClientPool(connectionManager, requestFactory, keepAliveStrategy, evictor, metricsName);
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
            SslBundle bundle, HttpPoolProperties props) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(props.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(props.socketTimeout().toMillis()))
                // Default is no TTL: a connection would stay pinned to its first instance forever.
                .setTimeToLive(TimeValue.ofMilliseconds(props.timeToLive().toMillis()))
                .setValidateAfterInactivity(TimeValue.ofMilliseconds(props.validateAfterInactivity().toMillis()))
                .build();
        PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(props.maxTotal())
                .setMaxConnPerRoute(props.maxPerRoute())
                .setDefaultConnectionConfig(connectionConfig);

        if (bundle != null) {
            //解决无法创建自定义SSL Context的问题
            SslStoreBundle stores = bundle.getStores();
            String pw = bundle.getKey().getPassword();
            char[] keyPw = pw != null ? pw.toCharArray() : new char[0];
            try {
                SSLContext ctx = SSLContexts.custom()
                        .loadKeyMaterial(stores.getKeyStore(), keyPw)
                        .loadTrustMaterial(stores.getTrustStore(), null)
                        .build();
                TlsSocketStrategy tlsSocketStrategy = ClientTlsStrategyBuilder.create()
                        .setSslContext(ctx)
                        .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .buildClassic();
                builder.setTlsSocketStrategy(tlsSocketStrategy);
            } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException |
                     UnrecoverableKeyException e) {
                throw new IllegalStateException("Cannot initialize SSL context" + e.getMessage(), e);
            }
        } else {
            log.warn("SslBundle is not available, mTLS will be disabled.");
        }

        return builder.build();
    }

    /**
     * Caps how long a returned connection counts as reusable. A server-sent {@code Keep-Alive: timeout=N}
     * is still honoured when it is shorter; without the header (every Tomcat downstream) HttpClient would
     * fall back to {@code RequestConfig}'s 3-minute default, well past Tomcat's 60 s idle close.
     * Every {@code HttpClientBuilder} riding a pool needs this — the strategy lives on the client, not the
     * pool.
     */
    public static ConnectionKeepAliveStrategy keepAliveStrategy(Duration max) {
        TimeValue cap = TimeValue.ofMilliseconds(max.toMillis());
        return (response, context) -> {
            TimeValue offered = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
            return TimeValue.isNonNegative(offered) && offered.compareTo(cap) < 0 ? offered : cap;
        };
    }

    /**
     * Background {@code closeExpired()} + {@code closeIdle(maxIdleTime)} sweep for one pool, NOT started.
     * Must be owned explicitly: {@code HttpClientBuilder.evictIdleConnections()} is a silent no-op with
     * {@code setConnectionManagerShared(true)} (the builder only starts an evictor for a pool it owns),
     * and every client here shares a Spring/Camel-owned pool. The evictor thread dies silently on any
     * exception ({@code catch (Exception ignore)} in httpclient5) — harmless for these two calls, which
     * are no-ops on a closed pool.
     */
    public static IdleConnectionEvictor evictorFor(
            PoolingHttpClientConnectionManager connectionManager, HttpPoolProperties props) {
        return new IdleConnectionEvictor(connectionManager,
                TimeValue.ofMilliseconds(props.evictionInterval().toMillis()),
                TimeValue.ofMilliseconds(props.maxIdleTime().toMillis()));
    }

    public static HttpComponentsClientHttpRequestFactory requestFactoryFrom(
            PoolingHttpClientConnectionManager connectionManager, ConnectionKeepAliveStrategy keepAliveStrategy,
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
                .setKeepAliveStrategy(keepAliveStrategy)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
