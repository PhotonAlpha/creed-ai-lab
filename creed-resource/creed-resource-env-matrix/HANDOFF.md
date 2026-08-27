# creed-resource-env-matrix — handoff

**Purpose** Backend for the Env Matrix Viewer: the environment host/ip/port mapping matrix, with
conflict detection. **The only resource server backed by a real database.**
**Skill** `creed-env-matrix` · shared: `creed-platform` · frontend: `../../creed-env-matrix-design`

## Run

```bash
# once — the database must exist before first start
docker exec creed-artifactory-db createdb -U artifactory env_matrix

# frontend integration: plain HTTP on 3001, the port Vite proxies. No PKI needed.
mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"

# mesh-style HTTPS
mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=primary -Dspring-boot.run.workingDirectory="$PWD"

curl http://localhost:3001/api/env-matrix/ping
```

| Profile | Port | Transport |
|---|---|---|
| `dev` | 3001 | plain HTTP |
| `primary` / `secondary` | 18095 / 18096 | HTTPS |
| `cloud` | 18084 | HTTPS, config-server driven |

`cloud` runs one instance per service on a contiguous range (catalog 18081, order 18082,
payment 18083, env-matrix 18084) — this module originally used 18095 there, breaking that pattern.

## Current state

Complete and verified end to end against real PostgreSQL. **32 tests pass.**

- Flyway `V1` creates `env_endpoint` (7 dimension columns + host/ip/port, unique index on the
  dimension tuple, indexes for the conflict scan); `V2` seeds **1235 rows** including **4 deliberate
  conflicts** (see the `note` column) so a fresh database is not empty.
- Flyway `V3` creates `env_app_link` and seeds the declared topology for all four tiers.
- `/api/env-matrix`: endpoint CRUD, batch save, `/matrix`, `/conflicts`, `/dimensions`, `/health`,
  `/health/recheck`, plus **link CRUD** (`/links`, `/links/{id}`) and a per-tier link batch save.
  Errors use one envelope `{error, message, fields?, time}`.
- `ConflictDetector` groups on `host:port` **and** `ip:port` inside a bucket set by
  `env-matrix.conflict.scope` (`TIER_ENV` default).
- `HealthProbeService` is **mock by default** (no network); `real` does a plain TCP connect.
- Batch save writes the whole table in one transaction, skipping rows that did not change.
- `AppLinkService` owns the declared app-system topology — the graph's edges. It shares nothing with
  endpoints (no conflicts, no health, no seven-dimension identity), which is why it is a separate
  service rather than more methods on `EnvMatrixService`.

## Landmines

- **`env_app_link` is scoped to the TIER, not the environment instance.** SIT1 and SIT2 are two
  instances of one wiring; per-instance declarations drift. The link batch save is therefore
  authoritative for exactly one tier — `PUT /links` deletes only what is missing *from that tier* —
  which is what lets the editor work on SIT without holding UAT's rows. Do not "improve" it into a
  whole-table save; that is a constraint the endpoint editor has and this one deliberately avoids.
- **`direction` decides arrowheads only.** The stored `sourceApp -> targetApp` orientation is what
  the frontend's layered view ranks on, so a `BIDIRECTIONAL` link still has a defined upstream end.
  Treating a two-way link as an edge in both directions would make every such pair a cycle with no
  defined layering.
- **A link may name an app system that has no endpoints.** That is legal on purpose — the frontend
  draws it as a placeholder, and the gap between "wired into the topology" and "recorded in the
  matrix" is exactly what the viewer exists to surface. Do not add a foreign key.
- **`dev` and `test` exclude `SslObservabilityAutoConfiguration`** and disable the SSL health
  indicator. Boot's `SslMeterBinder` eagerly opens **every declared SSL bundle** at startup and has
  **no disable property**, so `creed.https.enabled=false` alone is not enough — a missing keystore
  fails the whole context. Don't remove those exclusions from either profile.
- **Tests run on H2 with Flyway off** (V1/V2 are PostgreSQL-specific) and must set
  `spring.application.name` and `server.port`: the `actuator` profile is `include`d from
  `application.yml`, cannot be un-included by a profile-specific file, and OTel interpolates both.
- **`deleteMissing: true` makes a batch save authoritative.** Any caller using it must hold the
  complete, unfiltered set — a filtered subset deletes everything the filter hid.
- Conflicts are **reported, never rejected**; only duplicate identities give 409.
- The `note` column carries the seeded-conflict explanations — don't bulk-clear it.

## Open items

- OAuth2 validation is off (`permitAll`, `CachedJwtDecoderConfiguration` not annotated) — mesh-wide,
  same as the other resource servers.
- `env-matrix.health.mode=real` is implemented but **never exercised** — the seeded hosts are not
  reachable from a dev machine, so it has only been reasoned about, not run against live endpoints.
- Not wired into either gateway or `creed-simple-metrics`; it is currently only consumed by its own
  frontend.
