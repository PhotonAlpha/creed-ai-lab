package com.creed.simple.web;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PerRoutePoolMetrics}: it publishes one series per active route (leased/available
 * connections, pending waiters, effective max) tagged {@code httpclient}/{@code route}, refreshes with
 * {@code overwrite=true} to prune dead routes, and is a safe no-op before {@code bindTo}.
 */
class PerRoutePoolMetricsTest {

    private static final String CONNECTIONS = "httpcomponents.httpclient.pool.route.connections";
    private static final String PENDING = "httpcomponents.httpclient.pool.route.pending";
    private static final String MAX = "httpcomponents.httpclient.pool.route.max";

    private final PoolingHttpClientConnectionManager pool = mock(PoolingHttpClientConnectionManager.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private HttpRoute route(String host, int port) {
        return new HttpRoute(new HttpHost(host, port));
    }

    @Test
    void publishesPerRouteConnectionPendingAndMaxSeries() {
        HttpRoute route = route("localhost", 8081);
        when(pool.getRoutes()).thenReturn(Set.of(route));
        when(pool.getStats(route)).thenReturn(new PoolStats(2, 1, 3, 10)); // leased, pending, available, max

        new PerRoutePoolMetrics(pool, "loadBalancedPool").bindTo(registry);

        assertThat(gauge(CONNECTIONS, "localhost:8081", "leased")).isEqualTo(2.0);
        assertThat(gauge(CONNECTIONS, "localhost:8081", "available")).isEqualTo(3.0);
        assertThat(registry.get(PENDING).tags("route", "localhost:8081", "httpclient", "loadBalancedPool")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(MAX).tags("route", "localhost:8081", "httpclient", "loadBalancedPool")
                .gauge().value()).isEqualTo(10.0);
    }

    @Test
    void refreshPrunesSeriesForRoutesThePoolHasDropped() {
        HttpRoute route = route("localhost", 8081);
        when(pool.getRoutes()).thenReturn(Set.of(route));
        when(pool.getStats(route)).thenReturn(new PoolStats(1, 0, 0, 10));

        PerRoutePoolMetrics metrics = new PerRoutePoolMetrics(pool, "loadBalancedPool");
        metrics.bindTo(registry);
        assertThat(registry.get(CONNECTIONS).gauges()).isNotEmpty();

        // The pool discards the route; overwrite=true drops its rows on the next refresh.
        when(pool.getRoutes()).thenReturn(Set.of());
        metrics.refresh();
        double remaining = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(CONNECTIONS))
                .filter(Gauge.class::isInstance)
                .mapToDouble(m -> ((Gauge) m).value())
                .sum();
        assertThat(remaining).isZero();
    }

    @Test
    void refreshBeforeBindIsANoOp() {
        PerRoutePoolMetrics metrics = new PerRoutePoolMetrics(pool, "loadBalancedPool");
        assertThatCode(metrics::refresh).doesNotThrowAnyException();
    }

    private double gauge(String name, String route, String state) {
        return registry.get(name)
                .tags("route", route, "state", state, "httpclient", "loadBalancedPool")
                .gauge().value();
    }
}
