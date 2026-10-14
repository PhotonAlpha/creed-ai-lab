package com.creed.simple.lb;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Unit tests for {@link LoadBalancedRestClientConfiguration}'s bean factory methods and the
 * {@link ManagedHttpClientPool} template they build on: the mTLS pool from a resolved SSL bundle, the
 * graceful non-mTLS fallback when the bundle is missing ({@code resolveClientBundleOrNull}), the pool
 * meter binding, the buffering request factory, and the assembled {@link RestClient}s.
 */
@ExtendWith(MockitoExtension.class)
class LoadBalancedRestClientConfigurationTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final HttpPoolProperties P = HttpPoolProperties.of(5, 5, T, T, T, T);

    private final LoadBalancedRestClientConfiguration config = new LoadBalancedRestClientConfiguration();

    @Mock
    private SslBundles sslBundles;

    private static PartnerProps propsWithBundle(String bundleName) {
        HttpPoolProperties business = HttpPoolProperties.of(40, 10, T, T, T, T);
        HttpPoolProperties health = HttpPoolProperties.of(10, 5, T, T, T, T);
        return new PartnerProps(bundleName, business, new PartnerProps.HealthCheck(health));
    }

    /** A pool around an existing manager; its evictor is built but never started (nothing to sweep). */
    private static ManagedHttpClientPool poolOf(PoolingHttpClientConnectionManager cm,
                                                ClientHttpRequestFactory factory, String name) {
        return new ManagedHttpClientPool(cm, factory, RestClientSuppliers.keepAliveStrategy(T),
                RestClientSuppliers.evictorFor(cm, P), name);
    }

    @Test
    void clusterPoolUsesTheResolvedMtlsBundle() throws Exception {
        SslBundle bundle = org.mockito.Mockito.mock(SslBundle.class);
        lenient().when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        when(sslBundles.getBundle("creed-partner-client")).thenReturn(bundle);

        ManagedHttpClientPool pool = config.clusterPool(sslBundles, propsWithBundle("creed-partner-client"));

        assertThat(pool).isNotNull();
        assertThat(pool.connectionManager().getMaxTotal()).isEqualTo(40);
        assertThat(pool.requestFactory()).isNotNull();
        pool.close();
    }

    @Test
    void clusterPoolFallsBackToNonMtlsWhenBundleMissing() {
        // resolveClientBundleOrNull swallows NoSuchSslBundleException and builds a plain pool.
        when(sslBundles.getBundle("creed-partner-client"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-client", "missing"));

        ManagedHttpClientPool pool = config.clusterPool(sslBundles, propsWithBundle("creed-partner-client"));

        assertThat(pool).isNotNull();
        assertThat(pool.connectionManager()).isNotNull();
        pool.close();
    }

    @Test
    void healthCheckPoolIsBuilt() {
        when(sslBundles.getBundle("creed-partner-server"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-server", "missing"));

        ManagedHttpClientPool pool = config.healthCheckPool(sslBundles, propsWithBundle("creed-partner-server"));

        assertThat(pool).isNotNull();
        assertThat(pool.connectionManager().getMaxTotal()).isEqualTo(10);
        pool.close();
    }

    @Test
    void poolBindsItsMetersToARegistry() {
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, P);
        ManagedHttpClientPool pool = poolOf(cm,
                RestClientSuppliers.requestFactoryFrom(cm, RestClientSuppliers.keepAliveStrategy(T), T, T), "testPool");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        pool.bindTo(registry);

        assertThat(registry.getMeters()).isNotEmpty();
        pool.close();
    }

    @Test
    void restClientsAreAssembledFromThePools() {
        PoolingHttpClientConnectionManager cm =
                RestClientSuppliers.connectionManagerFrom(null, P);
        ClientHttpRequestFactory factory = RestClientSuppliers.requestFactoryFrom(cm, RestClientSuppliers.keepAliveStrategy(T), T, T);
        ManagedHttpClientPool clusterPool = poolOf(cm, factory, "loadBalancedPool");
        ManagedHttpClientPool healthCheckPool = poolOf(cm, factory, "healthCheckPool");

        RestClient.Builder builder = config.clusterRestClientBuilder(clusterPool, ObservationRegistry.NOOP);
        RestClient clusterClient = config.clusterRestClient(builder, new LoadBalancerAuditInterceptor());
        RestClient healthClient = config.healthCheckRestClient(healthCheckPool, ObservationRegistry.NOOP);

        assertThat(builder).isNotNull();
        assertThat(clusterClient).isNotNull();
        assertThat(healthClient).isNotNull();
        cm.close();
    }
}
