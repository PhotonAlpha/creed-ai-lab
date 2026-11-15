# creed-gateway-proxy — handoff

## State (2026-10-09)

The Env Matrix front door, replacing the Node BFF. User docs are in `README.md`.

- **Splunk session broker ported from `creed-env-matrix-design/server/splunk/*` to Java.**
  - `SplunkController` (`/api/env-matrix/splunk/*`, same JSON).
  - `SplunkBroker`, `Totp`, `SplunkLoginClient` (Reactor Netty), `BlockWindows`, `SplunkSecrets`.
  - Audit stores `MemoryAuditStore` / `JdbcAuditStore` (pg, mysql).
- **New by request (2026-10-09):**
  - the block window (`SPLUNK_BLOCK_WINDOWS`, default `22:00-09:00`, 403 before the OTP);
  - `Secure` only for an https login URL;
  - TOTP period 60 s;
  - passwords supplied at startup (env / `_FILE` / terminal prompt), never in yml.
- **New by request (2026-10-11): revoke.** `POST /splunk/session/{id}/revoke` → Splunk Web
  `/account/logout` with the login's cookies (held in memory by `SplunkBroker`, 24 h / 500 cap),
  audited `SPLUNK_LOGOUT`; audit rows carry `revocable`; page has a Revoke column + `clearScript`.
  Verified against a stub that tracks live sessions (`tmp/splunk-logout-stub.mjs`, e2e
  `tmp/splunk-revoke-e2e.mjs`): live before, dead after, second revoke 404. **Not verified against a
  real Splunk** — whether its logout ends the splunkd token is Splunk's behaviour, not tested here.
  The Node mock/BFF has no revoke; the page hides the column there (no `revocable` field).
- **New by request (2026-10-11):** `creed.splunk.targets[].password` accepts `ENC(…)` only
  (`SecretCipher`, Spring Security Crypto `Encryptors.delux`), decrypted at startup with
  `CREED_SECRETS_KEY` (`_FILE` / env / prompt); `java -jar … encrypt` makes the value. Env / `_FILE`
  still override it. Plain text, a missing key or a wrong key all fail startup. Verified with the
  packaged jar (stdin encrypt, decrypt at startup, wrong key, plain text); `ps` shows only the key path.
- `/api/**` is proxied to the env-matrix backend (route `env-matrix-api`). The frontend is served
  from `classpath:/static/` (`StaticSiteConfig`: immutable `assets/`, `no-cache` otherwise, SPA
  fallback, `GET /` → `/index.html`).
- The original ssh-tunnel catch-all lives in `application-tunnel.yml` (`--spring.profiles.active=tunnel`).
- The Node BFF (`npm run bff`) and the mock still carry their own copy of the broker. Both got the
  `Secure` fix and the 60 s default, but **not the block window**. The Vite dev proxy now sends
  `/api/env-matrix/splunk` to :8088.

**Verified (2026-10-09):**
- `mvn -pl creed-gateway-proxy test`: 34 tests pass.
- The packaged jar against:
  - a Splunk stub that checks password and `cval` (`tmp/splunk-pw-stub.mjs`);
  - `npm run mock` as the `/api` backend;
  - the real `dist/` via `CREED_PROXY_STATIC_LOCATIONS`.
- Password typed at a real pty prompt (`script`), no echo. `_FILE` path. http login gives no `Secure`.
- Block 403 with `Retry-After`, audited. Browser: banner + disabled button, and the page **unlocked
  by itself** when a 2-minute window ended, then issued a session (`tmp/splunk-block-e2e.mjs`).
- pg audit on the real `env_matrix` DB: reused the Node-written `splunk_broker.splunk_audit` (61 rows),
  ids continue (62/63 are test rows from this check).

**Not verified:** a real Splunk; the mysql store (no MySQL here; the same code path as pg except
DDL and `created_at`).

