# creed-mock-buddy

A config-driven mock API server. You describe endpoints in YAML; it serves them over HTTP with
scenario switching, latency and fault injection, auto-generated CRUD collections and an OpenAPI UI.

Built for this repo's own resource servers — `mocks/` ships mirrors of `creed-resource-catalog`,
`creed-resource-order` and `creed-resource-payment` — so a frontend or an integration test can run
without a JDK, a PKI or a config server. Nothing about it is repo-specific, though: point it at a
different `mocks/` directory and it mocks something else.

**Fastify 5 · TypeScript · Node ≥ 22.** No database, no build step in development.

## Quick start

```bash
npm install
npm run dev                                  # http://localhost:18100, restarts on any change

curl http://localhost:18100/api/catalog/products
curl http://localhost:18100/api/catalog/search?q=widget
open  http://localhost:18100/docs            # OpenAPI UI for every loaded mock
```

| Script | What it does |
|---|---|
| `npm run dev` | nodemon + tsx, watching `src/` and `mocks/` |
| `npm run build` | tsup → `dist/` (ESM, deps left external) |
| `npm start` | run the built server |
| `npm test` | vitest, in-process via `app.inject()` — no port, no network |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run bench` | autocannon throughput comparison (see [Benchmark](#benchmark)) |

## Defining mocks

Every `.yaml` / `.yml` / `.json` file under `mocks/` (recursively) is one **module**. A module has a
`name`, an optional URL `prefix`, and any number of `routes` and `collections`.

```yaml
name: catalog
prefix: /api/catalog
description: Mirrors creed-resource-catalog.

routes:
  - id: catalog-categories          # optional; defaults to "METHOD path"
    method: GET                     # defaults to GET
    path: /categories
    summary: Static category list   # shows up in /docs
    response:
      status: 200                   # defaults to 200
      headers:
        x-mock-source: catalog
      body:
        - { code: hardware, label: Hardware }
```

Definitions are validated with zod **at load time**. A typo — an unknown key, a path without a
leading slash, an expression the template engine doesn't know — fails the boot with the file, the
route and the reason. A mock server that silently ignores half its config is worse than one that
won't start.

### Templating

Any string in a response `body` **or** a response header may contain `{{ … }}` tokens.

| Expression | Renders |
|---|---|
| `{{uuid}}` `{{now}}` `{{today}}` `{{timestamp}}` | a v4 uuid · ISO-8601 instant · `YYYY-MM-DD` · epoch ms |
| `{{seq}}` | per-route counter, `1` on the first request |
| `{{requestId}}` | this request's correlation id |
| `{{int 1 99}}` `{{float 5 500 2}}` `{{bool}}` | random number in an inclusive range · with N decimals · coin flip |
| `{{pick IN_STOCK LOW_STOCK BACKORDER}}` | one of the listed choices (quote a choice containing spaces) |
| `{{params.id}}` `{{query.q}}` `{{headers.x-tenant}}` `{{body.customer.name}}` | request lookups, nested paths allowed |

A string that is **exactly one token** keeps the expression's native type — `"{{int 1 9}}"` is a JSON
number, not a string. A token embedded in text interpolates as a string. A lookup that misses renders
as `""` when interpolated, and as `undefined` (i.e. the field or header is omitted) when it stands
alone.

`$repeat` / `$each` expands into an array. Each element sees its own index as `{{seq}}`:

```yaml
items:
  $repeat: { min: 2, max: 5 }     # or a fixed integer
  $each:
    id: "{{uuid}}"
    rank: "{{seq}}"
    price: "{{float 5 500 2}}"
```

### Latency and faults

```yaml
delay: { min: 10, max: 60 }   # or a fixed number of ms; settable per module and per route
fault:
  rate: 0.15                  # 0..1 — fraction of calls that fail instead of responding
  status: 502
  body: { error: export worker unreachable }
```

A route's `delay` overrides its module's. Set `CREED_MOCK_CHAOS=false` to disable both globally —
benchmarks and CI usually want that.

### Scenarios

A route carrying `scenario: outage` is served only while that scenario is active; a route without one
is the fallback. Switching is instant and needs no restart or reconnect:

```bash
curl -X PUT localhost:18100/__admin/scenario -H 'content-type: application/json' -d '{"name":"outage"}'
curl localhost:18100/api/catalog/search?q=widget        # 503 catalog unavailable
curl -X PUT localhost:18100/__admin/scenario -H 'content-type: application/json' -d '{"name":"default"}'
```

The shipped mocks define `outage`, `slow` and `declined`. A path that *only* exists in a scenario
404s while that scenario is inactive, with a message saying so.

### Collections

A `collection` generates a full REST resource — `GET`/`POST` on the path, `GET`/`PUT`/`PATCH`/`DELETE`
on `{path}/:id` — backed by an in-memory store seeded from the YAML:

```yaml
collections:
  - name: products
    path: /products
    idField: id
    seed:
      - { id: 1, sku: CRD-1001, name: Widget, price: 9.99 }
