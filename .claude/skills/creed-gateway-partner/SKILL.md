---
name: creed-gateway-partner
description: The creed-gateway-partner module — the SERVLET edge gateway on HTTPS 8095, aggregating downstream resource servers via @LoadBalanced RestClients with client-side LoadBalancer + health checks. Downstream clusters are declared in YAML (creed.partner.clusters.<name>) and their pools/clients/metrics beans are registered dynamically, with isolated business vs health-check Apache HttpClient 5 pools, an audit interceptor, and mTLS. Use when adding or tuning a cluster, or working on the load balancer config/health-check logging, connection pools, interceptor ordering, or partner aggregation.
---

# creed-gateway-partner

The **servlet** edge gateway (Spring MVC, embedded Tomcat). HTTPS `8095`, no context-path. Servlet counterpart of the reactive [[creed-gateway]]. Profile `actuator` is `include`d (OTel/metrics — see [[creed-platform]] Observability). See [[creed-platform]] for SSL bundles, the `lb://` scheme leak, HttpClient 5 wiring, and LB health checks. Deep design notes: `creed-gateway-partner/README.md`.

## Aggregation pattern (`PartnerAggregateController`, `/api/partner/*`)
Uses a **`@LoadBalanced partnerRestClient`** against `https://<service-id>/...` URLs (service-ids `catalog-resource`, `order-resource`) — fix #1 for the scheme leak: keep scheme `https` (not `lb://`) so `computeScheme` doesn't choke. The LB rewrites host/port to a chosen instance.

## Client-side load balancing (`lb/PartnerLoadBalancerConfiguration`)
- Static registry: `spring.cloud.discovery.client.simple.instances.{catalog-resource,order-resource}` → two HTTPS instances each (18081/18082, 18091/18092).
- Per-client LB config imported via `@LoadBalancerClients(defaultConfiguration=...)`; the class has **no** `@Configuration`/`@Component` stereotype (would leak into the main scan).
- Health checks: the probe **path** now lives in `creed.partner.clusters.<name>.health-check.path` (`PartnerLoadBalancerConfiguration` overrides the framework path with the per-cluster value); only the scheduling (`initial-delay`, `interval: 10s`) stays under `spring.cloud.loadbalancer.health-check`.
- **Health-probe logging**: instead of `.withBlockingHealthChecks(restClient)` (which swallows errors in `catch (Exception ignored)`), it plugs in `LoggingHealthCheckServiceInstanceListSupplier extends HealthCheckServiceInstanceListSupplier` via `.with(...)`, overriding `protected Mono<Boolean> isAlive(ServiceInstance)` to log the full `ServiceInstance` + verdict, and a custom alive-fn (`loggingAliveFunction`) that logs HTTP status/latency and the otherwise-ignored exception. See [[creed-platform]] for the general pattern.

## Clusters are declarative — one YAML entry, a whole bean set (`web/PartnerClusterProperties` + `web/ClusterClientBeanRegistrar`)

Each entry under `creed.partner.clusters.<name>` describes one downstream: `service-id`, `client-bundle`, business `path`, pool tunables (`http.*`), and `health-check.path` + its own (smaller) pool. Records bind with `@DefaultValue`, so a sparse entry still yields sane pools — normally only `service-id`, `path` and `health-check.path` are supplied.

`ClusterClientBeanRegistrar` is a **`BeanDefinitionRegistryPostProcessor`** that registers, per cluster `<name>`, real Spring beans:

| Bean | Role |
|---|---|
| `<name>HttpConnectionManager` | business pool, `destroyMethod="close"` |
| `<name>ClientHttpRequestFactory` | buffered — the audit interceptor re-reads the body |
| `<name>RestClient` | **clones the `@LoadBalanced` builder** (keeping the LB interceptor) then appends the audit interceptor |
| `<name>PoolMetrics` | Micrometer `MeterBinder`; the cluster name becomes the `httpclient` tag |
| health-check pool + `RestClient` | separate, so probes don't pollute business pool stats |

**Adding a downstream cluster is a YAML change, not four `@Bean` methods.** Because the registrar runs before normal `@ConfigurationProperties` binding, it binds `creed.partner` with a manual `Binder`; a name typo therefore surfaces as a *missing bean* at startup, not a binding error.

> `creed-simple-metrics` later factored the same idea into a reusable `ManagedHttpClientPool` (one `@Bean` that is both `Closeable` and `MeterBinder`). This module still registers the trio separately — converging them is an open item.

## Interceptor ordering (`web/LoadBalancerAuditInterceptor`)
`ClientHttpRequestInterceptor`s run in **List insertion order, not `@Order`** (onion model, first = outermost). The `@LoadBalanced` post-processor **appends** the LB interceptor. Add yours in the `@Bean RestClient.Builder` to sit outer than LB; add after post-processing (in the build step) to sit inner (sees resolved `host:port`). Full writeup in the module README and [[creed-platform]].

## SSL identities (mTLS)
- Inbound: bundle `creed-partner-server` (alias `creed-gateway-partner`, serverAuth).
- Outbound (downstream + health checks): bundle `creed-partner-client` via `creed.partner.client-bundle` (alias `creed-gateway-partner-cli`, clientAuth `-CLI` cert).
- Bundles use `file:${creed.rootPath}` → run with `-Dspring-boot.run.workingDirectory="$PWD"`.