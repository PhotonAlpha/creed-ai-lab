# Env Matrix Viewer — build and run on Node

[中文](./DEPLOY.zh-CN.md)

How to turn this project into one Node process that serves the UI, owns the Splunk session broker
and proxies everything else to `creed-resource-env-matrix`. For local development, see
[README.md](./README.md) §1 instead.

```
browser ──► BFF (node server/bff.js, :3002)
              ├─ /api/env-matrix/splunk/*  answered here ── Splunk Web (HTTPS, form login)
              │                                         └── audit: memory (default) | PostgreSQL | MySQL (SPLUNK_AUDIT_STORE)
              ├─ /api/*                    proxied ─────── creed-resource-env-matrix (:18095)
              └─ everything else           dist/ (index.html for client routes)
```

The build produces static files; the only thing that *runs* is `server/bff.js`. Its single runtime
dependency is `pg`. React, antd and G6 are bundled into `dist/` and are not needed on the server.

---

## 1. Prerequisites

| | Build machine | Runtime host |
|---|---|---|
| Node | ≥ 22.9 (`--env-file-if-exists`); tested on 24.16 | same |
| npm | ships with Node | same |
| Network | npm registry | PostgreSQL, `creed-resource-env-matrix`, Splunk Web |
| openssl | optional — only for one `test:server` case | — |

A database is needed **only with `SPLUNK_AUDIT_STORE=pg` or `mysql`**. The default audit store is memory (newest
500 rows, lost on restart), and then no database is involved at all. With pg, the database must
exist (`env_matrix`) and the DB user needs `CREATE` on it, or a DBA creates the schema first (§5).

## 2. Build

From `creed-env-matrix-design/`:

```bash
npm ci                    # exact versions from package-lock.json
npm run typecheck         # tsc -b — NOT `tsc --noEmit`, which checks nothing here
npm run test:server       # the Splunk broker's tests (node:test, no network, no DB)
npm run build             # tsc -b && vite build  ->  dist/
```

`dist/` is the whole UI: `index.html` + fingerprinted `assets/`. `vite.config.ts` has
`sourcemap: true`, so `dist/` also carries `.map` files (~most of its 18 MB) that expose the
TypeScript source. Delete them if that matters: `find dist -name '*.map' -delete`.

## 3. Package

```bash
npm run package:bff                                   # -> release/env-matrix-bff/
RELEASE_DIR=/some/path npm run package:bff            # or anywhere else
```

`scripts/package-bff.mjs` copies only what runs and installs only `pg`:

```
release/env-matrix-bff/
├── dist/                  the built UI
├── server/bff.js
├── server/splunk/         broker code, without *.test.js
├── package.json           "type": "module", dependencies: { pg }, scripts.start
├── package-lock.json
├── node_modules/          pg + mysql2 only
└── .env.server.example    every setting, commented
```

**Do it by hand** if you'd rather not use the script. The two easy-to-miss parts: `"type": "module"`
(the server is ES modules in `.js` files — Node 22.9–22.11 refuses them without it, newer versions
re-parse with a warning) and `dist/` sitting next to `server/` (the BFF looks for
`../dist` relative to `bff.js` unless `BFF_STATIC_DIR` says otherwise):

```bash
R=release/env-matrix-bff && mkdir -p $R/server
cp -R dist $R/ && cp server/bff.js $R/server/ && cp -R server/splunk $R/server/ && rm $R/server/splunk/*.test.js
cp .env.server.example $R/
cat > $R/package.json <<'EOF'
{ "name": "env-matrix-bff", "private": true, "type": "module",
  "engines": { "node": ">=22.9" },
  "scripts": { "start": "node --env-file-if-exists=.env.server.local server/bff.js" },
  "dependencies": { "pg": "8.23.1" } }
EOF
(cd $R && npm install --omit=dev)
```

`pg` is pure JavaScript, so a release built on macOS runs on Linux. Ship it as an archive:

```bash
tar -C release -czf env-matrix-bff.tgz env-matrix-bff
# on the host
tar -xzf env-matrix-bff.tgz -C /opt
```

## 4. Configure

Settings come from, in order of precedence: real environment variables → `.env.server.local` in
the release directory (loaded by `npm start`) → built-in defaults. `.env.server.example` lists every
variable. Any secret can instead be given as `NAME_FILE=/path/to/file` (Docker/Kubernetes secrets, a
Vault agent sink); the file wins over `NAME`.

```bash
cd /opt/env-matrix-bff
cp .env.server.example .env.server.local && chmod 600 .env.server.local
```

**Set these for production — the defaults are for a laptop:**

