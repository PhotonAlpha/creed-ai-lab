---
name: creed-gateway
description: The creed-gateway module — the REACTIVE (WebFlux/Reactor Netty) edge gateway on HTTPS 8080, aggregating downstream resource servers via an explicit ReactiveLoadBalancer + a non-load-balanced SSL-bundle WebClient. Use when working on reactive aggregation, the explicit loadBalancer.choose() pattern, the resource WebClient/SSL trust, or this module's webflux security.
---

# creed-gateway

The **reactive** edge gateway (Spring WebFlux / Reactor Netty). HTTPS `8080`, no context-path. Contrast with the servlet [[creed-gateway-partner]] — same role, opposite stack. See [[creed-platform]] for SSL bundles, the `lb://` scheme leak, and the HTTPS listener pattern (here it's the **webflux** variant of `TomcatHttpsConfiguration`).

## Aggregation pattern (`AggregateController`, `/api/aggregate/summary`)
Resolves instances **explicitly** rather than via an `lb://` URL — this is fix #3 for the scheme-leak (see [[creed-platform]]):
- Inject `ReactiveLoadBalancer.Factory<ServiceInstance>` → `factory.getInstance(serviceId)` → `Mono.from(loadBalancer.choose())` to pick a `ServiceInstance`, then build the absolute `https://host:port/...` URI yourself.
- Call downstream with a **plain (non-`@LoadBalanced`) `WebClient`** — `resourceWebClient` — so the already-resolved URI is used verbatim.

## Layout (`com.creed.gateway`)
- `api/AggregateController` — reactive aggregation via explicit `loadBalancer.choose()` + `resourceWebClient`.
- `web/GatewayWebClientConfiguration` — `resourceWebClient`: `WebClient` over a Reactor Netty `HttpClient` configured with the SSL bundle (mTLS trust of downstream resource servers).
- `web/TomcatHttpsConfiguration` — programmatic HTTPS (webflux variant), bundle `creed-gateway-server`.
- `config/SecurityConfiguration` — `SecurityWebFilterChain`; currently `anyExchange().permitAll()` (oauth2 resource server is **commented out** — no token needed locally).
- `config/CachedReactiveJwtDecoderConfiguration` — reactive cached JWK decoder (wired when oauth2 is re-enabled).

## Config
- `application.yml`: port 8080, `spring.config.import=optional:configserver:` (optional — runs standalone), local SSL bundle `creed-gateway-server` from `file:${creed.rootPath}/creed-gateway-{keystore,truststore}.p12`.
- `application-cloud.yml`: config-server-driven variant.
- Because bundles use `file:${creed.rootPath}`, run with `-Dspring-boot.run.workingDirectory="$PWD"` (see [[creed-platform]]).

## Spring Boot 4 note

The HTTPS customizer is `web/ReactiveHttpsConfiguration`, a `WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory>`. It used to be a copy of the servlet modules' `TomcatHttpsConfiguration` typed on `TomcatServletWebServerFactory` — which this WebFlux/Netty module never creates, so the customizer never fired and the `creed-gateway-server` SSL bundle was silently never applied (the gateway served plain HTTP on 8080). Boot 4 forced the issue by moving the Tomcat classes into their own module. Do not reintroduce a servlet-typed customizer here.

The `cloud` profile bootstraps from the config server over HTTPS and trusts it via `classpath:certs/truststore.p12`, which is **not in git** — on a fresh clone that profile cannot start.
