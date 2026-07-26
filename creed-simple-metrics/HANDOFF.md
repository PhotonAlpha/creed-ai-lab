# creed-simple-metrics — handoff

**Purpose** Camel-on-Spring-Boot servlet gateway aggregating the catalog / order / payment resource
servers.
**Skill** `creed-simple-metrics` · shared: `creed-platform` · deep notes: `README.md`,
`docs/camel-http-loadbalancer.md`, `docs/camel-observation-baggage-loss.md`

## Run

```bash
mvn -pl creed-simple-metrics spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"
curl -k https://localhost:8096/camel/api/...
```

HTTPS `8096`, Camel REST under `/camel/*`. Needs the downstream resource servers up to aggregate.

## Current state

- **Routes live in `src/main/resources/camel-context.xml`** — the classic `<camelContext>` Spring XML
  DSL, loaded via `@ImportResource`, not the modern IO DSL.
- **Two coexisting downstream-call patterns**: camel-http + a custom `LoadBalancerRoutePlanner`
  (`fetch-catalog` / `fetch-order` / `fetch-payment`), and a `@LoadBalanced RestClient`
  (`RemoteClusterProcessor`, used by the `fulfillment` bulk fetches). Both resolve
  `https://<service-id>/...` against one shared `SimpleDiscoveryClient` registry.
- **Cookie-based sticky routing for `payment-resource`** only: `stickyId` cookie →
  `StickyContextHolder` → an instance-list supplier that filters on `metadata.stickyId`.
- **Runtime-toggleable health checks**: `GET/PUT /admin/lb/health-check`.
- HTTP-client plumbing is factored into `lb/ManagedHttpClientPool` — one `@Bean(destroyMethod="close")`
  that is both `Closeable` and a `MeterBinder`, replacing the pool + factory + binder trio.
- Metrics are **pull-mode**: this module exposes `/actuator/prometheus` itself and Prometheus scrapes
  it directly, unlike every other module (which pushes via OTLP).

## Landmines

- **`restConfiguration inlineRoutes="false"` is required.** The default `true` merges each REST
  route's `<to uri="direct:x"/>` into the same-named `direct:x` route and eats any other consumer —
  which breaks the multicast branches that reuse `direct:catalog`.
- **`spring.main.allow-circular-references: true` is required** — the inline `<threadPool>` beans
  cycle with camel-spring-boot's health-check registry auto-config.
- **`StickyContextHolder` must always be overwritten, including with `null`.** Route threads are
  pooled, so a stale value leaks into the next request.
- **Turning health checks OFF must `destroy()` the inner supplier** — its `afterPropertiesSet()` holds
  a permanent subscription that keeps the probe loop running regardless of traffic. `choose()` only
  sees a flip after the LB cache TTL (35 s default).
- **`camel-observation-starter` was removed deliberately.** It broke local (non-header) baggage
  propagation because it looks up the parent span through its own mechanism rather than Brave's
  ambient context. The documented cost is losing route-level Prometheus timers. Do not add it back
  without reading `docs/camel-observation-baggage-loss.md`.
- **Prometheus must scrape this module directly** with its own job: `scheme: https`,
  `tls_config.insecure_skip_verify: true`, target `host.docker.internal:8096`, path
  `/actuator/prometheus` (**not** under the `/camel/*` context-path). After editing
  `monitoring/prometheus.yml` you must **restart** Prometheus — the container has no
  `--web.enable-lifecycle`, so `/-/reload` is unavailable.

## Open items

- Route-level Prometheus timers are gone with `camel-observation-starter` — an accepted, documented
  tradeoff, not a bug, but still an observability gap if anyone wants per-route latency.
- The static discovery registry here duplicates the ones in `creed-gateway-partner` and
  `creed-config-server`; three copies will drift.
