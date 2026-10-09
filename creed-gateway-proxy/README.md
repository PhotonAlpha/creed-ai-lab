# creed-gateway-proxy

The Env Matrix front door on a server, on Spring Cloud Gateway (reactive). One jar serves:

| Path | Handled by |
|---|---|
| `/api/env-matrix/splunk/*` | **the Splunk session broker**, in this process (moved here from the Node BFF) |
| `/api/**` | proxied to creed-resource-env-matrix (`CREED_PROXY_API_TARGET`) |
| everything else | the built frontend in `static/`, with `index.html` for client routes (`/aes`, `/splunk`) |

With the `tunnel` profile it is the original nginx-style proxy instead: every path forwarded to an
`ssh -R` tunnel (see [Tunnel profile](#tunnel-profile)).

## Deploy: frontend + backend in one jar

```bash
# 1. build the frontend and put it into the jar's static folder
cd creed-env-matrix-design && npm run build && cd ..
rm -rf creed-gateway-proxy/src/main/resources/static
cp -r creed-env-matrix-design/dist creed-gateway-proxy/src/main/resources/static

# 2. package (plain `package` gives no executable jar in this repo)
mvn -q -pl creed-gateway-proxy -am -DskipTests install
mvn -q -pl creed-gateway-proxy -DskipTests package spring-boot:repackage

# 3. run on the server (Java 21+). Passwords are asked for on the terminal — see below.
SPLUNK_ENABLED=true \
SPLUNK_LOGIN_URL=https://splunk-uat:8000/en-US/account/login SPLUNK_USERNAME=svc-splunk \
CREED_PROXY_API_TARGET=https://env-matrix-host:18095 \
java -jar creed-gateway-proxy-1.0.0-SNAPSHOT.jar
```

To swap the UI without rebuilding the jar, point `CREED_PROXY_STATIC_LOCATIONS` at a directory
instead: `CREED_PROXY_STATIC_LOCATIONS=file:/opt/env-matrix/static/`.

Static files are served with the right cache rules. `assets/*` are Vite-fingerprinted and get a
one-year `immutable` header. Everything else is `no-cache`, so a new `index.html` is always picked up.

## Passwords are supplied at startup, never in a file

`application.yml` holds no password. Each secret is resolved in this order:

1. the environment variable (or `-DNAME=…`);
2. `NAME_FILE`, a file holding it (trimmed): Docker/Kubernetes secrets, a Vault agent sink;
3. otherwise, **if a terminal is attached, a prompt with echo off**:

```
Splunk password for target default (svc-splunk @ https://splunk-uat:8000/…) [SPLUNK_TARGET_DEFAULT_PASSWORD]:
TOTP secret (Base32; Enter = a random one for this run) [ENV_MATRIX_TOTP_SECRET]:
```

| Secret | Asked for when | If missing |
|---|---|---|
| `SPLUNK_TARGET_<ID>_PASSWORD` | `SPLUNK_ENABLED=true` (mock mode needs none) | that target is "not configured": a 503 on login, and the code is not used |
| `ENV_MATRIX_TOTP_SECRET` | always | a random secret for this run (fine while the page shows the code; useless to an authenticator app) |
| `SPLUNK_DB_PASSWORD` | audit store `pg` / `mysql` | startup fails if the database refuses |

Without a terminal (systemd, `nohup`), or with `CREED_SECRETS_PROMPT=false`, nothing is asked: use
the variable or `_FILE`. Values are never logged.

## Splunk broker

Same contract as the Node BFF had, so the page needs no change. Additions:

- **Block window.** `SPLUNK_BLOCK_WINDOWS` (default `22:00-09:00`; `HH:mm-HH:mm[,…]`, where an end
  before the start crosses midnight; blank means never blocked) is read on `SPLUNK_BLOCK_ZONE` (default:
  the server's zone). Inside a window the session API answers
  `403 blocked` with `Retry-After`, **before** the code is checked, so the code is not used. The
  attempt is audited (`SPLUNK_LOGIN / blocked`). The page shows the window, disables the button,
  and unlocks by itself when the window ends.
- **`Secure` follows the scheme.** The returned `document.cookie` script carries `Secure` only for
  an `https://` login URL. The script runs on Splunk Web's own page, and a browser drops a Secure
  cookie set from an http page. The session response also says `"secure": true|false`.
- **One-time code every 60 s** (`ENV_MATRIX_TOTP_PERIOD_SECONDS`), accepted ±1 step. Authenticator
  apps assume 30 s, so an app reading the same secret would show different codes. The code shown on
  the page is the one this broker accepts.

Configuration (`creed.splunk.*` / `creed.totp.*` in `application.yml`; the main ones also as
variables):

| Variable | Default | |
|---|---|---|
| `SPLUNK_ENABLED` | `false` | `false` returns a fabricated `mock-…` cookie and makes no call |
| `SPLUNK_LOGIN_URL` / `SPLUNK_USERNAME` / `SPLUNK_LABEL` / `SPLUNK_TARGET_ID` | example / `admin` / `default` / `default` | the single default target |
| `SPLUNK_TUNNEL` / `SPLUNK_TUNNEL_DEFAULT` | — / `false` | `host:port` to connect through (curl `--connect-to`: Host and SNI stay the real host) |
| `SPLUNK_SESSION_COOKIE` / `SPLUNK_SCRIPT_COOKIE_NAME` | `splunkd_8000` / `splunkd_8089` | defaults; per target and per request overridable |
| `SPLUNK_TLS_INSECURE` | `true` | Splunk's certificate is not checked. `false`: system roots, or `SPLUNK_CA_FILE` (PEM) |
| `SPLUNK_BLOCK_WINDOWS` / `SPLUNK_BLOCK_ZONE` | `22:00-09:00` / server zone | see above |
| `SPLUNK_AUDIT_STORE` | `memory` | `memory` (newest 500), `pg` (`splunk_broker.splunk_audit`), `mysql` (`splunk_audit`) |
| `SPLUNK_DB_URL` / `SPLUNK_DB_USER` | local `env_matrix` / `artifactory` | JDBC URL; the Node BFF's `postgres://…` form works too |
| `ENV_MATRIX_TOTP_EXPOSE_CODE` | `true` | the page shows the current code (the OTP is decorative until off) |

**Several targets:** list them in an external file and add
`--spring.config.additional-location=file:./splunk-targets.yml`:

```yaml
creed:
  splunk:
    default-target: UAT
    targets:
      - { id: SIT, label: SIT, login-url: "https://splunk-sit:8000/en-US/account/login", username: svc-splunk }
      - { id: UAT, label: UAT, login-url: "https://splunk-uat:3000/en-US/account/login", username: svc-splunk,
          session-cookie: splunkd_3000, tunnel: "relay-host:3000" }
```

Each target is then prompted for, or read from `SPLUNK_TARGET_SIT_PASSWORD` /
`SPLUNK_TARGET_UAT_PASSWORD`.

The pg store writes `splunk_broker.splunk_audit`, the same table the Node BFF wrote, so the
existing rows stay visible. It never touches `public.splunk_audit` (Flyway V6 of
creed-resource-env-matrix).

## Other settings

| Variable | Default | |
|---|---|---|
| `CREED_PROXY_PORT` / `CREED_PROXY_ADDRESS` | `8088` / `0.0.0.0` | public listener |
| `CREED_PROXY_API_TARGET` | `https://localhost:18095` | env-matrix backend for `/api/**` |
| `CREED_PROXY_API_INSECURE` | `true` | don't verify the backend's (Creed-CA) certificate |
| `CREED_PROXY_STATIC_LOCATIONS` | `classpath:/static/` | where the built frontend is |
| `CREED_PROXY_MANAGEMENT_PORT` | `8089` | actuator, on `127.0.0.1` only |

- `curl localhost:8089/actuator/health/upstreams` makes a TCP connect to every route target.
- A target that refuses the connection gets a 502 that names it. Under `/api/` the body is
  `{error, message}` JSON.
- Plain HTTP, no login. Keep port 8088 firewalled to your network, or put TLS in front.

## Tunnel profile

The module's first job, still available: serve a laptop's page (e.g. Vite on `:5173`) from the server.

```bash
# laptop
ssh -N -R 127.0.0.1:15173:localhost:5173 -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes user@server
# server
java -jar creed-gateway-proxy-1.0.0-SNAPSHOT.jar --spring.profiles.active=tunnel     # CREED_PROXY_TARGET to change 15173
```

Every path goes to the tunnel (`creed.proxy.routes` in `application-tunnel.yml`; list order is
match order; `strip-prefix`; `preserve-host`). Vite's HMR WebSocket passes through. The target gets
`Host: 127.0.0.1:15173`, because Vite answers 403 to a host it doesn't know. The Splunk API is still
answered by this process.

## Tests

```bash
mvn -pl creed-gateway-proxy test
```

34 tests, with no network, Docker or real Splunk. They cover:
- RFC 6238 vectors;
- block windows;
- broker ordering and every refusal;
- the login client against loopback HTTP and self-signed HTTPS stubs (`cval`, no redirect
  following, tunnel with the real Host header and SNI, trusted vs insecure TLS);
- secret precedence;
- the app end to end: API, static files, SPA fallback, JSON 502;
- tunnel routing.
