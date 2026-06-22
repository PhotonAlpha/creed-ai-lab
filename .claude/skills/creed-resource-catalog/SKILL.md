---
name: creed-resource-catalog
description: The creed-resource-catalog module — an OAuth2 resource server (Spring MVC, HTTPS) exposing /api/catalog/* product endpoints, run as two instances (primary 8081 / secondary 8082) behind client-side load balancers, with a cached JWT decoder and config-server-driven cloud profile. Use when working on catalog endpoints, the resource-server JWT/security wiring, or its primary/secondary/cloud profiles.
---

# creed-resource-catalog

OAuth2 **resource server** (Spring MVC, embedded HTTPS) serving the product catalog. Symmetric twin of [[creed-resource-order]] (same structure; `catalog`↔`order`, `/api/catalog`↔`/api/order`). A downstream of both gateways. See [[creed-platform]] for SSL bundles, the OIDC local-startup stub, and the HTTPS listener.

## Run topology — profiles
- **`primary`** (port 8081, app name `creed-resource-catalog-primary`) and **`secondary`** (8082, `...-secondary`): the two LB instances the gateways round-robin. Started locally as two processes.
- **`cloud`** (`application-cloud.yml`): pulls config (SSL bundle + `{cipher}` secrets) from [[creed-config-server]] over HTTPS (`spring.config.import=configserver:https://localhost:8443/config-server`, Basic auth, classpath `truststore.p12`), and sets `spring.security.oauth2.resourceserver.jwt.issuer-uri=${CREED_AUTH_ISSUER:...}`.
- **`actuator`** profile `include`d → OTel/metrics export (see [[creed-platform]] Observability).

## Endpoints (`api/CatalogController`, `/api/catalog`)
- `GET /items` (`@AuthenticationPrincipal Jwt jwt` — null-safe since security is `permitAll` locally), `GET /ping` (the **LB health-check path** the partner gateway probes), `GET /` (list), `GET /{sku}`. `Product` is the model.

## Security (`config/SecurityConfiguration`)
- `SecurityFilterChain` with `anyRequest().permitAll()`; the `oauth2ResourceServer().jwt()` line is **commented out** → no token needed in `primary`/`secondary`. Re-enable for real JWT validation.
- `config/CachedJwtDecoderConfiguration` — `@Configuration` is **commented out**; when enabled it builds `NimbusJwtDecoder.withIssuerLocation(issuerUri).cache(jwkSetCache)`. It resolves the issuer **eagerly at startup**, so `primary`/`secondary` need the OIDC stub from [[creed-platform]] (or leave it disabled).

## Notes
- SSL bundle `creed-order-server` (alias `creed-resource-catalog`) from `file:${creed.rootPath}/...` → run with `-Dspring-boot.run.workingDirectory="$PWD"`.
- `creed.oauth2.jwk-set-cache-minutes` (cloud) tunes JWK cache TTL.