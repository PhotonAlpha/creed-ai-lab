package com.creed.simple.lb;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoadBalancedRestClientConfiguration}'s bean factory methods: the mTLS pool built
 * from a resolved SSL bundle, the graceful non-mTLS fallback when the bundle is missing
 * ({@code resolveClientBundleOrNull}), the pool meter binders, the buffering request factories, and the
 * assembled {@link RestClient}s.
 */
@ExtendWith(MockitoExtension.class)
class LoadBalancedRestClientConfigurationTest {

    private static final Duration T = Duration.ofSeconds(5);

    private final LoadBalancedRestClientConfiguration config = new LoadBalancedRestClientConfiguration();

    @Mock
    private SslBundles sslBundles;

    @Test
    void clusterConnectionManagerUsesTheResolvedMtlsBundle() throws Exception {
        SslBundle bundle = org.mockito.Mockito.mock(SslBundle.class);
        lenient().when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        when(sslBundles.getBundle("creed-partner-client")).thenReturn(bundle);

        PoolingHttpClientConnectionManager cm =
                config.clusterHttpConnectionManager(sslBundles, "creed-partner-client", 40, 10, T, T);

        assertThat(cm).isNotNull();
        assertThat(cm.getMaxTotal()).isEqualTo(40);
        cm.close();
    }

    @Test
    void clusterConnectionManagerFallsBackToNonMtlsWhenBundleMissing() {
        // resolveClientBundleOrNull swallows NoSuchSslBundleException and builds a plain pool.
        when(sslBundles.getBundle("creed-partner-client"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-client", "missing"));

        PoolingHttpClientConnectionManager cm =
                config.clusterHttpConnectionManager(sslBundles, "creed-partner-client", 40, 10, T, T);

        assertThat(cm).isNotNull();
        cm.close();
    }

    @Test
    void healthCheckConnectionManagerIsBuilt() {
        when(sslBundles.getBundle("creed-partner-server"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-server", "missing"));

        PoolingHttpClientConnectionManager cm =
                config.healthCheckHttpConnectionManager(sslBundles, "creed-partner-server", 10, 5, T, T);

        assertThat(cm).isNotNull();
        assertThat(cm.getMaxTotal()).isEqualTo(10);
        cm.close();
    }

    @Test
    void poolMeterBindersRegisterAgainstAMeterRegistry() {
        PoolingHttpClientConnectionManager pool =
                RestClientSuppliers.connectionManagerFrom(null, 5, 5, T, T);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        MeterBinder clusterBinder = config.clusterCheckHttpPoolMetrics(pool);
        MeterBinder healthBinder = config.healthCheckHttpPoolMetrics(pool);
        clusterBinder.bindTo(registry);
        healthBinder.bindTo(registry);

        assertThat(registry.getMeters()).isNotEmpty();
        pool.close();
    }

    @Test
    void requestFactoriesAndRestClientsAreAssembled() {
        PoolingHttpClientConnectionManager pool =
                RestClientSuppliers.connectionManagerFrom(null, 5, 5, T, T);

        ClientHttpRequestFactory clusterFactory = config.clusterRequestFactory(pool, T, T);
        ClientHttpRequestFactory healthFactory = config.healthCheckClientHttpRequestFactory(pool, T, T);
        assertThat(clusterFactory).isNotNull();
        assertThat(healthFactory).isNotNull();

        RestClient.Builder builder = config.clusterRestClientBuilder(clusterFactory, ObservationRegistry.NOOP);
        RestClient clusterClient = config.clusterRestClient(builder, new LoadBalancerAuditInterceptor());
        RestClient healthClient = config.healthCheckRestClient(healthFactory, ObservationRegistry.NOOP);

        assertThat(builder).isNotNull();
        assertThat(clusterClient).isNotNull();
        assertThat(healthClient).isNotNull();
        pool.close();
    }
}
