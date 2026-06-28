package com.creed.partner.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Declarative description of every downstream cluster the partner gateway aggregates. Each entry under
 * {@code creed.partner.clusters.<name>} produces, in {@link PartnerClientConfiguration#clusterClients},
 * a full set of runtime objects (business pool + load-balanced/audited {@code RestClient}, health-check
 * pool + {@code RestClient}, and the two Micrometer pool binders) — so adding a cluster is a YAML change,
 * not four new {@code @Bean} methods.
 *
 * <p>Records bind with {@link DefaultValue} so a sparse YAML entry still yields sane pool sizes/timeouts;
 * only {@code service-id} (and usually {@code path} / {@code health-check.path}) need to be supplied.
 *
 * @param clusters cluster name (used as the metric suffix and the {@code /api/partner/{name}} path) → spec
 */
@ConfigurationProperties("creed.partner")
public record PartnerClusterProperties(Map<String, ClusterSpec> clusters) {

    /**
     * One downstream cluster.
     *
     * @param serviceId   load-balancer service-id resolved against the {@code SimpleDiscoveryClient}
     *                    registry; the business URL is {@code https://<serviceId><path>}
     * @param clientBundle SSL bundle used for both the business and health-check (mTLS) calls
     * @param path        downstream business API path appended to the service-id for aggregation
     * @param http        business connection-pool tunables
     * @param healthCheck health-check path + its (smaller) connection-pool tunables
     */
    public record ClusterSpec(
            String serviceId,
            @DefaultValue("creed-partner-client") String clientBundle,
            @DefaultValue("") String path,
            @DefaultValue PoolSpec http,
            @DefaultValue HealthCheckSpec healthCheck) {
    }

    /**
     * @param path the probe path appended to each instance URI (overrides Spring Cloud's
     *             {@code spring.cloud.loadbalancer.health-check.path}); default {@code /actuator/health}
     * @param http the health-check pool tunables (defaults match the business pool but are normally set
     *             smaller in YAML)
     */
    public record HealthCheckSpec(
            @DefaultValue("/actuator/health") String path,
            @DefaultValue PoolSpec http) {
    }

    /** Apache HttpClient 5 pool + timeout tunables, mirroring the {@code RestClientSuppliers} parameters. */
    public record PoolSpec(
            @DefaultValue("50") int maxTotal,
            @DefaultValue("20") int maxPerRoute,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration socketTimeout,
            @DefaultValue("3s") Duration connectionRequestTimeout,
            @DefaultValue("10s") Duration responseTimeout) {
    }
}
