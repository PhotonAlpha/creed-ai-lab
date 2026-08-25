# creed-mock-buddy — handoff

**Purpose** Config-driven mock API server (Fastify 5 + TypeScript). YAML-defined routes with
scenario variants, latency/fault injection and auto-generated CRUD collections. **Outside the OAuth2
mesh and outside the Maven reactor** — plain HTTP, no mTLS, no config server, no JDK.
**Skill** `creed-mock-buddy` · user docs: `README.md`

## Run

```bash
npm install
npm run dev          # http://localhost:18100 — nodemon restarts on any change under src/ or mocks/
open http://localhost:18100/docs
```

HTTP `18100`, no context path. Runs fully standalone — nothing else in the repo needs to be up. The
second Node module in this repo alongside `creed-env-matrix-design`; it is not in the root `pom.xml`.

```bash
npm test         # 72 tests, in-process via app.inject() — no port, no network, no fixtures on disk
npm run typecheck
npm run bench    # autocannon; spawns its own server on :18199 with chaos off
```

## Current state

Complete and green: `npm test` 72 passing across 5 files, `npm run typecheck` clean.

- **Definition loading** (`src/mock/loader.ts`, `definition.ts`) — every `.yaml`/`.yml`/`.json` under
  `mocks/` is one module, validated by zod at load time. Bad file ⇒ the process refuses to boot, with
  the source file, the route key and the reason.
- **Template engine** (`src/mock/template.ts`) — `{{ }}` expressions (`uuid`, `now`, `seq`,
  `int`/`float`/`pick`, and `params`/`query`/`headers`/`body` lookups) plus `$repeat`/`$each`.
  Compiled **once at load time** into a tree of `Compiled` nodes; a subtree with no tokens collapses
  back to a constant.
- **Registry** (`src/mock/registry.ts`) — the single mutable object: variant table, active scenario,
  collection stores, per-route counters. Handlers hold only a route *key* and consult it per request,
  which is what lets `POST /__admin/reload` swap definitions under a live server.
- **Collections** (`src/mock/collection.ts`) — in-memory CRUD with filter/sort/paginate, insertion
  order preserved, `409` on duplicate id, seed restorable via `/__admin/state/reset`.
- **Admin API** (`src/routes/admin.ts`) — modules, routes, collections, scenario, reload, stats,
  state reset, effective config. All of it appears in `/docs`.
- **Shipped mocks** (`mocks/`) — `catalog`, `order`, `payment`, mirroring the three resource servers
  including the payment lifecycle and a 15%-failure export route.
- Request context: inbound `x-request-id` reused, W3C `traceparent` parsed, both carried through an
  `AsyncLocalStorage` into the pino `mixin` so every line inside a handler is stamped.

## Landmines

- **Fastify's router is frozen after `listen()`.** Reload can change bodies, statuses, headers,
  delays, faults and variants, but adding or removing a *path* needs a restart. `reload()` diffs the
  registered keys against the new ones and returns them as `pendingRestart` — don't "fix" this by
  re-registering routes at runtime.
- **`@fastify/swagger` collects specs through an `onRoute` hook**, which only fires for routes added
  *after* the hook exists. The `await app.register(...)` ordering in `src/app.ts` is load-bearing;
  moving the swagger registration below the route registrations silently empties `/docs`.
- **No `schema.response` on mock routes, on purpose.** Fastify would compile it with
  fast-json-stringify and silently drop every field the schema doesn't mention — for arbitrary
  user-authored bodies that is data loss, not an optimisation. The serialisation win comes from the
  precomputed static payload instead.
- **A YAML file may declare `HEAD` for a path that also has `GET`.** Fastify's automatic HEAD-for-GET
  would then collide (`FST_ERR_DUPLICATED_ROUTE`) and refuse to boot, so `registerMockRoutes` sets
  `exposeHeadRoute: false` on such GETs.
- **`requestStore.run(context, done)` must call `done()` *inside* `run()`** (plugins/request-context.ts).
  Returning before `done()` drops the context for every later hook, the handler and the log mixin.
- **One `TemplateContext` per request, minted lazily.** Response headers and the response body both
  render from it, and it consumes a `seq`. Building a second context for the headers would
  double-count the counter; building one eagerly would consume a `seq` on fully static routes.
- Boot with `CREED_MOCK_CHAOS=false` for anything measuring the server — otherwise a benchmark
  measures `setTimeout`.
- under-pressure's thresholds are deliberately generous. A mock server returning 503 because a load
  test saturated it is a worse failure mode than a slow response.

## Open items

- **Not committed to git.** The whole directory is still untracked on `master-spring-boot-3`.
- Response *header* templating was added after the initial build (`registry.prepareRoute` compiles
  header values; `applyDynamicHeaders` renders them). Before that, `mocks/order.yaml`'s
  `location: "/api/order/orders/{{params.id}}"` was sent literally. If you add a third place that
  accepts author-supplied strings, run it through `compile()` too.
- No request *matching* beyond method + path: a route cannot yet key off a header, a query value or
  a body field. Scenarios cover most of what that would be used for.
- Collections have no relations, no validation and no server-side generated fields beyond the id.
- `Dockerfile` / compose entry not written; the module is run from source or from `dist/`.
