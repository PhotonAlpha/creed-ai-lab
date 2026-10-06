# creed-backstage — Creed Developer Portal

[中文](README.zh-CN.md)

A [Backstage](https://backstage.io) app: the **Software Catalog** for the Creed services, with each
service's REST API shown in **Swagger UI** (read-only, with a tag filter). The catalog is split into
**modules** — one per functional area (`creed-env-matrix`, `creed-payment`, `creed-order`, …) — each
with its own System, Component and API entity. An API's OpenAPI document is split into one `.yml`
per area, bundled into a single committed `dist/openapi.yaml` by Redocly CLI, and read by the
catalog with `$text`.

| | |
|---|---|
| Frontend | <http://localhost:3003> (not 3000 — Grafana's) |
| Backend | <http://localhost:7007> |
| Stack | Backstage 1.x (new frontend + backend systems), Yarn 4, Node 22/24, Redocly CLI 2 |

## Run

```bash
cd creed-backstage
corepack yarn install        # Yarn 4 is pinned in .yarnrc.yml; corepack runs it, no global install
corepack yarn start          # frontend + backend; sign in with "Enter" (guest)
```

The catalog's first pass runs about a minute after the backend starts; until then the catalog page
is empty. SQLite files live in `.sqlite/` (git-ignored) — delete the directory for a clean catalog.

**What `corepack` is.** Corepack ships with Node.js and runs the package manager a project declares
— here `"packageManager": "yarn@4.13.0"` in `package.json` (and `yarnPath` in `.yarnrc.yml`) — so
nothing has to be installed globally and everyone gets the same Yarn. `corepack yarn <cmd>` works as
is; `corepack enable` once would let you type plain `yarn`, but it writes shims into the global Node
installation, so it is optional (CI does it, because its container is thrown away). Corepack is
bundled with Node 14.19 / 16.9 up to 24; from Node 25 it must be installed with
`npm i -g corepack` — which is why `bitbucket-pipelines.yml` pins `node:22`.

## Catalog modules

```
catalog/
├── all.yaml                         Location: lists every module's catalog-info.yaml
├── org.yaml                         Group creed-platform (the owner)
├── creed-backstage.yaml             this portal, as a Component
├── creed-env-matrix/                ┐
├── creed-payment/                   ├ one directory per module, all with the same layout:
└── creed-order/                     ┘
    ├── catalog-info.yaml            System <module> + Component + API <module>-api
    ├── openapi.yaml                 entry document: info, servers, security by hand; paths GENERATED
    ├── paths/*.yml                  each a COMPLETE OpenAPI document (openapi, info, paths, components)
    └── dist/openapi.yaml            GENERATED single-file bundle — committed; the catalog reads THIS
```

A **module** is any `catalog/<module>/` directory with an `openapi.yaml`. Each one has its own
`metadata.name`s, so the catalog shows `creed-payment` and `creed-order` as separate systems with
separate APIs. Every `openapi:*` command works on **all modules** by default, or on the ones named:

| Command | What it does |
|---|---|
| `corepack yarn openapi:build [modules…]` | combine + bundle — the one command to run after an edit |
| `corepack yarn openapi:combine [modules…]` | regenerate `openapi.yaml`'s `paths:` / `components.securitySchemes` |
| `corepack yarn openapi:bundle [modules…]` | Redocly-bundle `openapi.yaml` → `dist/openapi.yaml` |
| `corepack yarn openapi:lint [modules…]` | `redocly lint` the bundles (structure errors fail, style rules warn) |
| `corepack yarn openapi:check [modules…]` | `build --check` + `lint`: exit non-zero if anything is stale or invalid (CI) |
| `corepack yarn catalog:new <module> [options]` | scaffold a new module (see §3 below) |

```bash
corepack yarn openapi:build                          # every module
corepack yarn openapi:build creed-payment            # just one
corepack yarn openapi:build creed-payment creed-order
```

An unknown module name is an error that lists the available ones, never a silent no-op.

## Scripts (`scripts/`)

Node scripts that build the OpenAPI documents. They are not part of Backstage itself; run them through
the `package.json` commands rather than directly.

| File | Command | What it does |
|---|---|---|
| `openapi-combine.mjs` | `openapi:combine` | reads a module's `paths/*.yml` and writes one `$ref` per path (plus the security schemes) into its `openapi.yaml`; fails on a duplicate path |
| `openapi-bundle.mjs` | `openapi:bundle` | runs Redocly CLI to merge `openapi.yaml` and everything it references into `dist/openapi.yaml` — the file the catalog reads with `$text` |
| `openapi-lint.mjs` | `openapi:lint` | runs `redocly lint` on `dist/openapi.yaml` with `redocly.yaml`: structure errors fail, style issues warn |
| `openapi-build.mjs` | `openapi:build`, `openapi:check` | combine, then bundle — the one command after an edit. With `--check` it writes nothing and only reports; `openapi:check` adds lint and is what CI runs |
| `catalog-new.mjs` | `catalog:new` | scaffolds a module: `catalog-info.yaml`, `openapi.yaml`, a starter `/ping` fragment, the `all.yaml` entry, then builds it |
| `lib/modules.mjs` | — | shared by all of the above: lists the modules under `catalog/`, parses module names and `--check`, rejects an unknown module name |

```
paths/*.yml ──combine──▶ openapi.yaml ──bundle──▶ dist/openapi.yaml ──lint──▶ valid?
                         (list of $refs)          (single file, committed)
```

Day to day you need two: `corepack yarn openapi:build <module>` after an edit, and
`corepack yarn openapi:check` before committing.

## How an API definition is built

**Every file in `paths/` is a whole OpenAPI document** — e.g. `creed-env-matrix/paths/api-example.yml`
— with its own `components` that its operations reference as `#/components/schemas/...`.

**1. Combine.** `openapi.yaml` lists their paths: one `$ref` per path, pointing **into** the file that
defines it.

```yaml
paths:
  /users/{userId}:
    $ref: ./paths/api-example.yml#/paths/~1users~1{userId}     # '/' is escaped as ~1 (RFC 6901)
```

OpenAPI allows a `$ref` per path item, never one for the whole `paths` object, so the list is one
line per path — generated, never hand-written. `scripts/openapi-combine.mjs` fails if two files
define the same path (or the same security scheme differently) and rewrites only `paths:` and
`components.securitySchemes`; comments, `info`, `servers` and `security` stay as written. It uses the
highest `openapi` version among the files and warns if they differ. A fragment's own `info`,
`servers`, `tags` and top-level `security` are not carried over: the entry document's apply.

**2. Bundle.** `scripts/openapi-bundle.mjs` runs `redocly bundle`, which follows every `$ref` and
writes `dist/openapi.yaml`, prepends a "GENERATED — do not edit" header, and fails if an external
`$ref` survives. Fragment schemas are hoisted into the bundle's `components`; when two files both
define `User`, Redocly keeps both under distinct names.

**3. Lint.** `scripts/openapi-lint.mjs` runs `redocly lint` on the bundles with `redocly.yaml`
(`extends: minimal`). This is what catches YAML that parses but means something else — the classic
being an unquoted `, ` inside a flow mapping: `{ description: Unknown id, or gone }` becomes a
`description` plus a key called `or gone`.

**4. Read.** The API entity's definition is

```yaml
definition:
  $text: ./dist/openapi.yaml
```

`$text` inserts the file verbatim, so what Swagger UI shows is exactly the reviewed file in the repo,
and the backend needs no OpenAPI module. Pointing `$text` at `./openapi.yaml` would hand Swagger UI
unresolved `$ref`s.

**`dist/openapi.yaml` must be committed.** It is excluded from the repo-wide `dist` ignore by
`!/catalog/*/dist/` in `.gitignore`. `bitbucket-pipelines.yml` (repo root) keeps it honest: on a pull
request it runs `openapi:check`; on `master` / `master-spring-boot-3` it rebuilds every module and
pushes the result back with `[skip ci]`.

## Step by step

All commands run in `creed-backstage/`. Whatever the change, the commit always holds the edited
source **and** the regenerated `openapi.yaml` / `dist/openapi.yaml` — CI rejects a pull request
where they disagree.

### 1. Change an existing API

1. Edit a fragment, e.g. `catalog/creed-payment/paths/payments.yml` (summaries, schemas, tags, …).
   Edit `info` / `servers` / `security` in that module's `openapi.yaml` — never generated.
2. Rebuild that module and check it:
   ```bash
   corepack yarn openapi:build creed-payment
   corepack yarn openapi:check creed-payment      # the same check CI runs, for this module
   ```
3. Look at it: with `corepack yarn start` running, open the API's page → **Refresh** (or wait for the
   next catalog pass), then the **Definition** tab.
