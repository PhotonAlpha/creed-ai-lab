package com.creed.simple.config;

import com.creed.simple.lb.HttpPoolProperties;
import com.creed.simple.lb.LoadBalancerRoutePlanner;
import io.micrometer.observation.ObservationRegistry;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.component.http.HttpEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.mock.env.MockEnvironment;
import org.zalando.logbook.Logbook;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

/**
 * Per-endpoint pools: a named component ({@code catalog-bulk:https://...}) next to the default {@code https} one,
 * each endpoint on its own component's pool, and a stopped endpoint never closing a pool its siblings still use.
 */
class CamelHttpComponentsTest {

    private final CamelHttpComponents.Shared shared = new CamelHttpComponents.Shared(
            new LoadBalancerRoutePlanner(mock(LoadBalancerClient.class), mock(DiscoveryClient.class)),
            ObservationRegistry.NOOP, Logbook.create(), 100, Duration.ofSeconds(1));
    private final HttpPoolProperties props = CamelHttpComponents.defaults(new MockEnvironment());

    private DefaultCamelContext camel;
    private PoolingHttpClientConnectionManager defaultPool;
    private PoolingHttpClientConnectionManager bulkPool;

    @BeforeEach
    void camel() {
        defaultPool = new PoolingHttpClientConnectionManager();
        bulkPool = new PoolingHttpClientConnectionManager();
        camel = new DefaultCamelContext();
        camel.getRegistry().bind("https", CamelHttpComponents.create(defaultPool, props, shared));
        camel.getRegistry().bind("catalog-bulk", CamelHttpComponents.createNamed("catalog-bulk", bulkPool, props, shared));
        camel.start();
    }

    @AfterEach
    void stop() {
        camel.stop();
    }

    @Test
    void anEndpointRidesItsComponentsPoolOverHttps() {
        HttpEndpoint bulk = camel.getEndpoint("catalog-bulk:https://catalog-resource/api/catalog/bulk", HttpEndpoint.class);
        HttpEndpoint items = camel.getEndpoint("https://catalog-resource/api/catalog/items", HttpEndpoint.class);

        assertThat(bulk.getClientConnectionManager()).isSameAs(bulkPool);
        assertThat(bulk.getHttpUri()).hasScheme("https").hasHost("catalog-resource").hasPath("/api/catalog/bulk");
        assertThat(items.getClientConnectionManager()).isSameAs(defaultPool);
    }

    @Test
    void aNamedComponentWithoutTheProtocolIsRefusedNotSentAsPlainHttp() {
        // camel-http decides TLS by uri.startsWith("https"): "catalog-bulk://..." would be plain HTTP
        assertThatThrownBy(() -> camel.getEndpoint("catalog-bulk://catalog-resource/api/catalog/bulk"))
                .isInstanceOf(ResolveEndpointFailedException.class)
                .rootCause().hasMessageContaining("catalog-bulk:https://<service-id>/...");
        assertThat(camel.getEndpoint("catalog-bulk:http://catalog-resource/x", HttpEndpoint.class).getHttpUri())
                .hasScheme("http");
    }

    @Test
    void stoppingOneEndpointLeavesTheSharedPoolOpen() throws Exception {
        HttpEndpoint first = camel.getEndpoint("catalog-bulk:https://catalog-resource/api/catalog/bulk", HttpEndpoint.class);
        HttpEndpoint second = camel.getEndpoint("catalog-bulk:https://catalog-resource/api/catalog/export", HttpEndpoint.class);
        first.getHttpClient();
        second.getHttpClient();

        first.stop();

        // a closed pool refuses every lease ("Connection pool shut down")
        assertThatNoException().isThrownBy(() -> leaseAndRelease(bulkPool));
        camel.stop();
        assertThatThrownBy(() -> leaseAndRelease(bulkPool)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNamedPoolStatesOnlyWhatDiffersFromTheDefaultOne() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("creed.camel.http.max-total", "40")
                .withProperty("creed.camel.http.response-timeout", "8s")
                .withProperty("creed.camel.http-components.catalog-bulk.max-per-route", "5")
                .withProperty("creed.camel.http-components.catalog-bulk.response-timeout", "60s");

        HttpPoolProperties bulk = CamelHttpComponents.named(env, "catalog-bulk");

        assertThat(bulk.maxPerRoute()).isEqualTo(5);                        // its own
        assertThat(bulk.responseTimeout()).isEqualTo(Duration.ofSeconds(60)); // its own, over the default block's
        assertThat(bulk.maxTotal()).isEqualTo(40);                          // inherited from creed.camel.http
        assertThat(bulk.connectTimeout()).isEqualTo(Duration.ofSeconds(5)); // neither: the record default
        assertThat(CamelHttpComponents.names(env)).containsExactly("catalog-bulk");
    }

    @Test
    void aComponentNameMustBeALowerCaseSchemeOtherThanHttp() {
        for (String bad : new String[] {"Catalog", "catalog_bulk", "9bulk", "http", "https"}) {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("creed.camel.http-components." + bad + ".max-total", "5");
            assertThatThrownBy(() -> CamelHttpComponents.names(env)).as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    private static void leaseAndRelease(PoolingHttpClientConnectionManager pool) throws Exception {
        ConnectionEndpoint endpoint = pool.lease("test", new HttpRoute(new HttpHost("localhost", 1)), Timeout.ofSeconds(1), null)
                .get(Timeout.ofSeconds(1));
        pool.release(endpoint, null, TimeValue.ZERO_MILLISECONDS);
    }
}
