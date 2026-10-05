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

Complete and verified end to end against real PostgreSQL. **37 tests pass.**

`V5` has been applied to the live `env_matrix` database and exercised from the frontend: pinning a
participant and reordering an app system wrote `layer` / `sort_order` (and bumped the version) on
**only** the two changed rows, a reload drew the stored layout, and the editor's *Reset all* put
every row back to `null` / `0`.

- Flyway `V1` creates `env_endpoint` (7 dimension columns + host/ip/port, unique index on the
  dimension tuple, indexes for the conflict scan); `V2` seeds **1235 rows** including **4 deliberate
  conflicts** (see the `note` column) so a fresh database is not empty.
- Flyway `V3` created `env_app_link`; **`V4` drops it** and creates the release topology —
  `env_release` / `env_release_node` / `env_release_link` — seeding one baseline release per tier
  plus a cross-country example. **`V5` adds `env_release_node.layer` / `.sort_order`**: where the
  graph draws a participant, as opposed to what it is connected to.
- **`layer` is nullable and `null` means "derive it from the links"** — the frontend ranks
  participants by a longest path over `env_release_link`, and a number here overrides that ranking
  for that participant only. `0` could not be the unset value: it means "column 0", so a link added
  later that ought to push the box right would silently disagree with a number nobody chose.
  `sort_order` is NOT NULL default 0, so a release nobody has arranged looks exactly as it did
  before the columns existed. Both are range-checked twice — `ck_env_release_node_layer` /
  `_sort_order` in `V5`, and per row in `ReleaseService.validate` so a stray number comes back as a
  422 naming the participant rather than a 400 for the whole release.
- `/api/env-matrix`: endpoint CRUD, batch save, `/matrix`, `/conflicts`, `/dimensions`, `/health`,
  `/health/recheck`, plus **release CRUD** (`/releases`, `/releases/{id}`) and the topology pair
  `GET|PUT /releases/{id}/topology`. Errors use one envelope `{error, message, fields?, time}`.
- `ConflictDetector` groups on `host:port` **and** `ip:port` inside a bucket set by
  `env-matrix.conflict.scope` (`TIER_ENV` default).
- `HealthProbeService` is **mock by default** (no network); `real` does a plain TCP connect.
- Batch save writes the whole table in one transaction, skipping rows that did not change.
- `ReleaseService` owns the declared topology — the graph's nodes *and* edges. It shares nothing
  with endpoints (no conflicts, no health, no seven-dimension identity), which is why it is a
  separate service rather than more methods on `EnvMatrixService`.

### Splunk session broker (`/splunk/*`)

`TOTP → Splunk Web login → document.cookie script`, every step audited. **61 tests pass.**
V6 has been applied to the live `env_matrix` database and the flow exercised by curl on the `dev`
profile with the switch off. **The real login has never been run against a real Splunk** — only
against a loopback stub in `RestClientSplunkLoginClientTest`; the user is testing that part.

```bash
SPLUNK_ENABLED=true SPLUNK_LOGIN_URL=https://<host>:8000/en-US/account/login SPLUNK_USERNAME=… SPLUNK_PASSWORD=… \
  mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"
```

- `TotpService` — RFC 6238 by hand (no dependency), checked against the RFC's appendix-B vectors.
  ±`allowed-drift-steps`, constant-time compare, replay rejection (**in memory, per instance**),
  per-address lockout (`max-failures` in `failure-window` → 429).
- `expose-current-code` is **on by default, by request**: `/splunk/totp/current` serves the code, so
  the OTP proves only that the caller could load the page. `ENV_MATRIX_TOTP_EXPOSE_CODE=false` to
  require an authenticator. The default secret `JBSWY3DPEHPK3PXP` is a demo value.
- **`env-matrix.splunk.enabled` (`SPLUNK_ENABLED`) is the switch, default `false`**: the client
  returns a fabricated `mock-…` value and sends nothing. On, `RestClientSplunkLoginClient` (Boot's
  `RestClient.Builder` over a JDK `HttpClient` with `Redirect.NEVER`) does the real login.
- Splunk configuration is checked **before** the OTP is verified: verifying consumes the code, so a
  503 after the check left the user's retry coming back `replayed` (the first bug report).
- `RestClientSplunkLoginClient` — `GET` login page for `cval`, form `POST` echoing it (cookie +
  field), `exchange` not `retrieve` (a 401 must be read, not thrown), reads `session-cookie` (`splunkd_8000`) from `Set-Cookie`. Script writes
  `script-cookie-name` (`splunkd_8089`) — kept separate because the requirement names both.
- `splunk_audit` (V6) — one row per OTP check and per Splunk call, linked by `correlation_id`,
  saved individually (the service is not `@Transactional`) so a Splunk failure keeps its OTP row.
  Stores a 16-hex SHA-256 **fingerprint** of the cookie, never the value.

### AES records (`/aes/*`, V7 `env_aes_record` + V8 `random_key`)

Backend of the frontend's `/aes` page (design: `docs/design.png`). **86 tests pass** (25 AES:
`AesCryptoServiceTest`, `AesControllerTest`). V7 and V8 applied to the live `env_matrix` DB and exercised by
curl on the `dev` profile.