| Variable | Default | Production |
|---|---|---|
| `ENV_MATRIX_API_TARGET` | `https://localhost:18095` | the backend's real URL |
| `ENV_MATRIX_API_INSECURE` | `true` | `false` + `ENV_MATRIX_API_CA_FILE` when the backend has a proper cert |
| `ENV_MATRIX_TOTP_SECRET` (`_FILE`) | `JBSWY3DPEHPK3PXP` — **demo** | a fresh Base32 secret |
| `ENV_MATRIX_TOTP_EXPOSE_CODE` | `true` — the page shows the code | `false` if the OTP is meant to gate anything |
| `SPLUNK_ENABLED` | `false` — fabricated cookie | `true` |
| `SPLUNK_LOGIN_URL` | `splunk.example.invalid` | `https://<splunk>:8000/en-US/account/login` |
| `SPLUNK_USERNAME` / `SPLUNK_PASSWORD` (`_FILE`) | `admin` / `admin` | the shared account |
| `SPLUNK_TLS_INSECURE` | `true` — certificate **not verified** | as required; `false` + `SPLUNK_CA_FILE` to verify |
| `SPLUNK_AUDIT_STORE` | `memory` — newest 500 rows, **lost on restart** | `pg` or `mysql` to keep an audit trail |
| `SPLUNK_DB_URL` (pg / mysql) | `postgres://127.0.0.1:5432/env_matrix`, or `mysql://127.0.0.1:3306/env_matrix` with mysql | the real host; a URL for the other database is rejected at startup |
| `SPLUNK_DB_USER` / `SPLUNK_DB_PASSWORD` (`_FILE`, pg / mysql) | `artifactory` / `artifactory_pw` | real credentials |
| `BFF_PORT` / `BFF_HOST` | `3002` / all interfaces | `BFF_HOST=127.0.0.1` behind a reverse proxy |

Generate a TOTP secret: `node -e "const a='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';console.log([...require('crypto').randomBytes(20)].map(b=>a[b&31]).join(''))"`.

Invalid values (a non-Base32 secret, a bad schema name, out-of-range numbers) fail at startup with
the variable's name, not on the first request.

## 5. Prepare the database (`SPLUNK_AUDIT_STORE=pg` or `mysql`)

Skip this section with the default memory store.

**MySQL** (8.0+, or MariaDB 10.5+): create the database and an account that may create a table in it;
the BFF creates `splunk_audit` (InnoDB, utf8mb4) on first start. Times are stored as UTC `datetime(3)`
whatever the server's time zone.

```sql
create database env_matrix character set utf8mb4;
create user 'artifactory'@'%' identified by '…';
grant create, select, insert on env_matrix.* to 'artifactory'@'%';
```

**PostgreSQL**: there is nothing to do if the DB user may
create schemas: on first start the BFF runs
`create schema if not exists splunk_broker` + the table + two indexes, and copies any rows from the
legacy `public.splunk_audit` (Flyway V6 of the Java module) once. Otherwise, as a privileged user:

```sql
create schema splunk_broker authorization artifactory;   -- the BFF does the rest
```

The audit lives in its own schema on purpose — see README → *Why its own schema*.

## 6. Start and verify

```bash
cd /opt/env-matrix-bff
npm start
# equivalent without npm:
node --env-file-if-exists=.env.server.local server/bff.js
```

A healthy start logs the effective configuration (every secret masked) and then:

```
[bff] listening on http://localhost:3002 — splunk=real, audit=pg, /api -> https://…:18095, static=/opt/env-matrix-bff/dist
```

`audit=memory` (the default) is preceded by a warning that the audit is lost on restart. With
`audit=pg` it **refuses to start** when PostgreSQL is unreachable: the broker never issues a session
it cannot audit. With `SPLUNK_TLS_INSECURE=true` and Splunk enabled it also logs a warning saying so.

```bash
B=http://localhost:3002
curl -s -o /dev/null -w '%{http_code}\n' $B/                          # 200  the UI
curl -s -o /dev/null -w '%{http_code}\n' $B/topology                  # 200  client route -> index.html
curl -s $B/api/env-matrix/ping                                        # proxied to the Java backend
curl -s $B/api/env-matrix/splunk/totp                                 # broker: splunkMode, splunkConfigured
curl -s "$B/api/env-matrix/splunk/audit?limit=5"                      # reads the audit store (memory or pg)
```

Then open `/splunk` in a browser and request a session. With `SPLUNK_ENABLED=false` the cookie is
`mock-…`; with it on, a 502 `splunk_no_session_cookie` usually means wrong credentials (Splunk
answered 401) or a missing `cval` (answered 200).

Stop with Ctrl-C / `SIGTERM`: the server stops accepting connections and closes the DB pool.

## 7. Keep it running

**One instance.** Replay protection and the failed-code lockout live in process memory. Two
instances (or pm2 cluster mode) would each accept the same code once. Scale out only after moving
that state somewhere shared.

The two templates below were written for this guide but **not run here**. Adjust paths and the user.

### systemd

```ini
# /etc/systemd/system/env-matrix-bff.service
[Unit]
Description=Env Matrix BFF
After=network-online.target
Wants=network-online.target

[Service]
User=envmatrix
WorkingDirectory=/opt/env-matrix-bff
ExecStart=/usr/bin/node --env-file-if-exists=.env.server.local server/bff.js
Restart=on-failure
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now env-matrix-bff
journalctl -u env-matrix-bff -f
```

