package com.creed.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.core5.pool.ConnPoolControl;

/**
 * Apache HttpClient 5 connection-pool gauges, under the meter names this project's dashboards and
 * Prometheus queries are written against.
 *
 * <p><b>Why this exists.</b> It replaces Micrometer's
 * {@code PoolingHttpClientConnectionManagerMetricsBinder}, which the whole
 * {@code io.micrometer.core.instrument.binder.httpcomponents.hc5} package deprecates from Micrometer
 * 1.17 (Spring Boot 4.1) in favour of Apache's {@code httpclient5-observation} artifact. That
 * artifact is not a drop-in here, for two independent reasons:
 *
 * <ul>
 *   <li><b>Different meters.</b> Apache's {@code ConnPoolMeters} emits {@code <prefix>.pool.leased},
 *       {@code .pool.available} and {@code .pool.pending} — three gauges, no {@code state} tag — and
 *       has no equivalent of {@code pool.total.max} or {@code pool.route.max.default} at all. Even
 *       with its prefix overridden, {@code httpcomponents_httpclient_pool_total_connections{state=…}}
 *       and the two capacity series our Grafana panels use (including the saturation panel 705)
 *       would disappear.</li>
 *   <li><b>Different binding surface.</b> {@code ConnPoolMeters.bindTo} takes an
 *       {@code HttpClientBuilder}. Every pool in this project is bound as a standalone
 *       {@code PoolingHttpClientConnectionManager} — Camel's {@code HttpComponent} and the partner
 *       gateway's dynamically registered clusters own their pools independently of any builder we
 *       hold.</li>
 * </ul>
 *
 * <p>So the pool half of the deprecated package is reimplemented here, deliberately gauge-for-gauge
 * and tag-for-tag identical to Micrometer 1.16's binder (including {@code Tags.concat(tags,
 * "httpclient", name)} ordering), so dashboards and PromQL are untouched. This is the one sanctioned
 * exception to the "don't hand-roll pool gauges" rule in the platform skill: upstream removed the
 * capability, it was not ours to reuse.
 *
 * <p>The <em>request</em> half of that package ({@code ObservationExecChainHandler}) is a separate
 * migration and does have a true upstream replacement — see the module notes.
 */
public class ConnectionPoolMetrics implements MeterBinder {

    private final ConnPoolControl<HttpRoute> connPoolControl;
    private final Iterable<Tag> tags;

    /**
     * @param connPoolControl the pool to observe (a {@code PoolingHttpClientConnectionManager})
     * @param name            pool name; becomes the {@code httpclient} tag
     * @param tags            extra key/value pairs, an even number of arguments
     */
    public ConnectionPoolMetrics(ConnPoolControl<HttpRoute> connPoolControl, String name, String... tags) {
        this(connPoolControl, name, Tags.of(tags));
    }

    public ConnectionPoolMetrics(ConnPoolControl<HttpRoute> connPoolControl, String name, Iterable<Tag> tags) {
        this.connPoolControl = connPoolControl;
        this.tags = Tags.concat(tags, "httpclient", name);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("httpcomponents.httpclient.pool.total.max", connPoolControl,
                        pool -> pool.getTotalStats().getMax())
                .description("The configured maximum number of allowed persistent connections for all routes.")
                .tags(tags)
                .register(registry);
        // One meter name, two series: the `state` tag is what the dashboards facet on, so available
        // and leased must NOT become separate meters.
        Gauge.builder("httpcomponents.httpclient.pool.total.connections", connPoolControl,
                        pool -> pool.getTotalStats().getAvailable())
                .description("The number of persistent and available connections for all routes.")
                .tags(tags)
                .tag("state", "available")
                .register(registry);
        Gauge.builder("httpcomponents.httpclient.pool.total.connections", connPoolControl,
                        pool -> pool.getTotalStats().getLeased())
                .description("The number of persistent and leased connections for all routes.")
                .tags(tags)
                .tag("state", "leased")
                .register(registry);
        Gauge.builder("httpcomponents.httpclient.pool.total.pending", connPoolControl,
                        pool -> pool.getTotalStats().getPending())
                .description("The number of connection requests being blocked awaiting a free connection for all routes.")
                .tags(tags)
                .register(registry);
        Gauge.builder("httpcomponents.httpclient.pool.route.max.default", connPoolControl,
                        ConnPoolControl::getDefaultMaxPerRoute)
                .description("The configured default maximum number of allowed persistent connections per route.")
                .tags(tags)
                .register(registry);
    }
}
