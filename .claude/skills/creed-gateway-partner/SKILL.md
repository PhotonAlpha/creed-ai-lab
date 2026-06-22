---
name: creed-gateway-partner
description: The creed-gateway-partner module — the SERVLET edge gateway on HTTPS 8095, aggregating downstream resource servers via a @LoadBalanced RestClient with client-side LoadBalancer + health checks, two isolated Apache HttpClient 5 pools (business vs health-check), an audit interceptor, and mTLS. Use when working on the load balancer config/health-check logging, the dual connection pools, interceptor ordering, or partner aggregation.
---

# creed-gateway-partner

The **servlet** edge gateway (Spring MVC, embedded Tomcat). HTTPS `8095`, no context-path. Servlet counterpart of the reactive [[creed-gateway]]. Profile `actuator` is `include`d (OTel/metrics — see [[creed-platform]] Observability). See [[creed-platform]] for SSL bundles, the `lb://` scheme leak, HttpClient 5 wiring, and LB health checks. Deep design notes: `creed-gateway-partner/README.md`.

## Aggregation pattern (`PartnerAggregateController`, `/api/partner/*`)
Uses a **`@LoadBalanced partnerRestClient`** against `https://<service-id>/...` URLs (service-ids `catalog-resource`, `order-resource`) — fix #1 for the scheme leak: keep scheme `https` (not `lb://`) so `computeScheme` doesn't choke. The LB rewrites host/port to a chosen instance.

## Client-side load balancing (`lb/PartnerLoadBalancerConfiguration`)
- Static registry: `spring.cloud.discovery.client.simple.instances.{catalog-resource,order-resource}` → two HTTPS instances each (8081/8082, 8091/8092).
- Per-client LB config imported via `@LoadBalancerClients(defaultConfiguration=...)`; the class has **no** `@Configuration`/`@Component` stereotype (would leak into the main scan).
- Health checks on `spring.cloud.loadbalancer.health-check.path.{catalog-resource:/api/catalog/ping, order-resource:/api/order/ping}`, `interval: 10s`.
- **Health-probe logging**: instead of `.withBlockingHealthChecks(restClient)` (which swallows errors in `catch (Exception ignored)`), it plugs in `LoggingHealthCheckServiceInstanceListSupplier extends HealthCheckServiceInstanceListSupplier` via `.with(...)`, overriding `protected Mono<Boolean> isAlive(ServiceInstance)` to log the full `ServiceInstance` + verdict, and a custom alive-fn (`loggingAliveFunction`) that logs HTTP status/latency and the otherwise-ignored exception. See [[creed-platform]] for the general pattern.

## Two isolated HttpClient 5 pools (`web/PartnerClientConfiguration`)
Business and health-check traffic are fully separated so probes don't pollute business pool stats:

| Pool bean | Purpose | RestClient | metric tag `httpclient=` | max-total |
|---|---|---|---|---|
| `aggregateHttpConnectionManager` | business aggregation (audited) | `partnerRestClient` (`@LoadBalanced`) | `creed-partner-aggregate` | 50 |
| `healthCheckHttpConnectionManager` | LB health checks | `healthCheckRestClient` | `creed-partner-health` | 10 |

- Tunables: `creed.partner.http.*` (aggregate) and `creed.partner.health-check.http.*` (shorter timeouts, smaller pool). Inject by name with `@Qualifier` to avoid `NoUniqueBean`.
- Each pool exposes metrics via Micrometer `PoolingHttpClientConnectionManagerMetricsBinder` (pool name = `httpclient` tag); only the aggregate factory wraps in `BufferingClientHttpRequestFactory` (audit re-reads the body).

## Interceptor ordering (`web/LoadBalancerAuditInterceptor`)
`ClientHttpRequestInterceptor`s run in **List insertion order, not `@Order`** (onion model, first = outermost). The `@LoadBalanced` post-processor **appends** the LB interceptor. Add yours in the `@Bean RestClient.Builder` to sit outer than LB; add after post-processing (in the build step) to sit inner (sees resolved `host:port`). Full writeup in the module README and [[creed-platform]].

## SSL identities (mTLS)
- Inbound: bundle `creed-partner-server` (alias `creed-gateway-partner`, serverAuth).
- Outbound (downstream + health checks): bundle `creed-partner-client` via `creed.partner.client-bundle` (alias `creed-gateway-partner-cli`, clientAuth `-CLI` cert).
- Bundles use `file:${creed.rootPath}` → run with `-Dspring-boot.run.workingDirectory="$PWD"`.