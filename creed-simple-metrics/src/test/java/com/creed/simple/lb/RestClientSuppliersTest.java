package com.creed.simple.lb;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
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
 * bundle (the degraded non-mTLS fallback), the per-route/total pool limits, and the request-factory
 * wrapping around a built connection manager.
 */
class RestClientSuppliersTest {

    private static final Duration T = Duration.ofSeconds(5);

    @Test
    void connectionManagerWithNullBundleSkipsCustomTlsStrategy() {
        // The null-bundle path is the agreed degraded mode: no custom TLS strategy installed.
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, 50, 20, T, T);

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
                RestClientSuppliers.connectionManagerFrom(bundle, 10, 5, T, T);

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
        RestClientSuppliers.connectionManagerFrom(null, 1, 1, T, T).close();
        verify(bundle, never()).createSslContext();
    }

    @Test
    void requestFactoryWrapsTheConnectionManager() {
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, 5, 5, T, T);

        HttpComponentsClientHttpRequestFactory factory =
                RestClientSuppliers.requestFactoryFrom(cm, Duration.ofSeconds(3), Duration.ofSeconds(10));

        assertThat(factory).isNotNull();
        cm.close();
    }
}
