---
name: creed-simple-metrics
description: The creed-simple-metrics module — a Camel-on-Spring-Boot servlet gateway on HTTPS 8096 (context-path /camel/*), routes defined in the classic <camelContext> Spring XML DSL, aggregating creed-resource-catalog/order/payment via camel-http + a custom Spring Cloud LoadBalancer route planner (plus a @LoadBalanced RestClient path for bulk/legacy calls), cookie-based sticky routing for payment, a three-layer audit/timing/observability design, local-baggage (non-header) trace correlation, and mod_cluster node registration with an Apache HTTP Server balancer via the upstream JBoss listener. Use when working on camel-context.xml routes, the LB route planner / sticky LB, the audit-and-timing layers, MDC/baggage tracing, or the mod_cluster registration in this module.
---

# creed-simple-metrics

Camel-on-Spring-Boot servlet gateway. HTTPS `8096`, Camel REST under `https://host:8096/camel/api/*`
(`camel.servlet.mapping.context-path=/camel/*`). See [[creed-platform]] for ports/mTLS/SSL-bundle
conventions shared across the mesh, and [[creed-resource-catalog]]/[[creed-resource-order]]/
[[creed-resource-payment]] for the three downstream resource servers it aggregates.

## Route DSL: classic `<camelContext>` Spring XML

All routes/rests/threadPools live in `src/main/resources/camel-context.xml`, loaded via
`@ImportResource`, **not** the modern `<routes>`/`<rests>` IO DSL. Dependency is
`camel-spring-xml`; `camel.main.routes-collector-enabled: false` (routes are inline, not
file-collected). `restConfiguration inlineRoutes="false"` (default `true` merges each REST route's
`<to uri="direct:x"/>` into the same-named `direct:x` route and eats any other consumer of it — breaks
multicast branches reusing `direct:catalog` etc). `spring.main.allow-circular-references: true` (the
inline `<threadPool>` beans cycle with camel-spring-boot's health-check registry auto-config). Full
gotcha writeup (including the three schema/attribute-name traps under Camel 4's unified route model):
`README.md`'s `<camelContext>` section.

## Two downstream-call patterns coexist

- **camel-http + `LoadBalancerRoutePlanner`** (`fetch-catalog`/`fetch-order`/`fetch-payment`): routes
  keep plain `https://<service-id>/...` endpoint URIs; `CamelConfig.httpComponent`'s
  `HttpClientConfigurer` installs the route planner, which resolves the service-id via
  `DiscoveryClient`/`LoadBalancerClient.choose()` at connect time — the replacement for the removed
  `ServiceCall` EIP. `choose(...)` is only half of what the `@LoadBalanced` path's `execute(...)` does,
  so this path has **no `LoadBalancerLifecycle` callbacks** (no `loadbalancer.requests.*` metrics), no
  `hint` properties, no failover on hc5 retry, and it keeps the logical name in the `Host` header
  (redirects/vhost routing/`server.address` all see the service-id). Full design plus the side-by-side
  comparison and risk list: `docs/camel-http-loadbalancer.md` → 「与 `LoadBalancerInterceptor` 的区别与风险」.
- **`@LoadBalanced RestClient`** (`RemoteClusterProcessor`, used by the `fulfillment` pipeline's bulk
  fetches): same `https://<service-id>` trick, resolved by the Spring Cloud LB interceptor.
  `LoadBalancerAuditInterceptor` (added after the LB post-processor, so it's innermost / sees the
  resolved `host:port`) logs the chosen instance — see [[creed-platform]] "RestClient interceptor
  ordering" for why insertion order (not `@Order`) controls this.

Both paths share one `SimpleDiscoveryClient` registry (`application.yml`
`spring.cloud.discovery.client.simple.instances.{catalog-resource,order-resource,payment-resource}`)
and one health-check chain (`PartnerLoadBalancerConfiguration`: discovery → logging health check
(overrides `isAlive` to log what Spring Cloud's stock probe swallows in `catch (Exception ignored)`) →
caching). The health-check layer is runtime-toggleable (`ToggleableHealthCheckServiceInstanceListSupplier`
+ main-context `HealthCheckToggle`, API `GET/PUT /admin/lb/health-check`, initial state
`creed.lb.health-check.enabled`): OFF must `destroy()` the inner supplier — its `afterPropertiesSet()`
holds a permanent `aliveInstancesReplay.subscribe()` that keeps the `replay(1).refCount(1)` probe loop
running regardless of traffic; `choose()` sees a flip only after the LB cache TTL (35s default). Design
notes: `docs/camel-http-loadbalancer.md` 运行时开关 section.

## Sticky routing for `payment-resource`

Request carries `Cookie: stickyId=<value>` → `PaymentStickyProcessor` (first step of `fetch-payment`)
narrows the `Cookie` header to that one cookie and lets it ride the outgoing request (so `fetch-payment`,
unlike `fetch-catalog`/`fetch-order`, must NOT use `skipRequestHeaders=true`; it uses
`<removeHeaders pattern="*" excludePattern="Cookie"/>` instead) → `LoadBalancerRoutePlanner` overrides
hc5's **three-arg** `determineRoute(target, request, context)` — the overload `InternalHttpClient`
actually calls — adapts that request into the same `RequestData`/`RequestDataContext` that
`BlockingLoadBalancerClient.execute(...)` builds, and calls `choose(serviceId, request)`. Selection is
therefore request-scoped, not thread-scoped: multicast branches and the async `ProducerTemplate` need no
context propagation. (Bare `choose(serviceId)` would pass `new DefaultRequest<>()`, whose no-arg
constructor discards its context — `getContext()` returns `null` and every `instanceof
RequestDataContext` supplier silently no-ops. Don't parse cookies with Spring's
`RequestData(HttpRequest)` either: it splits the whole Cookie header on `=` and sees only the first
cookie.) `payment-resource` alone gets
`PaymentStickyLoadBalancerConfiguration` (via `@LoadBalancerClient(name="payment-resource")`; every
other service keeps `PartnerLoadBalancerConfiguration` as `defaultConfiguration`); it stacks
`StickyMetadataServiceInstanceListSupplier` **outside the cache** on the same
discovery→health-check→cache chain, filtering the alive list by `metadata.stickyId` (registered per
instance in `application.yml`). Contract: no cookie → full alive list; match → pinned instance(s); no
match (unknown id, or the pinned instance just failed health check) → WARN + fall back to the full alive
list (availability beats stickiness).

## Three-layer audit/timing design

See `docs/camel-audit-observability.md` for the full writeup (Zalando Logbook config, three real
pitfalls, hc5 entity-is-a-one-shot-stream lesson). Summary:

| Layer | Mechanism | Class | Output |
|---|---|---|---|
| REST request overall | `SynchronizationAdapter` + `RoutePolicyFactory` | `RestApiAuditRoutePolicyFactory`/`RestApiAuditSynchronization` — attaches once per inbound `servlet:` exchange (idempotent via exchange property), captures method/URI *before* `fetch-*` routes strip `CamelHttp*` headers | `CAMEL-AUDIT GET /camel/api/x status=200 in 27ms` |
| Every send (`<to>`, multicast/wireTap branch, ProducerTemplate) | `EventNotifier` on `ExchangeSentEvent` | `CamelSendTimingEventNotifier` — skips `direct:` (in-JVM glue, already counted in the outer send) | `*-metrics.log` single-line `camel-send endpoint=... timeMs=...` |
| Every HTTP network round-trip | hc5 `ExecChainHandler` ×3 on `CamelConfig.httpComponent` | `LogbookHttpExecHandler` (full audit, outermost) → `ObservationExecChainHandler` (Micrometer timer + trace propagation) → `CamelLoadBalancerAuditExecHandler` (innermost — logs the LB-resolved instance, the one thing Logbook/Observation can't see) | Logbook block + `httpcomponents.httpclient.request` + `LB resolved -> instance=...` |

Both `EventNotifier`/`RoutePolicyFactory` need no manual wiring — the classic `<camelContext>` factory
bean auto-discovers every such bean in the Spring registry.

### Logbook production tuning (`web/LogbookAuditConfiguration` + `ContentAwareBodyStrategy`)

The shared Logbook instance (inbound servlet filter + both hc5 exec handlers) is tuned for production via
this module's own `creed.logbook.*` props, because Logbook's native property surface can't express any of
it (its `logbook.predicate.*` only has path/method fields). Two extension beans hook Logbook's
`@ConditionalOnMissingBean` points:
- `requestCondition` (a `Predicate<HttpRequest>` — must keep that exact bean name) is the top-level
  `Logbook.condition()`: `creed.logbook.skip-paths` (Ant patterns) + `creed.logbook.allowed-content-types`
  (default `application/json`) gate the **request** before any body buffering — a non-matching request
  drops the whole audit.
- `logbookStrategy` (`ContentAwareBodyStrategy`) gates the **response body** in `process(request,response)`
  — the only place the response content-type is visible (`condition` runs pre-request). Same
  `allowed-content-types` list, but a non-matching response only drops its body (metadata line stays,
  since the request was already emitted). Optional `creed.logbook.body-on-error.*` adds a status gate; the
  decision is in `process(...)` so it's a genuine buffering skip, not a suppressed log line (unlike every
  built-in Logbook strategy, which only overrides `write()` after both bodies are already buffered).

Output goes to a dedicated `${appName}-logbook.log` behind an `AsyncAppender` (`neverBlock=true`,
`discardingThreshold=0`) so audit I/O never blocks the synchronous hc5 call thread. Full knob list,
verification steps, and the request-vs-response asymmetry rationale: `docs/logbook-production-tuning.md`.

## Tracing: local baggage, not header propagation — and its camel-observation trap

`correlationTraceId` (`MyMDCScopeDecorator.CORRELATION_FIELD`, set by `TracingFilter` on every inbound
request) is a Brave `SingleBaggageField.local` — it lives only in the current `TraceContext.extra`, never
rides an HTTP header. This is deliberate (curl with no propagation headers still gets a real, connected
trace id), but it means the value only survives where something correctly threads Brave's ambient
`CurrentTraceContext` — **not** wherever a library builds its own parent-span lookup.

Two real breaks from this, both documented in depth:

- **`ProducerTemplate.asyncRequestBody*`** (pool threads): fixed via a context-propagating executor
  (`CamelConfig.producerTemplate` wraps the pool with `ContextExecutorService`). Full mechanism, the
  "looked connected but wasn't" false-positive from the old remote-baggage era, and a pitfall checklist:
  `docs/camel-producertemplate-context-propagation.md`.
- **`camel-observation-starter`'s producer/CLIENT span** (every `<to>` endpoint call, even a single
  synchronous hop — not just async EIPs): its span-parent lookup goes through Camel's own
  `ActiveSpanManager`/`ObservationRegistry.getCurrentObservation()`, not Brave's `CurrentTraceContext`,
  so the new span's context doesn't reliably carry the baggage extra. **Fixed by removing the
  dependency entirely** (not `camel.observation.exclude-patterns` — none of the three audit layers
  above ever needed it). Costs the route/processor-level Prometheus timers it was providing as a side
  effect (`fulfillment_seconds` etc. — gone; a deliberate, documented tradeoff). Full investigation
  (including the bracketing-diagnostic + A/B methodology worth reusing for this whole class of bug):
  `docs/camel-observation-baggage-loss.md`.

`MyMDCScopeDecorator.MDCContext.getValue()` has a fallback (read stale MDC when baggage is blank) that
exists to make a broken-old-remote-era log line *look* connected — useful to know when it's masking a
real break; don't trust `MDC.get("traceId")` alone when debugging this class of issue, read
`CORRELATION_FIELD.getValue()` directly.

## Fulfillment pipeline (`POST /api/fulfillment`)

Multi-stage orchestration example: (0) `FulfillmentRequestProcessor` lifts optional
`{"failCatalog":bool,"failOrder":bool}` fault-injection flags from the body into headers → (1)
`multicast parallelProcessing="true"` (pool `aggregatePoolA`) bulk-fetches catalog + order via
`RemoteClusterProcessor` (`resilient=true` captures downstream error status into
`branchError`/`branchStatus` instead of throwing; `?fail=` query param triggers it) → (2) any branch
error short-circuits to `FailureResponseProcessor`; otherwise `FulfillmentFilterProcessor` keeps only
orders with status∈{NEW,PAID} *and* sufficient catalog stock → (3) a second `multicast` enriches via
internal `direct:` routers (`fulfillmentEnricher` bean, no downstream HTTP) → (4) success wireTaps
`direct:fulfillment-notification` fire-and-forget on pool `notificationPoolB`.

## Cookie-header corruption repro (`POST /camel/api/cookie-relay?cluster=order|catalog`)

`CookieRelayProcessor` reproduces a real prod bug end-to-end against a live downstream: `HttpCookie.parse()`
guesses cookie **version 1** for a `Max-Age`-without-`Expires` `Set-Cookie` (RFC 2965), and
`HttpCookie.toString()` on a version-1 cookie serializes as `name="value";$Path="/";$Domain="..."` — the
buggy `Cookie` header. Compares that against the fix (plain `name=value` join) by forwarding both to the
same downstream `/echo` and showing what the server-side actually parsed. Uses a dedicated
`cookieRelayRestClient` (`CookieRelayRestClientConfiguration`) with cookie management disabled — the
business client would silently absorb/mask the bug.

## mod_cluster node registration (`modcluster/`, `creed.mod-cluster.*`, OFF by default)

Registers this process as a node of an Apache HTTP Server `mod_cluster` balancer using the **upstream
container integration** `org.jboss.mod_cluster:mod_cluster-container-tomcat-10.1` — the library JBoss
Web Server 6.x ships in `$JWS_HOME/tomcat/lib` and wires with `<Listener
className="org.jboss.modcluster.container.tomcat.ModClusterListener" .../>` in `server.xml`. There is no
`server.xml` here, so `ModClusterListenerConfiguration` does that element's three jobs in code. The
library owns the protocol (MCMP `CONFIG`→`ENABLE-APP`→`STATUS`, dynamic `LoadMetric` load factor,
context discovery, re-registration, session draining, shutdown removal); this module only adds the
verdict. Full design, httpd config and verification recipe: `docs/mod-cluster-registration.md`.

- **The listener must be added to the `Server`, before the Server initialises.**
  `TomcatEventHandlerAdapter` only handles `Server`-sourced `AFTER_INIT`/`START`/`AFTER_START`/
  `BEFORE_STOP`/`STOP`; on a Context or Host it receives nothing and registers nothing, silently. In
  Boot the window is a `TomcatContextCustomizer` (the Context→Host→Engine→Service→Server chain is wired
  by then, and `tomcat.start()` has not run).
- **`JVMRoute` comes from `Engine.getJvmRoute()`** (else a generated UUID), so the customizer sets it —
  `<app-name>-<port>` by default. **`STATUS` cadence is
  `Engine.backgroundProcessorDelay × org.jboss.modcluster.container.catalina.status-frequency`**
  (system property, default 1), so `status-interval` maps onto the Engine delay, not a timer of ours.
- **`setProxyList(String)` resolves DNS at bean-creation time and throws** — from a `@Bean` method that
  is a failed context, contradicting `fail-fast: false`. `parseProxies` builds `InetSocketAddress`es by
  hand and skips (loudly) the ones that cannot resolve.
- **The registered context is Tomcat's ROOT `/`, not `/camel`** — contexts come from what the container
  has deployed; `/camel/*` is only the `CamelHttpTransportServlet` mapping. `excluded-contexts` is the
  only lever. Likewise the sticky-session cookie/path come from Tomcat's session config, not from
  `creed.mod-cluster.balancer.*`.
- **Registration success is not in the library's logs** (MCMP traffic is DEBUG; refused and unreachable
  look alike). `ModClusterListenerStatusReporter` asks each proxy with MCMP `INFO`
  (`ModClusterServiceMBean#getProxyInfo()`) and looks for `Name: <JVMRoute>`; it polls up to
  `startup-timeout` because registration happens asynchronously on the Server's `AFTER_START`. The
  banner's **log level is the verdict** (INFO all / WARN partial / ERROR none, plus one INFO line when
  disabled), and `fail-fast` turns "none" into a startup failure.
- **The banner prints the address the proxy reports, not the locally computed one** — mod_cluster picks
  the advertised address from the Tomcat connector, and the two really do differ on a multi-homed host
  (measured: local `192.168.5.9` vs advertised `127.0.0.1`). `node.host`/`node.port` only override what
  is published (`externalConnector*`), they do not select the connector.

## SSL / mTLS

Same two-bundle pattern as `creed-gateway-partner` (inbound `creed-partner-server` strict/fail-fast,
outbound `creed-partner-client` tolerant/degrades-to-plain-HTTP), registered **programmatically** in
`SslBundleConfiguration` rather than `spring.ssl.bundle.jks.*` — forces keystore/truststore load and key
recovery at startup instead of on first handshake. See [[creed-platform]] for the shared PKI/bundle
conventions and the `TomcatHttpsConfiguration` pattern (present here too).