4. Commit the fragment, `openapi.yaml` and `dist/openapi.yaml` together.

### 2. Add an API file to a module

1. Put a **complete** OpenAPI document (`openapi`, `info`, `paths`, `components`) into
   `catalog/<module>/paths/<name>.yml`. Give each operation a `tags:` entry — that is what Swagger
   UI's *Filter by tag* box filters on. Write each path in full (`/api/payment/{id}`) with the
   module's `servers:` URL at the host root: Spring Boot 3 does not match a trailing slash, so a
   server ending in `/api/payment` plus a path `/` would call `/api/payment/` and 404.
2. Run steps 2–4 of §1. `combine` fails if the new file defines a path another file in the module
   already has, or a security scheme with the same name but a different definition.

### 3. Add a new module

```bash
corepack yarn catalog:new creed-inventory \
  --title Inventory \
  --description "Stock levels per warehouse" \
  --component creed-resource-inventory \
  --server https://localhost:18097
```

| Option | Default |
|---|---|
| `--title` | from the name: `creed-inventory` → `Inventory` |
| `--description` | `<title> — functional module` |
| `--component` | `creed-resource-<short>` (`<short>` = the name without `creed-`) |
| `--server` | `http://localhost:8080/api/<short>` |
| `--owner` | `group:default/creed-platform` |

