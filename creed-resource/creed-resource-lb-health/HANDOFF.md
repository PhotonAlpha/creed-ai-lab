# creed-resource-lb-health — HANDOFF

## State (2026-11-16)

Lab module, created to verify that Spring Cloud LoadBalancer's health check never hands out a partial instance list
(`update-results-list=false`) while results keep updating (`interval < refetch-instances-interval`, worst-case round
< refetch) and the first request does not wait for round one (`eager-load`, `initial-delay: 0`). Constraints and their
reasons are in `README.md`.

- Port 18099 (HTTP), context path `/api/lb-health`. Target service `lb-health-target`: 10 instances on
  18100-18109, served by this app's own extra Tomcat connectors (`creed.lb-health.target.embedded`).
- Probe mode `blocking` (default, `withBlockingHealthChecks(RestTemplate)`) or `webclient` (`withHealthChecks(WebClient)`).
- Debug logs (`[REFETCH]`, `[HC]`, `[HC-STREAM]`, `[HC-ROUND]`, `[HC-RESULT]`) behind `creed.lb-health.debug.enabled` / `CREED_LB_DEBUG`.
- Start-up WARNs `[LB-HEALTH-CONFIG]` for every violated rule, against the effective per-client properties.
- 18 tests: virtual-time StepVerifier tests of Spring's own supplier, validator unit tests, and an end-to-end
  `@SpringBootTest` that probes real loopback connectors (no Docker / PKI / issuer).

## Probe request/response log (2026-11-17, asked for)

Logbook 4.2.0 on the two probe clients only (`HealthCheckClients`: `LogbookClientHttpRequestInterceptor` on the
RestTemplate, after the `[HC]` debug interceptor; `LogbookExchangeFilterFunction` on the WebClient), so it logs in both
modes, debug or not. `logbook.format.style: splunk` - one key=value line per request (`origin=local type=request
correlation=… method=GET uri=…`) and per response (`origin=remote type=response correlation=… duration=… status=…
body=…`), paired by `correlation`. A probe that gets no answer (refused, read timeout) logs only its request. The
starter's inbound servlet filter is off (`logbook.filter.enabled: false`): the targets are this app's own connectors,
so it would log every probe twice. Logger `org.zalando.logbook.Logbook` at TRACE; `CREED_LB_LOGBOOK_LEVEL=INFO` silences
it (attached but idle: Logbook skips a request when its writer is not at TRACE). `creed.lb-health.logbook.enabled=false`
(`CREED_LB_LOGBOOK`) does not attach it at all (`HealthCheckClients` takes `null`, `HealthCheckClientsTest`); the start-up
`[LB-HEALTH]` line says `logbook=true|false`. Tested by `LbHealthApplicationTest.everyProbeIsLoggedByLogbookInSplunkStyle` (a logback `ListAppender`, not
captured stdout - the console appender keeps the stream it had when the cached context started). The test class now
pins `initial-delay: 0` and `update-results-list: false` itself instead of relying on application.yml's defaults.

## Round trigger + MDC (2026-11-17, asked for)

Debug only (`ObservableHealthCheckServiceInstanceListSupplier`). `[HC-ROUND] start round=N trigger=REFETCH|INTERVAL`
when a round starts, the same `round=` / `trigger=` on `[HC-RESULT]`. Hooks: `healthCheckFlux` is switchMap's mapper
(once per refetched list → next round is REFETCH), the first `isAlive` of a round is its start (Spring calls it for
every instance before subscribing any probe); the first list at start-up counts as REFETCH. Every probe runs with MDC
`hcTrigger` / `hcRound`, printed by `logging.pattern.level` as ` trigger=… round=…` (dropped when empty), so Logbook's
lines carry it. Blocking: MDC set around the probe's subscription and removed after. WebClient: Logbook writes on the
JDK HttpClient's threads (measured: no MDC there otherwise), so the round also goes into the Reactor Context and
`HealthCheckMdc` registers the keys with `context-propagation` and turns on `Hooks.enableAutomaticContextPropagation()`
- JVM-global, only in debug + webclient. `[HC-RESULT]` itself is logged after the probe scope: its trigger is in the
message, not the MDC. Tests: `HealthCheckRoundTest.eachRoundSaysWhetherTheRefetchOrTheIntervalStartedIt` (0 s REFETCH,
35/70/105 s INTERVAL, 120 s REFETCH) and the MDC of both Logbook lines in `everyProbeIsLoggedByLogbookInSplunkStyle`.

## Circuit breakers + bulkheads, per cluster (2026-11-17, reworked 2026-11-18)

resilience4j 2.3.0 (Spring Cloud BOM; `spring-cloud-starter-circuitbreaker-resilience4j` → `resilience4j-spring-boot3`
binds `resilience4j.*` on Boot 4; `resilience4j-bulkhead` added for the `BulkheadRegistry`). The user's config is
`circuitbreaker.configs.default` (+ `bulkhead.configs.default`); `backendA` / `backendB` are gone.

- **One breaker + one bulkhead per cluster** (= key under `spring.cloud.discovery.client.simple.instances`), named after
  it, created in `DownstreamCaller.createGuards()` at start-up via `registry.circuitBreaker(cluster)` - which takes
  `configs.default` because Spring registers it as the registry default. `instances.<cluster>` overrides one cluster.
  An unknown name is a 400, never a new breaker.
