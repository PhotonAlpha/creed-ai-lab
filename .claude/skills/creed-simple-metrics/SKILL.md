---
name: creed-simple-metrics
description: The creed-simple-metrics module — a Camel-on-Spring-Boot servlet gateway on HTTPS 8096 (context-path /camel/*), routes defined in the classic <camelContext> Spring XML DSL, aggregating creed-resource-catalog/order/payment via camel-http + a custom Spring Cloud LoadBalancer route planner (plus a @LoadBalanced RestClient path for bulk/legacy calls), cookie-based sticky routing for payment, a three-layer audit/timing/observability design, and local-baggage (non-header) trace correlation. Use when working on camel-context.xml routes, the LB route planner / sticky LB, the audit-and-timing layers, or MDC/baggage tracing in this module.
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
  `ServiceCall` EIP. Full design: `docs/camel-http-loadbalancer.md`.
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
lifts it into `StickyContextHolder` (a plain `ThreadLocal` — camel-http's producer and
`LoadBalancerRoutePlanner.choose()` run synchronously on the same route thread, so it's visible;
**always overwrite**, including `null`, since route threads are pooled). `payment-resource` alone gets
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
  (`CamelConfig.producerTemplate` wraps the pool with `ContextExecutorService` + a custom
  `StickyContextThreadLocalAccessor` so `StickyContextHolder` rides along too). Full mechanism, the
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

## SSL / mTLS

Same two-bundle pattern as `creed-gateway-partner` (inbound `creed-partner-server` strict/fail-fast,
outbound `creed-partner-client` tolerant/degrades-to-plain-HTTP), registered **programmatically** in
`SslBundleConfiguration` rather than `spring.ssl.bundle.jks.*` — forces keystore/truststore load and key
recovery at startup instead of on first handshake. See [[creed-platform]] for the shared PKI/bundle
conventions and the `TomcatHttpsConfiguration` pattern (present here too).

## Spring Boot 4 note

Runs on Camel **4.22.0** — the classic `<camelContext>` Spring XML DSL still works (31 routes, 11 rest-dsl, verified starting on Boot 4). Camel's BOM manages no `org.springframework.*` artifact, so Boot's BOM wins on Spring versions.

Two module-specific Boot 4 requirements:
- **Logbook 4.1.0** (was 3.9.0). Logbook 3.x auto-configuration injects a Jackson **2** `com.fasterxml.jackson.databind.ObjectMapper` bean, which Boot 4 no longer defines — the context fails at startup. The 4.x line targets Boot 4 and Jackson 3.
- **`spring-boot-micrometer-tracing`** must be declared: Boot 4's actuator starter no longer brings tracing auto-configuration, and this module injects `io.micrometer.tracing.Tracer` (`TracingFilter`, `SpanTaskDecorator`, both thread-pool configs).

`ContentCachingRequestWrapper` lost its single-argument constructor in Spring Framework 7; a cache limit is now mandatory.

## hc5 pool metrics / observation deprecation

`CamelConfig`'s pool gauges now come from **`com.creed.metrics.ConnectionPoolMetrics`** (creed-common-metrics), not Micrometer's `PoolingHttpClientConnectionManagerMetricsBinder` — the whole `io.micrometer…httpcomponents.hc5` package is deprecated from Micrometer 1.17 / Boot 4.1, and Apache's replacement emits different meters and only binds via an `HttpClientBuilder`. See [[creed-platform]] for the full reasoning; meter names and tags are unchanged, so the dashboards were not touched.

The request observation is migrated too: `ObservationExecChainHandler` → **`new ObservationClassicExecInterceptor(observationRegistry, ObservingOptions.DEFAULT)`** from `httpclient5-observation` (needs `httpclient5` 5.6.4, pinned in the root pom).

**Constructed directly, deliberately not via `HttpClientObservationSupport.enable(builder, …)`.** That helper installs everything with `addExecInterceptorFirst`, which would break this module's exec chain in two ways: it moves the observation *outside* RETRY — silently changing it from per-attempt to per-operation timing, and decoupling it from what `CamelLoadBalancerAuditExecHandler` logs per attempt — and it also binds its own `ConnPoolMeters`, duplicating pool gauges under Apache names alongside the `ConnectionPoolMetrics` ones the dashboards use. Constructing the interceptor keeps the documented ordering: Logbook outermost, observation right inside RETRY, `lbAudit` innermost.

**Meter/span rename**: the Observation is now `http.client.request` (was `httpcomponents.httpclient.request`), so Prometheus shows `http_client_request_seconds*`. Nothing in `monitoring/` queried the old name — checked before the change. Do not confuse it with Spring's RestClient `http.client.requests` (plural), which this module also emits.

Verified end to end against a live `creed-resource-catalog`: `http_client_request_seconds_count{error="none",http_method="GET",http_status_code="200",net_peer_name="catalog-resource"}`, with pool gauges unduplicated and the chain order intact.
