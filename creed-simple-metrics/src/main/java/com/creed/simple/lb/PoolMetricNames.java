package com.creed.simple.lb;

/**
 * The {@code httpclient} tag of every connection pool's gauges ({@code httpcomponents_httpclient_pool_*}), by one
 * rule instead of a string per call site: a pool family's base name for its default pool, {@code <base>-<name>}
 * for each pool declared by name in a batch block.
 *
 * <table>
 *   <tr><th>pool</th><th>declared by</th><th>tag</th></tr>
 *   <tr><td>{@code clusterPool}</td><td>{@code creed.partner.http}</td><td>{@code loadBalancedPool}</td></tr>
 *   <tr><td>{@code <name>Pool}</td><td>{@code creed.partner.rest-clients.<name>}</td><td>{@code loadBalancedPool-<name>}</td></tr>
 *   <tr><td>{@code healthCheckPool}</td><td>{@code creed.partner.health-check.http}</td><td>{@code healthCheckPool}</td></tr>
 *   <tr><td>{@code camelHttpConnectionManager}</td><td>{@code creed.camel.http}</td><td>{@code camelHttpPool}</td></tr>
 *   <tr><td>{@code <name>ConnectionManager}</td><td>{@code creed.camel.http-components.<name>}</td><td>{@code camelHttpPool-<name>}</td></tr>
 * </table>
 *
 * The base names are what the Grafana panels and the PromQL in {@code docs/connection-pool-tuning.md} filter on —
 * renaming one breaks them.
 */
public final class PoolMetricNames {

    /** {@code @LoadBalanced} RestClient pools. */
    public static final String LOAD_BALANCED = "loadBalancedPool";
    /** The LoadBalancer health-check probe's pool. */
    public static final String HEALTH_CHECK = "healthCheckPool";
    /** camel-http component pools. */
    public static final String CAMEL_HTTP = "camelHttpPool";

    private PoolMetricNames() {
    }

    /** The family's default pool. */
    public static String of(String base) {
        return base;
    }

    /** A pool declared as {@code name} in a batch block: {@code <base>-<name>}. */
    public static String of(String base, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a named pool needs a name; use of(base) for the default pool");
        }
        return base + "-" + name;
    }
}
