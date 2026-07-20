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
caching).

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