- `AesCryptoService` — **`secretKey = randomKey + host + ip`** (plain concatenation, the real config
  files' rule; not an input), `key = PBKDF2WithHmacSHA256(secretKey, UTF-8(salt), 65536, 256)`,
  `AES/CBC/PKCS5Padding`, IV = exactly 16 UTF-8 bytes, Base64. **Salt is required** (400 naming
  `salt`). Vector `H24JNkxfMPSBHI3vtO41cQ==` (r4nd0m / ms1.cn.uat1 / 10.1.1.11, salt
  `s4lt 盐`) is pinned here and in the node mock (`server/aes.test.js`) and matches `openssl kdf` +
  `openssl enc` — change both or neither. Batch paths derive once per (secretKey, salt) via
  `AesCryptoService.KeyCache` (request-scoped). Saves take **plain values + IV + salt** and encrypt
  once per server; `/records/decrypt` takes `{id, iv?, salt?}` and derives the Secret Key from the
  record; `AesRecordDto.secretKey` is derived on read (no column). **V9 stores `iv` and `salt` per
  record** (nullable; by request): the record's own win in `/records/decrypt`, the request's are the
  fallback for pre-V9 rows. **With the Secret Key made of stored/public data and V9's IV/salt, a copy
  of `env_aes_record` decrypts every value in it.** Records saved under the
  earlier `SHA-256(secretKey + randomKey)` and unsalted `SHA-256(secretKey)` rules no longer decrypt.
- **The IV and the salt are never logged or put in a URL** (stored since V9; the Secret Key is derived, never stored). Request records override
  `toString`; no exception message carries a key, IV or value (a test checks the error body).
- **V8 adds `random_key`** (nullable — pre-V8 rows are "unknown", not ""): the randomkey in the form
  at save time, shown in the result list. Recorded, not verified (a pasted ciphertext cannot be
  checked without the Secret Key); not trimmed, since spaces are hashed. A changed randomkey alone
  counts as an update. Alone it decrypts nothing — it is only hashed with the Secret Key.
- Unusable keys (empty secret, IV ≠ 16 bytes) → `400 validation_failed` naming the field; a value that
  will not decrypt → `422 decrypt_failed`. Strict UTF-8 decoding, so a wrong key is not reported as
  U+FFFD soup.
- `env_aes_record` identity `(app_system, host, ip, property_key)` — in V7 *and* on `@Table`, for the
  H2 test schema. A save upserts per server and skips unchanged rows. **No FK to `env_endpoint`**, for
  the same reason as `env_release_node`: the config page rewrites endpoints wholesale.
- `GET /aes/servers` uses two queries, not `(:appSystem is null or …)` — PostgreSQL cannot type a
  parameter only ever compared with null.
- **Batches** (the page's "Keys and values" is an array of complete rows):
  `POST /aes/{encrypt,decrypt}/batch` — each item its own keys, per-row `{index, value | error, field}`
  (`invalid_key_material` / `decrypt_failed`); bean-validation shape errors stay one 400.
  `POST /aes/records/batch` — every item × every server in one transaction; a repeated property key
  is a 400 naming `items[i].propertyKey` (`DuplicatePropertyKeyException`). Single `POST /aes/records`
  is a batch of one. `POST /aes/records/decrypt` takes `{items: [{id, secretKey, iv, randomKey}]}`;
  unusable keys are that row's `invalid_key_material`, no longer a request-wide 400.
- Type-use `@Valid` on a nested type must be written `Outer.@Valid Inner` —
  `@Valid Outer.Inner` is a compile error that surfaces as a wall of Lombok `cannot find symbol: log`.

## Landmines

- **A topology node is a slice, not an app system.** `env_app_link` keyed a connection on
  `(tier, sourceApp, targetApp)` and so could not hold `SG CCS SIT3 -> Global-CCS SIT2 -> CN CCS
  SIT5`, where CCS appears twice. `env_release_node` is `(appSystem, country, envInstance)` and a
  release names a set of them. Do not "simplify" the link back to app systems.
- **`country = '*'` means "not country-specific", and is not NULL on purpose.** Postgres lets
  several NULLs through a unique index, so the identity would need a `coalesce()` expression index —
  which `@UniqueConstraint` cannot express, leaving the H2 test schema without the constraint.
- **`env_release_link.release_id` is redundant but load-bearing.** It is derivable from the two
  nodes; it is kept because the identity index needs it, and because it is what the service checks
  both ends against. Without that check a link could quietly stitch two releases together.
- **`release.tier` is a label, never validated against the participants.** A promotion chain
  legitimately spans tiers; the UI warns, the API does not reject.
- **`direction` decides arrowheads only.** The stored `source -> target` orientation is what the
  frontend's layered view ranks on, so a `BIDIRECTIONAL` link still has a defined upstream end.
  Treating a two-way link as an edge in both directions would make every such pair a cycle with no
  defined layering.
- **A participant may name a slice with no endpoints.** Legal on purpose — the frontend draws it as
  a placeholder, and the gap between "wired into the topology" and "recorded in the matrix" is
  exactly what the viewer exists to surface. Do not add a foreign key to `env_endpoint`.
- **Any client of `PUT /releases/{id}/topology` must resend `layer` and `sortOrder`.** The save
  replaces the release's whole topology, and a missing `layer` is read as "unpinned" — which is what
  makes a client that predates the columns harmless, and what makes one that simply forgot them
  destructive. The frontend's configuration page carries both fields through untouched.
- **`PUT /releases/{id}/topology` saves participants and links together**, and a link may point at a
  participant created in the same payload via `{"ref": "..."}`. They are one graph: a link cannot
  exist without its ends, and "add a participant and connect it" is the commonest edit. Splitting
  this into per-row routes would need two round trips and would leave an orphan participant in the
  database in between.
- **Children are deleted explicitly, not by the FK cascade.** The entities map the relationship as a
  plain id column, so the H2 schema the tests run against has no foreign key at all; doing it in the
  service keeps the behaviour identical in both.
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
