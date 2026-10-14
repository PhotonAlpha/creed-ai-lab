package com.creed.simple.lb;

import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestClientSuppliers}: connection-manager construction with and without an SSL
 * bundle (the degraded non-mTLS fallback), the per-route/total pool limits, the request-factory
 * wrapping around a built connection manager, the keep-alive cap, and the evictor's lifecycle.
 */
class RestClientSuppliersTest {

    private static final Duration T = Duration.ofSeconds(5);

    @Test
    void connectionManagerWithNullBundleSkipsCustomTlsStrategy() {
        // The null-bundle path is the agreed degraded mode: no custom TLS strategy installed.
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, HttpPoolProperties.of(50, 20, T, T, T, T));

        assertThat(cm).isNotNull();
        assertThat(cm.getMaxTotal()).isEqualTo(50);
        assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(20);
        cm.close();
    }

    @Test
    void connectionManagerWithBundleInstallsBundleTlsMaterial() throws Exception {
        SslBundle bundle = mock(SslBundle.class);
        when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());

        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(bundle, HttpPoolProperties.of(10, 5, T, T, T, T));

        assertThat(cm).isNotNull();
        assertThat(cm.getMaxTotal()).isEqualTo(10);
        assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(5);
        // The bundle's mTLS context was pulled to build the TLS socket strategy.
        verify(bundle).createSslContext();
        cm.close();
    }

    @Test
    void nullBundleNeverTouchesTheBundle() {
        SslBundle bundle = mock(SslBundle.class);
        RestClientSuppliers.connectionManagerFrom(null, HttpPoolProperties.of(1, 1, T, T, T, T)).close();
        verify(bundle, never()).createSslContext();
    }

    @Test
    void requestFactoryWrapsTheConnectionManager() {
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, HttpPoolProperties.of(5, 5, T, T, T, T));

        HttpComponentsClientHttpRequestFactory factory =
                RestClientSuppliers.requestFactoryFrom(cm, RestClientSuppliers.keepAliveStrategy(T),
                        Duration.ofSeconds(3), Duration.ofSeconds(10));

        assertThat(factory).isNotNull();
        cm.close();
    }

    @Test
    void keepAliveFallsBackToTheCapWhenTheServerSendsNoHeader() {
        // Tomcat's case: without the cap HttpClient would assume RequestConfig's 3-minute default.
        ConnectionKeepAliveStrategy strategy = RestClientSuppliers.keepAliveStrategy(Duration.ofSeconds(30));

        assertThat(strategy.getKeepAliveDuration(new BasicHttpResponse(200), HttpClientContext.create()))
                .isEqualTo(TimeValue.ofSeconds(30));
    }

    @Test
    void keepAliveHonoursAShorterServerTimeoutButCapsALongerOne() {
        ConnectionKeepAliveStrategy strategy = RestClientSuppliers.keepAliveStrategy(Duration.ofSeconds(30));

        BasicHttpResponse shorter = new BasicHttpResponse(200);
        shorter.addHeader("Keep-Alive", "timeout=5");
        BasicHttpResponse longer = new BasicHttpResponse(200);
        longer.addHeader("Keep-Alive", "timeout=120");

        assertThat(strategy.getKeepAliveDuration(shorter, HttpClientContext.create()))
                .isEqualTo(TimeValue.ofSeconds(5));
        assertThat(strategy.getKeepAliveDuration(longer, HttpClientContext.create()))
                .isEqualTo(TimeValue.ofSeconds(30));
    }

    @Test
    void poolStartsItsEvictorAndCloseStopsIt() {
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle("missing")).thenThrow(new NoSuchSslBundleException("missing", "test"));

        ManagedHttpClientPool pool = RestClientSuppliers.pool(
                bundles, "missing", HttpPoolProperties.of(5, 5, T, T, T, T), "test");
        assertThat(pool.evictorRunning()).isTrue();
        assertThat(pool.keepAliveStrategy()).isNotNull();

        pool.close();
        assertThat(pool.evictorRunning()).isFalse();
    }
}