```

`GET` on the list path supports `_page`, `_limit`, `_sort`, `_order`; **any other query parameter is
an equality filter** on that field. New ids are numbers when every seeded id is a number, uuids
otherwise. `POST` of an id that already exists is a `409`, not a silent overwrite.

Writes are process-local and last until `POST /__admin/state/reset`, which restores every collection
to its seed — call it between test cases.

## Admin API

Mounted at `/__admin` (`CREED_MOCK_ADMIN_PREFIX`), and documented in `/docs` alongside the mocks.

| | |
|---|---|
| `GET /__admin/modules` | loaded definition files, with route and collection counts |
| `GET /__admin/routes` | every route, its scenario variants, and whether its body is precomputed |
| `GET /__admin/collections` | generated collections and their current row counts |
| `GET`/`PUT /__admin/scenario` | read / switch the active scenario |
| `POST /__admin/reload` | re-read `mocks/` from disk without restarting |
| `GET`/`DELETE /__admin/stats` | per-route hits, faults, avg/max latency · reset |
| `POST /__admin/state/reset` | restore every collection to its seed |
| `GET /__admin/config` | effective runtime configuration |

`POST /__admin/reload` applies changed bodies, statuses, headers, delays, faults and variants in
place. **Adding or removing a *path* needs a process restart** — Fastify freezes its router on
`listen()` — and the response says so in `pendingRestart`. `npm run dev` restarts for you. A reload
that fails validation returns `422` and keeps the previous definitions loaded.

`GET /health` and `GET /ready` sit outside the admin prefix; `/ready` reports event-loop delay and
heap so a load test can watch the server go unhealthy before it starts shedding.

## Configuration

Every value has a `CREED_MOCK_*` override and an inline fallback, matching the `${CREED_FOO:fallback}`
convention used on the Java side of this repo.

| Variable | Default | |
|---|---|---|
| `CREED_MOCK_HOST` / `CREED_MOCK_PORT` | `0.0.0.0` / `18100` | listen address |
| `CREED_MOCK_DIR` | `mocks` | definition directory, resolved against the cwd |
| `CREED_MOCK_SCENARIO` | `default` | scenario active at boot |
| `CREED_MOCK_CHAOS` | `true` | master switch for delay + fault injection |
| `CREED_MOCK_ADMIN_PREFIX` | `/__admin` | admin mount point |
| `CREED_MOCK_DOCS` / `CREED_MOCK_DOCS_PATH` | `true` / `/docs` | OpenAPI UI |
| `CREED_MOCK_LOG_LEVEL` / `CREED_MOCK_PRETTY_LOGS` | `debug` / `true` in dev | pino level and pretty printing |
| `CREED_MOCK_SLOW_MS` | `500` | threshold for the "slow request" warning |
| `CREED_MOCK_BODY_LIMIT` | `5 MiB` | max request body |
| `CREED_MOCK_MAX_EVENT_LOOP_DELAY` / `CREED_MOCK_MAX_HEAP` | `2000` ms / `1 GiB` | load-shedding thresholds |

## Observability

An inbound `x-request-id` is reused as the correlation id rather than replaced, and a W3C
`traceparent` is parsed so `traceId` / `spanId` land on every log line the request produces —
including lines emitted deep inside a handler, via an `AsyncLocalStorage` context. Both are echoed
back as `x-request-id` and `x-trace-id`.

## Benchmark

```bash
npm run bench                                        # 100 connections, 10s per route
BENCH_DURATION=20 BENCH_CONNECTIONS=200 npm run bench
```

Spawns its own server with chaos off and logging quiet, then walks a set of routes. The column worth
watching is the gap between the static and templated rows: a body with no tokens is compiled and
serialised **once, at load time**, so its request path does no rendering and no `JSON.stringify` — it
writes bytes. `GET /__admin/routes` reports which routes get that treatment as `precomputed: true`.
