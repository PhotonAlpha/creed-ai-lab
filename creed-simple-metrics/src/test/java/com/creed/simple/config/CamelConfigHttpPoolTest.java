package com.creed.simple.config;

import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CamelConfig}'s camel-http pool binds {@code creed.camel.http.*} into {@code HttpPoolProperties}:
 * set keys win, unset keys keep the record defaults (the former {@code @Value} fallbacks), and the
 * evictor bean is built over the same pool.
 */
class CamelConfigHttpPoolTest {

    private final CamelConfig config = new CamelConfig();

    private static SslBundles missingBundles() {
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle("creed-partner-client"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-client", "test"));
        return bundles;
    }

    @Test
    void bindsConfiguredSizingFromCamelHttpProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("creed.camel.http.max-total", "7")
                .withProperty("creed.camel.http.max-per-route", "3")
                .withProperty("creed.camel.http.time-to-live", "1m");

        PoolingHttpClientConnectionManager cm =
                config.camelHttpConnectionManager(missingBundles(), "creed-partner-client", env);

        assertThat(cm.getMaxTotal()).isEqualTo(7);
        assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(3);
        cm.close();
    }

    @Test
    void unsetPropertiesFallBackToTheRecordDefaults() {
        PoolingHttpClientConnectionManager cm =
                config.camelHttpConnectionManager(missingBundles(), "creed-partner-client", new MockEnvironment());

        assertThat(cm.getMaxTotal()).isEqualTo(50);
        assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(20);
        cm.close();
    }

    @Test
    void evictorRunsOverThePoolAndStopsOnShutdown() throws Exception {
        MockEnvironment env = new MockEnvironment();
        PoolingHttpClientConnectionManager cm =
                config.camelHttpConnectionManager(missingBundles(), "creed-partner-client", env);
        IdleConnectionEvictor evictor = config.camelHttpPoolEvictor(cm, env);

        evictor.start();
        assertThat(evictor.isRunning()).isTrue();
        evictor.shutdown();
        evictor.awaitTermination(Timeout.ofSeconds(1));
        assertThat(evictor.isRunning()).isFalse();
        cm.close();
    }
}
