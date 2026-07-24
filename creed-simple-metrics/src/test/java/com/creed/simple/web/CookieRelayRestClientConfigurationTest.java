package com.creed.simple.web;

import com.creed.simple.lb.HttpPoolProperties;
import com.creed.simple.lb.ManagedHttpClientPool;
import com.creed.simple.lb.PartnerProps;
import com.creed.simple.lb.RestClientSuppliers;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.Logbook;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CookieRelayRestClientConfiguration}: the dedicated cookie-relay client is
 * assembled over the shared {@link ManagedHttpClientPool}'s connection manager with a Logbook + LB-audit
 * exec chain, and the builder and final {@link RestClient} are produced.
 */
class CookieRelayRestClientConfigurationTest {

    private static final Duration T = Duration.ofSeconds(5);

    private final CookieRelayRestClientConfiguration config = new CookieRelayRestClientConfiguration();

    private static ManagedHttpClientPool nonMtlsPool() {
        // Mocked bundles that report the bundle missing → RestClientSuppliers builds a plain (non-mTLS) pool.
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle("creed-partner-client"))
                .thenThrow(new NoSuchSslBundleException("creed-partner-client", "missing"));
        return RestClientSuppliers.pool(
                bundles, "creed-partner-client", new HttpPoolProperties(5, 5, T, T, T, T), "test");
    }

    private static PartnerProps props() {
        HttpPoolProperties pool = new HttpPoolProperties(5, 5, T, T, T, T);
        return new PartnerProps("creed-partner-client", pool, new PartnerProps.HealthCheck(pool));
    }

    @Test
    void assemblesTheCookieRelayRequestFactoryAndClient() {
        ManagedHttpClientPool pool = nonMtlsPool();
        Logbook logbook = mock(Logbook.class);

        ClientHttpRequestFactory requestFactory =
                config.cookieRelayRequestFactory(pool, logbook, props());
        assertThat(requestFactory).isNotNull();

        RestClient.Builder builder =
                config.cookieRelayRestClientBuilder(requestFactory, ObservationRegistry.NOOP);
        assertThat(builder).isNotNull();

        RestClient client = config.cookieRelayRestClient(builder);
        assertThat(client).isNotNull();
        pool.close();
    }
}