- Nesting **`Bulkhead( CircuitBreaker( call ) )`** - deliberately NOT resilience4j's annotation order. Tried the
  annotation order on 2026-11-19 and reverted the same day (user's call): with the bulkhead inside, the 44 rejections of
  a 100-call burst were recorded as 44 SUCCESSES (the predicate's `false`), and the bulkhead wait counted in the call
  duration (a 60 s wait vs the 60 s slow-call threshold). Now pinned the other way:
  `withTheBulkheadOutsideTheBreakerARejectionIsNotInTheWindow` (window = only the calls that went out), and the 9 s
  queue of the bulkhead cluster is recorded as ~1 s. Doc §4.4 has both columns. The aspect order is inert here (no annotations).
- `DownstreamCircuitBreakerFailurePredicate`: failure = I/O, 5xx, 408, 429, "No instances available", anything unknown;
  `false` (= recorded as SUCCESS, not ignored - resilience4j's order is ignore → record → success) for other 4xx and
  our own rejections.
- Second cluster `lb-health-bulkhead` (3 instances 18110-18112, Spring's default LoadBalancer, bulkhead 10,
  `max-wait-duration 60s` since 2026-11-19 = a full bulkhead QUEUES on the caller's thread instead of rejecting); `TargetConnectorsConfiguration` now opens a connector for every cluster's localhost ports.
- `LoadRun` has `concurrent=true` (one virtual thread per call, open model; rows = calls that ENDED that second; keeps a
  row per second after sending until nothing waits or is in flight, max 120 s) plus `bulkheadFull` / `waiting` /
  `inFlight` columns. Risk R13 in the doc: a 60 s wait holds Tomcat threads. `/cb/*` takes `cluster=` (default `lb-health-target`), not `backend=`.
- **Actuator (2026-11-19)**: `spring-boot-starter-actuator`, exposed health / info / metrics / loggers / circuitbreakers /
  circuitbreakerevents / bulkheads / bulkheadevents under `/api/lb-health/actuator`. resilience4j 2.3.0's endpoints,
  events and Micrometer metrics work on Boot 4; its health indicator does NOT (implements Boot 3's
  `org.springframework.boot.actuate.health.HealthIndicator`) - `downstream/CircuitBreakersHealthIndicator` (bean
  `circuitBreakersHealthIndicator` → component `circuitBreakers`) replaces it with the same rules
  (`register-health-indicator`, DOWN only with `allow-health-indicator-to-fail`). Remove it when resilience4j ships a
  Boot 4 indicator, or there will be two. Spring Cloud's discovery health indicators are off
  (`spring.cloud.discovery.client.health-indicator.enabled: false`): with simple discovery they stay UNKNOWN forever.
- `bulkhead.configs.default.max-concurrent-calls` = 20 (was 100; 2 × 100 = Tomcat's 200 threads, risk R8).
- Inert key: `circuit-breaker-aspect-order` (no annotations).

Pinned: the bound values + predicate + per-cluster breakers/bulkheads, 50th failure opens an empty window, the other
cluster unaffected, automatic OPEN→HALF_OPEN, bulkhead peak 10 with 60 s queueing (40/s × 3 s at 1 s per call: all 120 succeed over 13 s,
90 waiting at 3 s, no slow calls - the wait is outside the breaker), rejection after max-wait -
`LbHealthApplicationTest`; scenario numbers (opens 6.95 s at 20/s / 24.80 s at 5/s, 150 downstream failures because the
15 s wait < 60 s interval sends trials to the stale list; 1 / 2 of 10 down = 10 % / 20 %, never opens, 118 / 236
failures; 5 down never, 6 down opens at 10.95 s and then also blocks the 4 healthy instances until 72.95 s; recovery first success 130.50 s, 120.00 s with "No instances
available" ignored) - `CircuitBreakerScenarioTest` (automatic transition mirrored as off: it runs on a real-time
scheduler). Write-up incl. every key, combinations and risks (R1: the business RestTemplate has NO timeout - a hung
cluster defeats the slow-call rule and can pin HALF_OPEN forever): `docs/resilience4j-circuit-breaker.md`.

Test port fix: `LbHealthApplicationTest.freePort()` binds on loopback - a wildcard `ServerSocket(0)` was handed ports
IntelliJ holds on 127.0.0.1 (50000-50002), and those "instances" answered 503 from the first round.

## Run

```bash
mvn -q -pl creed-resource/creed-resource-lb-health -am -DskipTests install
mvn -pl creed-resource/creed-resource-lb-health spring-boot:run \
  -Dspring-boot.run.workingDirectory="$PWD" -Dspring-boot.run.jvmArguments="-DCREED_LB_DEBUG=true"
curl -s localhost:18099/api/lb-health/lb/instances
curl -s -X PUT 'localhost:18099/api/lb-health/target/instances/18103?up=false'
```

## Open items

- Not applied to `creed-gateway-partner` / `creed-simple-metrics`, which still stack `withCaching()` on the health check
  (see `creed-simple-metrics/docs/lb-health-check-partial-list.md`). Porting means `update-results-list: false`,
  dropping the cache layer, and checking their own interval/refetch/timeout ratios.
- No Backstage catalog entry (no OpenAPI - it is a lab, not a service).
- `LbHealthController#instances` blocks up to 90 s on `get().next()`, which is what a real request would do before round one.