- **New by request (2026-11-15):**
  - per-target block windows (`targets[].block.windows` / `.zone`, inheriting what is left out);
    the target is now resolved before the block check;
  - date-range windows (`yyyy-MM-dd HH:mm[:ss] - yyyy-MM-dd HH:mm[:ss]`), mixable with daily ones;
    `nextChange` walks window edges instead of stepping minutes over one day;
  - the caller's forward-confirmed reverse-DNS name on the page and in the audit (`client_host`).
  Verified against the running app (mock Splunk): SIT blocked by its own date range (403,
  `Retry-After` to 11-17 08:30, code not spent), UAT took the same code; `127.0.0.1` → `localhost`
  in `/totp` and the audit. **Not verified:** the page in a browser (Chrome automation timed out),
  the `alter table` on a real pg / mysql audit table.
- **New by request (2026-11-15): allowed windows + admin API.** `targets[].allow.windows` = the only
  times a target issues sessions (`403 not_available`, windows never sent to the page);
  `/api/env-matrix/splunk/admin/targets[/{id}/availability]` GET/PUT/DELETE with `Bearer SPLUNK_ADMIN_TOKEN`
  replaces them at runtime (memory only). `SplunkAdmin` (token hash, constant-time compare, 5 wrong / 5 min
  per IP → 429), `Availability` (block + allowed, `nextChange` over both). Error handlers moved to
  `SplunkApiErrors` (shared by both controllers). Verified over HTTP: 401 without/with a wrong token,
  PUT → SIT 403 `not_available` "available again at 2026-11-16 20:00", `/totp` does not contain the
  windows, a window covering now → login works, DELETE → back to config, the page's audit list has no
  ADMIN rows. **Not verified:** a restart wiping the API's change (by design), the page in a browser.
- `GatewayProxyAppTest.totpInfoHasSixtySecondStepsTargetsAndBlock` fails, and did before 2026-11-15:
  the committed yml defaults the TOTP period to 30 s and lists UAT/SIT, the test expects 60 s and a
  `default` target.

## Open items

- Retire the Node BFF's broker (or port the block window to it) once the jar is deployed.
- Several targets need an external yml (the default yml has one env-driven target).
- Basic auth / TLS on :8088 if the port cannot be firewalled.

## Landmines

- **Handler order decides who answers.** Controllers (order 0) beat gateway routes (1), which beat
  static files (lowest). A catch-all route (tunnel profile) therefore hides the frontend, but never
  the Splunk API.
- **Boot's welcome page bypasses the cache rules.** It answers `GET /` with index.html and no
  `Cache-Control`, and is created only for `spring.webflux.static-path-pattern=/**`, so that
  pattern is set to a dummy (Boot's own mappings are off anyway). And `ResourceWebHandler` drops an
  empty path before any resolver, hence the `GET /` → `/index.html` WebFilter (only when an
  index.html exists).
- **Reactor Netty ignores `remoteAddress()` for an absolute URI.** The tunnel login sends a path
  plus an explicit `Host` header. SNI is set by hand, so a verified certificate is checked against
  the real host even through the tunnel (pinned by `SplunkLoginClientTest`).
- **A list property binds from one source as a whole.** Overriding `creed.proxy.routes[0].uri`
  alone (env, test) drops `id`/`path` → startup fails with "id is required". Restate the route.
- **JDK 22+ `System.console()` is non-null even without a terminal.** `isTerminal()` decides
  (called reflectively; the source level is 21), otherwise the prompt would block on a nohup's stdin.
- **No spring-jdbc on purpose.** With it, `DataSourceAutoConfiguration` demands a URL even for the
  memory store. The JDBC store builds its own Hikari pool.
- **The broker blocks** (JDBC, the login) and runs on `boundedElastic`. Never call it from a Netty thread.
  The same goes for building `SplunkBroker.Client` in `SplunkController`: it does the reverse lookup,
  so `GET /totp` runs on `boundedElastic` too.
- **Admin rows are hidden by event type.** `SplunkAuditStore.list` drops `ADMIN_*` (the page's list);
  a new admin event must keep that prefix or it shows up on the page. The Node BFF reads the same pg
  table without that filter.
- **`client_host` is not in the Node BFF's DDL.** Both JDBC stores add it at startup (pg: `add column
  if not exists`; MySQL has no such clause — checked in `information_schema`). The one-time copy from
  `public.splunk_audit` uses `SplunkAuditRow.LEGACY_COLUMNS`, which lack it.
- `X-Forwarded-*` needs `trusted-proxies`, which also trusts the client's own headers. Off.
- Building a jar needs `package spring-boot:repackage` (repo-wide).
