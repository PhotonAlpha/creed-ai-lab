package com.creed.simple.web;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.Logbook;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CookieRelayRestClientConfiguration}: the dedicated cookie-relay client is
 * assembled over the shared connection manager with a Logbook + LB-audit exec chain, and the builder and
 * final {@link RestClient} are produced.
 */
class CookieRelayRestClientConfigurationTest {

    private static final Duration T = Duration.ofSeconds(5);

    private final CookieRelayRestClientConfiguration config = new CookieRelayRestClientConfiguration();

    @Test
    void assemblesTheCookieRelayRequestFactoryAndClient() {
        PoolingHttpClientConnectionManager pool = mock(PoolingHttpClientConnectionManager.class);
        Logbook logbook = mock(Logbook.class);

        ClientHttpRequestFactory requestFactory =
                config.cookieRelayRequestFactory(pool, logbook, T, T);
        assertThat(requestFactory).isNotNull();

        RestClient.Builder builder =
                config.cookieRelayRestClientBuilder(requestFactory, ObservationRegistry.NOOP);
        assertThat(builder).isNotNull();

        RestClient client = config.cookieRelayRestClient(builder);
        assertThat(client).isNotNull();
    }
}
