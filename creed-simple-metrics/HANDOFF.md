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
- **Cookie-based sticky routing for `payment-resource`** only: the `stickyId` cookie rides the
  outgoing request → `LoadBalancerRoutePlanner`'s three-arg `determineRoute` turns it into a
  `RequestDataContext` → an instance-list supplier that filters on `metadata.stickyId`.
- **Runtime-toggleable health checks**: `GET/PUT /admin/lb/health-check`.
- **mod_cluster node registration** (`modcluster/`, `creed.mod-cluster.*`, OFF by default): the
  upstream `mod_cluster-container-tomcat-10.1` listener (the library JWS 6.x ships and wires with
  `<Listener>` in `server.xml`) attached to the embedded Tomcat `Server`. The library owns the MCMP
  handshake, dynamic load metrics, context discovery, re-registration and shutdown removal;
  `ModClusterListenerStatusReporter` adds the one thing it does not report — a startup banner saying
  whether each proxy actually holds this node (MCMP `INFO` → look for `Name: <JVMRoute>`), with
  optional `fail-fast`. Design + httpd config + gotchas: `docs/mod-cluster-registration.md`.
- Metrics are **pull-mode**: this module exposes `/actuator/prometheus` itself and Prometheus scrapes
  it directly, unlike every other module (which pushes via OTLP).

## Landmines

- **`restConfiguration inlineRoutes="false"` is required.** The default `true` merges each REST
  route's `<to uri="direct:x"/>` into the same-named `direct:x` route and eats any other consumer —
  which breaks the multicast branches that reuse `direct:catalog`.
- **`spring.main.allow-circular-references: true` is required** — the inline `<threadPool>` beans
  cycle with camel-spring-boot's health-check registry auto-config.
- **The camel-http path is `choose(...)`, not `execute(...)`** — so no `LoadBalancerLifecycle`
  callbacks (no `loadbalancer.requests.*` metrics), no `spring.cloud.loadbalancer.hint.*`, no
  instance failover on retry, and the `Host` header keeps the logical service name. Full comparison
  against `LoadBalancerInterceptor` and the risk list:
  `docs/camel-http-loadbalancer.md` → 「与 `LoadBalancerInterceptor` 的区别与风险」.
- **`fetch-payment` deliberately does NOT set `skipRequestHeaders=true`** (unlike `fetch-catalog` /
  `fetch-order`): the sticky cookie has to reach the outgoing HTTP request, since that request is what
  the route planner hands to the load balancer. `<removeHeaders pattern="*" excludePattern="Cookie"/>`
  plus the processor's narrowing of the Cookie header is the equivalent whitelist.
- **Turning health checks OFF must `destroy()` the inner supplier** — its `afterPropertiesSet()` holds
  a permanent subscription that keeps the probe loop running regardless of traffic. `choose()` only
  sees a flip after the LB cache TTL (35 s default).
- **`camel-observation-starter` was removed deliberately.** It broke local (non-header) baggage
  propagation because it looks up the parent span through its own mechanism rather than Brave's
  ambient context. The documented cost is losing route-level Prometheus timers. Do not add it back
  without reading `docs/camel-observation-baggage-loss.md`.
- **mod_cluster: the listener must go on the `Server`, before it initialises.**
  `TomcatEventHandlerAdapter` only reacts to `Server`-sourced lifecycle events — on a Context or Host it
  silently registers nothing. A `TomcatContextCustomizer` is the right window in Boot (parent chain
  wired, server not yet started). `JVMRoute` comes from the Engine, and `STATUS` cadence is
  `Engine.backgroundProcessorDelay × status-frequency`, not a timer of ours.
- **mod_cluster: `setProxyList(String)` resolves DNS at bean-creation time and throws** — from a
  `@Bean` method that is a failed context, contradicting `fail-fast: false`. The proxies are parsed into
  `InetSocketAddress`es by hand, unresolvable ones logged and skipped.
- **mod_cluster registers Tomcat's ROOT context, not `/camel`** (`/camel/*` is only the Camel servlet
  mapping) — httpd-side routing must be configured for `/`. And registration success is not visible in
  the library's own logs: the banner's verdict comes from an MCMP `INFO` per proxy.
- **Prometheus must scrape this module directly** with its own job: `scheme: https`,
  `tls_config.insecure_skip_verify: true`, target `host.docker.internal:8096`, path
  `/actuator/prometheus` (**not** under the `/camel/*` context-path). After editing
  `monitoring/prometheus.yml` you must **restart** Prometheus — the container has no
  `--web.enable-lifecycle`, so `/-/reload` is unavailable.

## Open items

- Route-level Prometheus timers are gone with `camel-observation-starter` — an accepted, documented
  tradeoff, not a bug, but still an observability gap if anyone wants per-route latency.
- `RestClientSuppliersTest.connectionManagerWithBundleInstallsBundleTlsMaterial` and
  `LoadBalancedRestClientConfigurationTest.clusterPoolUsesTheResolvedMtlsBundle` fail on `master-spring-boot-3`
  (pre-existing): they stub `SslBundle.createSslContext()`, but `RestClientSuppliers` now builds the
  context from `getStores()`/`getKey()` — the mock returns `null` for `getKey()`.
- The static discovery registry here duplicates the ones in `creed-gateway-partner` and
  `creed-config-server`; three copies will drift.
