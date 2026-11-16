# creed-resource-lb-health — HANDOFF

## State (2026-11-16)

Lab module, created to verify that Spring Cloud LoadBalancer's health check never hands out a partial instance list
(`update-results-list=false`) while results keep updating (`interval < refetch-instances-interval`, worst-case round
< refetch) and the first request does not wait for round one (`eager-load`, `initial-delay: 0`). Constraints and their
reasons are in `README.md`.

- Port 18099 (HTTP), context path `/api/lb-health`. Target service `lb-health-target`: 10 instances on
  18100-18109, served by this app's own extra Tomcat connectors (`creed.lb-health.target.embedded`).
- Probe mode `blocking` (default, `withBlockingHealthChecks(RestTemplate)`) or `webclient` (`withHealthChecks(WebClient)`).
- Debug logs (`[REFETCH]`, `[HC]`, `[HC-STREAM]`, `[HC-RESULT]`) behind `creed.lb-health.debug.enabled` / `CREED_LB_DEBUG`.
- Start-up WARNs `[LB-HEALTH-CONFIG]` for every violated rule, against the effective per-client properties.
- 18 tests: virtual-time StepVerifier tests of Spring's own supplier, validator unit tests, and an end-to-end
  `@SpringBootTest` that probes real loopback connectors (no Docker / PKI / issuer).

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
