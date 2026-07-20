---
name: creed-resource-payment
description: The creed-resource-payment module — an OAuth2 resource server (Spring MVC, HTTPS) exposing /api/payment/* endpoints with an explicit PENDING→AUTHORIZED→CAPTURED→REFUNDED lifecycle (plus CANCELLED), run as two instances (primary 18093 / secondary 18094) behind client-side load balancers with cookie-based sticky routing, a cached JWT decoder, and config-server-driven cloud profile. Use when working on payment endpoints/state transitions, the resource-server JWT/security wiring, or its primary/secondary/cloud profiles.
---

# creed-resource-payment

OAuth2 **resource server** (Spring MVC, embedded HTTPS) serving payments. Same structural twin as
[[creed-resource-catalog]]/[[creed-resource-order]] (primary/secondary/cloud profiles, permitAll +
disabled JWT locally, cached-JWT-decoder-when-enabled), but modeled around explicit state instead of
plain CRUD. Aggregated by [[creed-simple-metrics]], which also adds **cookie-based sticky LB routing**
for this cluster specifically — see that skill's "Sticky routing for payment-resource" section. See
[[creed-platform]] for SSL bundles, the OIDC local-startup stub, and the HTTPS listener.

## Run topology — profiles
- **`primary`** (port `18093`, app name `creed-resource-payment-primary`) and **`secondary`** (`18094`,
  `...-secondary`): the two LB instances load balancers round-robin (or pin to, when the caller sends a
  matching `stickyId` cookie via creed-simple-metrics).
- **`cloud`** (`application-cloud.yml`): pulls config from [[creed-config-server]], sets
  `spring.security.oauth2.resourceserver.jwt.issuer-uri=${CREED_AUTH_ISSUER:...}` — same wiring as the
  catalog/order twins.
- Outside `cloud`, the SSL bundle `creed-payment-server` (alias `creed-resource-payment`) is defined
  locally in `application.yml` from `file:${creed.rootPath}/creed-resource-payment-{keystore,truststore}.p12`
  — run with `-Dspring-boot.run.workingDirectory="$PWD"`.
- **`actuator`** profile `include`d → OTel/metrics export (see [[creed-platform]] Observability).

## Endpoints (`api/PaymentController`, `/api/payment`)

Unlike the catalog/order twins' plain CRUD, payment has an explicit lifecycle:
`PENDING → AUTHORIZED → CAPTURED → REFUNDED`, with `CANCELLED` as a terminal side-exit from
`PENDING`/`AUTHORIZED` only. State-machine endpoints return **409 Conflict** (not 400/404) when the
payment isn't in the required predecessor state — useful for aggregators/tests that need a realistic
error path, unlike catalog/order's simpler CRUD-only surface:

| Method & path | Effect |
|---|---|
| `GET /ping` | LB health-check path (same shape as the twins') |
| `GET`, `GET /{id}` | list (optional `orderId`/`status` filter), fetch by id (404 if missing) |
| `POST` | create, starts `PENDING`; 400 on missing/non-positive amount or unknown `method` (`CARD`/`BANK_TRANSFER`/`WALLET`) |
| `PUT /{id}` | partial replace; 404 if missing |
| `POST /{id}/authorize` | `PENDING`→`AUTHORIZED`; 409 otherwise |
| `POST /{id}/capture` | `AUTHORIZED`→`CAPTURED`; 409 otherwise |
| `POST /{id}/refund` | `CAPTURED`→`REFUNDED`; 409 otherwise |
| `DELETE /{id}` | cancel: `PENDING`/`AUTHORIZED`→`CANCELLED` (200); 409 once captured/refunded (use `/refund` instead) |

In-memory `ConcurrentHashMap` store (`PAY-<seq>` ids from an `AtomicInteger` starting at 700), seeded
with two sample payments at startup — same "adequate for local/manual testing" convention as the other
resource twins.

## Security
- `config/SecurityConfiguration` — `anyRequest().permitAll()`; `oauth2ResourceServer().jwt()` **commented
  out** → no token needed in `primary`/`secondary`.
- `config/CachedJwtDecoderConfiguration` — `@Configuration` **commented out**; when enabled, builds
  `NimbusJwtDecoder.withIssuerLocation(issuerUri).cache(jwkSetCache)`, resolving the issuer **eagerly at
  startup** — needs the OIDC stub from [[creed-platform]] (or leave it disabled) for `primary`/`secondary`.
  `@AuthenticationPrincipal Jwt` is null-safe in the controller either way.