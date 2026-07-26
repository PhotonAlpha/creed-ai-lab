# creed-resource-catalog — handoff

**Purpose** OAuth2 resource server serving the product catalog. Structural twin of
`creed-resource-order`.
**Skill** `creed-resource-catalog` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-resource/creed-resource-catalog spring-boot:run \
  -Dspring-boot.run.profiles=primary -Dspring-boot.run.workingDirectory="$PWD"
# and, in a second shell, the same with -Dspring-boot.run.profiles=secondary
curl -k https://localhost:18081/api/catalog/ping
```

| Profile | Port |
|---|---|
| `primary` | 18081 |
| `secondary` | 18082 |
| `cloud` | 18081 |

## Current state

- `api/CatalogController` (`/api/catalog`): `GET /items`, `GET /ping` (**the LB health-check path**
  the partner gateway probes), `GET /` (list), `GET /{sku}`. Model `Product`, in-memory store.
- `@AuthenticationPrincipal Jwt` is null-safe — security is `permitAll` locally.
- `cloud` profile pulls config from `creed-config-server` over HTTPS and sets the JWT issuer.
- `actuator` profile included → OTel/metrics.

## Landmines

- **Security is disabled.** `config/SecurityConfiguration` is `anyRequest().permitAll()` with
  `oauth2ResourceServer().jwt()` commented out, and `config/CachedJwtDecoderConfiguration` has its
  `@Configuration` commented out too. **Re-enable both together** — the decoder resolves the issuer
  *eagerly at startup*, so `primary`/`secondary` then need a reachable auth server or the OIDC stub
  from `creed-platform`.
- The SSL bundle is named **`creed-order-server`** even in this module (alias
  `creed-resource-catalog`) — a copy-paste from the order twin. Harmless, but confusing; don't
  "correct" it without checking nothing references the name.
- `file:${creed.rootPath}` bundle → run with `-Dspring-boot.run.workingDirectory="$PWD"`.

## Open items

- OAuth2 validation is off (see above) — this is the mesh-wide open item, not specific to catalog.

**Note on the `cloud` port**: `cloud` runs **one** instance per service on a contiguous range —
catalog 18081, order 18082, payment 18083, env-matrix 18084 — separate from the primary/secondary
pairs. Those are mutually distinct, so there is no collision within the `cloud` topology; the ranges
only overlap if you mix `cloud` and `secondary` across services, which is not a topology anyone runs.
