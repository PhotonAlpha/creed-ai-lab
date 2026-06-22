---
name: creed-resource-order
description: The creed-resource-order module — an OAuth2 resource server (Spring MVC, HTTPS) exposing /api/order/* CRUD endpoints, run as two instances (primary 8091 / secondary 8092) behind client-side load balancers, with a cached JWT decoder and config-server-driven cloud profile. Use when working on order endpoints, the resource-server JWT/security wiring, or its primary/secondary/cloud profiles.
---

# creed-resource-order

OAuth2 **resource server** (Spring MVC, embedded HTTPS) serving orders. Symmetric twin of [[creed-resource-catalog]] (same structure; `order`↔`catalog`). A downstream of both gateways. See [[creed-platform]] for SSL bundles, the OIDC local-startup stub, and the HTTPS listener.

## Run topology — profiles
- **`primary`** (port 8091, app name `creed-resource-order-primary`) and **`secondary`** (8092, `...-secondary`): the two LB instances the gateways round-robin.
- **`cloud`** (`application-cloud.yml`): config from [[creed-config-server]] over HTTPS + `jwt.issuer-uri=${CREED_AUTH_ISSUER:...}` (same wiring as the catalog twin).
- **`actuator`** profile `include`d → OTel/metrics export (see [[creed-platform]] Observability).

## Endpoints (`api/OrderController`, `/api/order`)
- Full CRUD: `GET /items`, `GET /ping` (the **LB health-check path**), `GET /` (list), `GET /{id}`, `POST /`, `PUT /{id}`, `DELETE /{id}`. `Order` is the model. `@AuthenticationPrincipal Jwt` is null-safe under local `permitAll`.

## Security
- `config/SecurityConfiguration` — `anyRequest().permitAll()`, `oauth2ResourceServer().jwt()` **commented out**.
- `config/CachedJwtDecoderConfiguration` — `@Configuration` **commented out**; `NimbusJwtDecoder.withIssuerLocation(...)` resolves the issuer eagerly when enabled → needs the OIDC stub from [[creed-platform]] for `primary`/`secondary`.

## Notes
- SSL bundle `creed-order-server` (alias `creed-resource-order`) from `file:${creed.rootPath}/...` → run with `-Dspring-boot.run.workingDirectory="$PWD"`.
