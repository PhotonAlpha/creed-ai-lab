# creed-gateway-partner — handoff

**Purpose** The **servlet** (Spring MVC) edge gateway — servlet counterpart of `creed-gateway`.
**Skill** `creed-gateway-partner` · shared: `creed-platform` · deep design notes: `README.md`

## Run

```bash
mvn -pl creed-gateway-partner spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"
curl -k https://localhost:8095/api/partner/catalog
```

HTTPS `8095`, no context-path. Profile `actuator` is included (OTel/metrics).

## Current state

**Clusters are declarative.** Each entry under `creed.partner.clusters.<name>` in `application.yml`
(bound by `web/PartnerClusterProperties`) describes one downstream: `service-id`, `client-bundle`,
business `path`, pool tunables, and health-check path + its own pool.

`web/ClusterClientBeanRegistrar` — a `BeanDefinitionRegistryPostProcessor` — then registers, **per
cluster**, real Spring beans: `<name>HttpConnectionManager` (business pool,
`destroyMethod="close"`), `<name>ClientHttpRequestFactory` (buffered, so the audit interceptor can
re-read the body), `<name>RestClient` (clones the `@LoadBalanced` builder to keep the LB interceptor,
then appends the audit interceptor), `<name>PoolMetrics`, plus the health-check pool and client.

**Adding a downstream cluster is a YAML change, not four `@Bean` methods.**

Also present: `lb/PartnerLoadBalancerConfiguration` (static `SimpleDiscoveryClient` registry + health
checks), `lb/HealthCheckServiceSupplier` (logs the probe that the stock supplier silently swallows),
`web/LoadBalancerAuditInterceptor`, `web/TomcatHttpsConfiguration`.

The health-check **path** lives per cluster in `creed.partner.clusters.<name>.health-check.path`;
only the scheduling stays under `spring.cloud.loadbalancer.health-check`.

## Landmines

- **`ClusterClientBeanRegistrar` runs as a `BeanDefinitionRegistryPostProcessor`**, so it binds
  `creed.partner.clusters` via a manual `Binder` — it executes *before* normal
  `@ConfigurationProperties` binding. A bean-name typo surfaces as a missing bean at startup, not as
  a binding error.
- **Interceptor order is List insertion order, not `@Order`.** Added in the `@Bean RestClient.Builder`
  ⇒ outer than the LB interceptor; added after post-processing ⇒ inner (sees resolved `host:port`).
- **`lb://` scheme leak**: this module uses `https://<service-id>/...` (fix #1). Don't "simplify" to
  `lb://`.
- The LB config class must have **no** `@Configuration`/`@Component` stereotype — it is imported into
  each LB child context and a stereotype leaks it into the main scan.
- SSL bundles use `file:${creed.rootPath}` → run with `-Dspring-boot.run.workingDirectory="$PWD"`.

## Recently fixed

- **The discovery registry pointed at dead ports.** `spring.cloud.discovery.client.simple.instances`
  still listed `8081/8082` and `8091/8092` from before the resource servers moved to `1808x`/`1809x`,
  so the load balancer had no reachable instance and **every aggregation call failed**. Now corrected
  to 18081/18082 and 18091/18092. If you see aggregation failing again, check these first — the
  failure mode is silent (the stock health-check supplier swallows the exception).

  **Verified end to end**: with catalog `primary` up, `GET https://localhost:8095/api/partner/catalog`
  returns 200 with catalog data, and `[LB-AUDIT][summary]` logs
  `GET https://localhost:18081/api/catalog/items -> 200 OK`.

## Open items

- `creed-simple-metrics` has since factored the same idea into a reusable `ManagedHttpClientPool`
  (one `@Bean` that is both `Closeable` and `MeterBinder`); this module still registers the
  pool/factory/binder trio separately per cluster. Worth converging.
- Only the `catalog` cluster has been exercised end to end since the port fix; `order` is corrected
  by the same edit but not separately re-run.
