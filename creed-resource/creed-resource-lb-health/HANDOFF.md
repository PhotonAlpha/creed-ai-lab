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

## Circuit breakers (2026-11-17, asked for)

resilience4j 2.3.0 (Spring Cloud BOM, `spring-cloud-starter-circuitbreaker-resilience4j` → `resilience4j-spring-boot3`
binds `resilience4j.circuitbreaker.instances.*` on Boot 4) around the `@LoadBalanced` RestTemplate: `downstream/DownstreamCaller`
(breaker taken from the registry, never created; not Spring Cloud's `CircuitBreakerFactory`, whose 1 s TimeLimiter would
change the calls), `downstream/LoadRun` (rps x seconds, per-second `[LOAD]` rows), `api/CircuitBreakerController` (`/cb/*`),
`[CB]` state-transition log. `backendA` / `backendB` as asked. `/target/whoami` now follows the instance state (503 when
down) - before, only `/health` failed and the breaker saw nothing. `registerHealthIndicator` has no effect (no actuator;
the 2.3.0 indicator targets Boot 3's health package).

Facts the doc rests on, pinned: the Spring binding uses separate setters, so `minimumNumberOfCalls` stays 100 for both
(never capped to the window; TIME_BASED 10 s ⇒ ≥ 10 calls/s or it never opens) - `LbHealthApplicationTest`;
the scenario numbers (A opens 3.45 s / B 5.95 s at 20/s, B never at 5/s, 1-of-10 down never opens, recovery at 90 s →
first success 124.00 / 126.15 s, 120.00 s with "No instances available" ignored) - `CircuitBreakerScenarioTest`, virtual
time, Spring's real health-check supplier + resilience4j's real CircuitBreaker on one clock. Write-up:
`docs/resilience4j-circuit-breaker.md`.

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