`Restart=on-failure` also covers "database not up yet" at boot.

### Docker

```dockerfile
# build context: release/env-matrix-bff
FROM node:24-alpine
WORKDIR /app
COPY . .
RUN rm -f .env.server.local
USER node
EXPOSE 3002
CMD ["node", "server/bff.js"]
```

Pass settings with `-e`/`--env-file`, secrets with `*_FILE` + mounted secrets. Inside a container,
`127.0.0.1` is the container itself — point `SPLUNK_DB_URL` and `ENV_MATRIX_API_TARGET` at real hosts.

## 8. Behind a reverse proxy (TLS)

The BFF speaks plain HTTP. Terminate TLS in front of it and bind it to loopback (`BFF_HOST=127.0.0.1`):

```nginx
location / {
    proxy_pass http://127.0.0.1:3002;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

The audit records `X-Forwarded-For` but does not trust it. The client address and the lockout key
are the TCP peer — behind a proxy, that is the proxy, so **every user shares one lockout bucket**
(5 wrong codes from anyone lock everyone out for a minute).

## 9. Upgrade and rollback

1. Build and package the new version (§2–3) into a new directory, e.g. `/opt/env-matrix-bff-1.1.0`.
2. Copy `.env.server.local` across.
3. Point a symlink at it (`ln -sfn /opt/env-matrix-bff-1.1.0 /opt/env-matrix-bff`) and restart.

Rollback is the symlink back plus a restart. The audit schema is `create … if not exists`, so
versions can be swapped freely. Restarting clears the replay memory and lockout counters.

## 10. Before you deploy — checklist

- [ ] No debug `console.log` in `server/splunk/` that prints the `splunk` config object — it contains
      `password`. Logs must carry fingerprints and masked config only.
- [ ] `ENV_MATRIX_TOTP_SECRET` is not the demo value; `ENV_MATRIX_TOTP_EXPOSE_CODE` decided on purpose.
- [ ] `SPLUNK_TLS_INSECURE` decided on purpose (default: no certificate verification).
- [ ] `.env.server.local` is `chmod 600` and not in any archive you share; prefer `*_FILE`.
- [ ] Source maps removed from `dist/` if the source must not be published.
- [ ] `SPLUNK_AUDIT_STORE` decided on purpose: the default `memory` loses the audit on every restart.
- [ ] Exactly one instance running.

## 11. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `SyntaxError: Cannot use import statement outside a module` (Node 22.9–22.11) or a `MODULE_TYPELESS_PACKAGE_JSON` warning (Node ≥ 22.12, which re-parses and carries on) | `package.json` next to `server/` lacks `"type": "module"` |
| `Cannot find package 'pg'` | `npm install --omit=dev` not run in the release directory |
| `SASL: … client password must be a string` | no DB password reached `pg` — set `SPLUNK_DB_PASSWORD` (or `_FILE`) |
| startup fails with `ECONNREFUSED …:5432` / `permission denied for database` | `SPLUNK_AUDIT_STORE=pg` and the DB is unreachable / no `CREATE` — see §5 |
| startup fails with `ECONNREFUSED …:3306` / `Access denied for user` / `Unknown database` | `SPLUNK_AUDIT_STORE=mysql` and the DB is unreachable / wrong credentials / database not created — see §5 |
| `SPLUNK_DB_URL '…' does not fit SPLUNK_AUDIT_STORE=…` | a `postgres://` URL with `mysql`, or the reverse |
| audit empty after a restart | the default `SPLUNK_AUDIT_STORE=memory` — set `pg` or `mysql` to persist |
| `EADDRINUSE :::3002` | another BFF is running; `lsof -nP -iTCP:3002 -sTCP:LISTEN` |
| `/` answers ``no build at … — run `npm run build` first`` | `dist/` missing or `BFF_STATIC_DIR` wrong |
| `/api/...` → `502 bad_gateway` | `ENV_MATRIX_API_TARGET` unreachable, or its certificate rejected with `ENV_MATRIX_API_INSECURE=false` |
| `/splunk/session` → `503 not_configured` | Splunk enabled but URL/user/password missing — the code was **not** consumed |
| `/splunk/session` → `502 splunk_io_error` | Splunk unreachable / timeout / TLS rejected (`SPLUNK_TLS_INSECURE=false`) |
| `/splunk/session` → `502 splunk_no_session_cookie` | Splunk 401 = credentials; 200 = `cval` rejected or wrong `SPLUNK_SESSION_COOKIE` |
| `/splunk/session` → `401 otp_replayed` | the code was already used — wait for the next one |
| `/splunk/session` → `429` | 5 wrong codes from this address in 60 s; behind a proxy, from anyone (§8) |
| `.env.server.local` ignored | it is read from the **working directory** — start from the release dir |
