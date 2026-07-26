# creed-gateway — handoff

**Purpose** The **reactive** (WebFlux / Reactor Netty) edge gateway.
**Skill** `creed-gateway` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-gateway spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"
curl -k https://localhost:8080/api/aggregate/summary
```

HTTPS `8080`, no context-path. Needs at least one catalog/order instance up to aggregate anything.

## Current state

- `api/AggregateController` (`GET /api/aggregate/summary`) aggregates downstream resource servers.
- **Explicit instance resolution**, not `lb://`: inject `ReactiveLoadBalancer.Factory`, call
  `factory.getInstance(serviceId)` → `Mono.from(loadBalancer.choose())`, then build the absolute
  `https://host:port/...` URI by hand and call it with the **plain, non-`@LoadBalanced`**
  `resourceWebClient`. This is fix #3 for the `lb://` scheme leak.
- `web/GatewayWebClientConfiguration` — `resourceWebClient` over a Reactor Netty `HttpClient`
  configured from the SSL bundle (mTLS trust of downstreams).
- `web/TomcatHttpsConfiguration` — the **webflux variant** of the programmatic HTTPS listener,
  bundle `creed-gateway-server`.

## Landmines

- **Security is `permitAll`.** `config/SecurityConfiguration` has `anyExchange().permitAll()` and the
  `oauth2ResourceServer` line **commented out** — no token is needed locally. Re-enabling it means
  also enabling `config/CachedReactiveJwtDecoderConfiguration`, which resolves the issuer eagerly and
  therefore needs a reachable auth server (or the OIDC stub from `creed-platform`).
- SSL bundle uses `file:${creed.rootPath}` → **must** run with
  `-Dspring-boot.run.workingDirectory="$PWD"` from the repo root.
- Reactive stack: don't copy servlet-only patterns from `creed-gateway-partner` (blocking LB
  suppliers, `RestClient` interceptors) into this module.

## Open items

- OAuth2 resource-server validation is commented out — the gateway currently authenticates nothing.
- Only `summary` aggregation exists; the servlet twin (`creed-gateway-partner`) has since grown a
  YAML-driven multi-cluster design that this module does not have. If both gateways are meant to stay
  feature-comparable, this is the gap.
