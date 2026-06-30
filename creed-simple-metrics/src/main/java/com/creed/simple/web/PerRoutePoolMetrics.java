package com.creed.simple.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-route pool gauges that the built-in
 * {@link io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder}
 * does <em>not</em> provide — that binder only reads {@code getTotalStats()} and publishes pool-wide
 * aggregates ({@code httpcomponents.httpclient.pool.total.*}) plus the single
 * {@code pool.route.max.default} config value. This binder instead walks
 * {@link PoolingHttpClientConnectionManager#getRoutes()} and {@code getStats(route)} to publish one
 * series <em>per downstream route</em> (the target {@code host:port}), so you can see which resource-server
 * instance is saturating the pool.
 *
 * <p>Routes materialise lazily — a route only exists once the pool has opened a connection to that
 * target — so the row set is refreshed periodically by {@link #refresh()} ({@code @Scheduled}) using a
 * {@link MultiGauge} with {@code overwrite=true}, which prunes series for routes that are no longer
 * active. Keep the {@code route} tag cardinality bounded (here: the handful of statically-registered
 * {@code catalog-resource}/{@code order-resource} instances); do not point this at a large dynamic fleet.
 *
 * <p>Metrics published (prefix {@code httpcomponents.httpclient.pool.route}):
 * <ul>
 *   <li>{@code .connections}{@code {state=leased|available}} — live connections for that route</li>
 *   <li>{@code .pending} — callers blocked waiting to lease a connection on that route</li>
 *   <li>{@code .max} — effective max connections allowed for that route</li>
 * </ul>
 * all tagged {@code httpclient}=&lt;pool name&gt; and {@code route}=&lt;host:port&gt;.
 */
public class PerRoutePoolMetrics implements MeterBinder {

    private static final String METRIC_CONNECTIONS = "httpcomponents.httpclient.pool.route.connections";
    private static final String METRIC_PENDING = "httpcomponents.httpclient.pool.route.pending";
    private static final String METRIC_MAX = "httpcomponents.httpclient.pool.route.max";

    private final PoolingHttpClientConnectionManager pool;
    private final String poolName;

    private MultiGauge connections;
    private MultiGauge pending;
    private MultiGauge max;

    public PerRoutePoolMetrics(PoolingHttpClientConnectionManager pool, String poolName) {
        this.pool = pool;
        this.poolName = poolName;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.connections = MultiGauge.builder(METRIC_CONNECTIONS)
                .tag("httpclient", poolName)
                .description("Live connections per route, split by state (leased/available).")
                .register(registry);
        this.pending = MultiGauge.builder(METRIC_PENDING)
                .tag("httpclient", poolName)
                .description("Callers blocked waiting to lease a connection, per route.")
                .register(registry);
        this.max = MultiGauge.builder(METRIC_MAX)
                .tag("httpclient", poolName)
                .description("Effective maximum connections allowed, per route.")
                .register(registry);
        // Publish an initial (likely empty) snapshot so the meters exist before the first scheduled tick.
        refresh();
    }

    /**
     * Re-snapshots every currently-active route. Cheap (a handful of routes); {@code overwrite=true}
     * replaces the previous row set, dropping series for routes the pool has discarded. No-op until
     * {@link #bindTo(MeterRegistry)} has created the gauges.
     */
    @Scheduled(fixedDelayString = "${creed.partner.pool.route-metrics.refresh-ms:10000}")
    public void refresh() {
        if (connections == null) {
            return;
        }
        Set<HttpRoute> routes = pool.getRoutes();
        List<MultiGauge.Row<?>> connectionRows = new ArrayList<>(routes.size() * 2);
        List<MultiGauge.Row<?>> pendingRows = new ArrayList<>(routes.size());
        List<MultiGauge.Row<?>> maxRows = new ArrayList<>(routes.size());
        for (HttpRoute route : routes) {
            PoolStats stats = pool.getStats(route);
            String tag = routeTag(route);
            connectionRows.add(MultiGauge.Row.of(Tags.of("route", tag, "state", "leased"), stats.getLeased()));
            connectionRows.add(MultiGauge.Row.of(Tags.of("route", tag, "state", "available"), stats.getAvailable()));
            pendingRows.add(MultiGauge.Row.of(Tags.of("route", tag), stats.getPending()));
            maxRows.add(MultiGauge.Row.of(Tags.of("route", tag), stats.getMax()));
        }
        connections.register(connectionRows, true);
        pending.register(pendingRows, true);
        max.register(maxRows, true);
    }

    /** Stable per-route identity: the final target {@code host:port} (proxy hops are not distinguished). */
    private static String routeTag(HttpRoute route) {
        HttpHost target = route.getTargetHost();
        return target.getHostName() + ":" + target.getPort();
    }
}