It writes `catalog/creed-inventory/` — `catalog-info.yaml` (System `creed-inventory`, Component
`creed-resource-inventory`, API `creed-inventory-api`, all tagged `inventory`), `openapi.yaml`, a
starter `paths/inventory-health.yml` (`GET /ping`) — adds the module to `catalog/all.yaml` (comments
kept), and builds `dist/openapi.yaml`. It refuses to overwrite an existing directory. Then:

1. Replace the starter fragment with the real endpoints (§2); add `security:` to `openapi.yaml` if the
   API needs a token.
2. `corepack yarn openapi:build creed-inventory && corepack yarn openapi:check creed-inventory`.
3. Commit `catalog/creed-inventory/` (with `dist/`) and `catalog/all.yaml`.

By hand it is the same: the three files plus a line in `all.yaml`'s `targets`, then `openapi:build`.

### 4. One-time Bitbucket setup

1. Make sure the repository is on Bitbucket and **Repository settings → Pipelines → Settings →
   Enable Pipelines** is on. `bitbucket-pipelines.yml` must stay at the repository root.
2. If `master` or `master-spring-boot-3` has branch restrictions, **Repository settings → Branch
   restrictions** → add **Bitbucket Pipelines** to the users allowed to write; otherwise the
   regenerate step fails at `git push`.
3. Open a pull request that touches `creed-backstage/catalog/**` and check that the step
   *OpenAPI bundle is up to date* runs and passes.

### 5. What CI does

The pipeline runs only when `creed-backstage/catalog/**`, `creed-backstage/scripts/**` or
`creed-backstage/redocly.yaml` changed, installs only the root workspace (`yarn workspaces focus
root`), and always covers **every** module.

| Trigger | Step | Result |
|---|---|---|
| Pull request | `yarn openapi:check` | red if any module's `openapi.yaml` / `dist/openapi.yaml` is stale or missing, or a bundle fails lint |
| Push to `master` / `master-spring-boot-3` | `openapi:build` + `openapi:lint`, then commit | if anything changed, a commit `catalog: regenerate OpenAPI bundle [skip ci]` is pushed to the same branch; `[skip ci]` keeps it from triggering another run |

**A red pull-request check:** run `corepack yarn openapi:build`, fix whatever `openapi:lint` reports,
commit, push.

### 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `✗ … is out of date` | run `openapi:build` (for that module or all), commit the result |
| `no catalog module 'x' — available: …` | typo, or the directory has no `openapi.yaml` |
| `external $ref left after bundling` | a `$ref` points at a file Redocly could not inline — paths are relative to the file containing them |
| `path … is defined in both paths/a.yml and paths/b.yml` | two fragments of one module define the same path; keep it in one file |
| `security scheme … differs between …` | two fragments define a scheme with one name but different settings; make them identical or rename one |
| `not a complete OpenAPI document` | a `paths/*.yml` lacks `openapi:` or `paths:` — fragments are whole documents |
| lint `struct` error, `Property 'or …' is not expected here` | an unquoted `, ` inside `{ … }` — quote the value: `{ description: 'a, b' }` |
| Definition tab still shows the old version | the catalog has not re-read the entity — **Refresh** on the entity page |
| A new module does not appear | it is missing from `catalog/all.yaml`'s `targets`, or the catalog has not refreshed the `creed-catalog` location yet |
| Swagger UI shows unresolved `$ref`s | `catalog-info.yaml` points `$text` at `openapi.yaml` instead of `./dist/openapi.yaml` |
| Pipeline fails at `git push` | branch restrictions — see §4 step 2 |
| `dist/openapi.yaml` not picked up by `git add` | the `!/catalog/*/dist/` line was removed from `.gitignore` |

## Why the catalog is served over HTTP

`app-config.yaml` registers the catalog as
`url: http://localhost:7007/api/catalog-files/all.yaml`, not as a `type: file` location. Backstage
resolves a placeholder's relative path with `new URL(path, location.target)` and reads it through
its URL readers. A file location's target is a bare filesystem path, so the URL cannot be built, and
there is no URL reader for local files — `$text: ./dist/openapi.yaml` would fail.

`packages/backend/src/catalogFiles.ts` therefore serves `catalog/` read-only at
`/api/catalog-files/*` when `creed.catalogFiles.directory` is set, and `backend.reading.allow` lets
the catalog fetch exactly that path. Edits show up at the catalog's next refresh.

| Config | `creed.catalogFiles.directory` |
|---|---|
| `app-config.yaml` (`yarn start`) | `../../catalog` — this checkout (relative to `packages/backend`) |
| `+ app-config.production.yaml` (the image) | `./catalog` — copied into the image by `packages/backend/Dockerfile` |
