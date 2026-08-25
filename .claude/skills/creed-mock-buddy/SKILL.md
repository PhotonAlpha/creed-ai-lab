---
name: creed-mock-buddy
description: The creed-mock-buddy module — a config-driven mock API server (Fastify 5 + TypeScript, Node ≥22) on HTTP 18100, serving YAML-defined routes with scenario variants, latency/fault injection, auto-generated CRUD collections, a /__admin control plane and OpenAPI docs. Standalone — outside the OAuth2 mesh and outside the Maven reactor. Use when working on mock definitions, the template engine, the registry/reload model, collections, the admin API, or this module's Fastify wiring.
---

# creed-mock-buddy

Config-driven **mock API server**. Fastify 5 + TypeScript, Node ≥ 22, plain HTTP `18100`, no context
path. **Not part of the OAuth2 mesh** (no HTTPS listener, no mTLS, no config server, no JWT) and
**not in the root `pom.xml`** — it is a Node module, the second in this repo alongside
`creed-env-matrix-design`. Run it with `npm run dev`; nothing else needs to be up.

`mocks/` ships mirrors of `creed-resource-catalog` / `-order` / `-payment` so a frontend or an
integration test can run without a JDK or the PKI. See [[creed-env-matrix]] for the repo's frontend
and [[creed-platform]] only for the wider mesh it imitates — this module shares no build tooling with
either. User-facing docs: `README.md`. Current state and open items: `HANDOFF.md`.

## Layout (`src/`)

- **`config.ts`** — every externally-varying value as `CREED_MOCK_*` with an inline fallback,
  matching the repo's `${CREED_FOO:fallback}` convention. Read once into a frozen `config` object.
- **`app.ts`** — `buildApp(options)` builds a Fastify instance over a `MockRegistry`. Tests call it
  with `{ mocksDir, scenario, logger: false, docs: false }` and drive it with `app.inject()`, so the
  suite binds no port. **The `await app.register(...)` ordering is load-bearing** — see Landmines.
- **`main.ts`** — listen + signal handling. Shutdown has a 10s force-exit timer, because a handler
  wedged on an injected 30s delay would otherwise hold the process until an orchestrator SIGKILLs it
  and cuts in-flight writes anyway.
- **`mock/definition.ts`** — the zod schema for a definition file. `strictObject` throughout, so an
  unknown key is an error naming the key, not a silently ignored line.
- **`mock/loader.ts`** — walks the directory (recursive, extension-filtered, name-sorted for
  deterministic load order), parses, validates, rejects duplicate module names. Empty files are
  skipped rather than failing.
- **`mock/template.ts`** — the compiler. See below.
- **`mock/registry.ts`** — `MockRegistry`, the one mutable object. Variant table keyed by
  `"METHOD /path"`, active scenario, collection bindings, per-route stats and `seq` counters.
- **`mock/register.ts`** — turns the registry into Fastify routes: `registerMockRoutes` (one handler
  per key) and `registerCollections` (six routes per collection).
- **`mock/collection.ts`** — `CollectionStore`, in-memory CRUD.
- **`routes/admin.ts`**, **`routes/health.ts`**, **`plugins/*`** — control plane, probes, request
  context / error handler / swagger.

## The design that everything else hangs off

**Compile once at load time, render nothing you don't have to.**

`template.ts` walks a response body into a tree of `Compiled` nodes. Any subtree containing no
`{{ }}` tokens **collapses back into a plain value** and is marked `isStatic`. `registry.prepareRoute`
then pre-serialises a fully-static body into a `staticPayload` string, and the request path writes
that string straight out — zero rendering, zero `JSON.stringify`. `GET /__admin/routes` exposes which
routes got that treatment as `precomputed: true`, and `npm run bench` exists to show the gap.

The corollary: **unknown expressions throw at load time**, not at 3am as an `undefined` in a payload.

**Handlers hold a key, not a route.** `makeMockHandler(registry, key)` closes over the string key and
calls `registry.resolve(key)` per request. That indirection is what makes scenario switching and
`POST /__admin/reload` work on a live server without touching Fastify's router.

## Templating rules worth knowing

- A string that is **exactly one token** keeps the expression's native type (`"{{int 1 9}}"` → a JSON
  number). A token embedded in text interpolates as a string.
- A missed lookup renders as `""` when interpolated, `undefined` when it is the sole token — and
  `applyDynamicHeaders` drops a header that rendered `undefined` rather than sending the literal
  string `"undefined"`.
- `headers.*` lookups are lowercased at compile time, because Node lowercases inbound header names
  and the YAML author will not.
- `$repeat`/`$each` gives each element `ctx.seq + i`, so repeated rows differ. A fixed-count
  `$repeat` of a static `$each` stays static.
- **Response headers go through the same compiler as bodies.** Static header values collapse into a
  plain map applied in one `reply.headers()` call; only genuinely templated ones are rendered per
  request. If you add a third place that accepts author-supplied strings, run it through `compile()`.

## Landmines

- **Fastify's router is frozen after `listen()`.** `reload()` can change bodies, statuses, headers,
  delays, faults and scenario variants in place, but **adding or removing a path needs a restart**.
  `reload()` diffs registered keys against the new set and returns `pendingRestart`; `npm run dev`
  restarts on any change under `mocks/`. Do not try to re-register routes at runtime.
- **`@fastify/swagger` hooks `onRoute`, which only fires for routes registered after it.** The
  registration order in `app.ts` (swagger → health → admin → mocks) is why `/docs` is populated.
  Moving swagger below the routes silently empties it, with no error.
- **Mock routes deliberately carry no `schema.response`.** Fastify would compile it with
  fast-json-stringify and drop every field the schema doesn't mention — data loss on arbitrary
  author-written bodies. The `staticPayload` path is where the speed actually comes from.
- **Explicit `HEAD` on a path that also has `GET`** collides with Fastify's automatic HEAD-for-GET
  (`FST_ERR_DUPLICATED_ROUTE`, boot failure). `registerMockRoutes` pre-scans for that and sets
  `exposeHeadRoute: false` on the affected GETs.
- **`requestStore.run(context, done)` must call `done()` *inside* `run()`** — returning before it
  drops the `AsyncLocalStorage` context for every later hook, the handler and the pino `mixin`, and
  the symptom is silently unstamped log lines rather than an error.
- **One `TemplateContext` per request, built lazily** (`const context = () => (ctx ??= …)`). Headers
  and body both render from it and it consumes a `seq`: a second context would double-count the
  counter, an eager one would burn a `seq` on fully static routes.
- **`ignoreTrailingSlash` lives under `routerOptions`**, not at the top level — the top-level spelling
  is deprecated and goes away in fastify@6.
- **Collection state survives a reload only if the id field is unchanged**, so an edited seed is
  actually visible. That is deliberate; an author editing YAML wants to see the edit.
- **`CREED_MOCK_CHAOS=false` for anything that measures the server**, or you measure `setTimeout`.
- under-pressure's thresholds are deliberately generous — a mock returning 503 under a load test is a
  worse failure mode than a slow response. `/ready` surfaces the same signals so a test can watch it
  degrade.

## Conventions

- **Tests never bind a port and never touch the network** — `buildTestApp()` builds over
  `tests/fixtures/mocks/` and drives `app.inject()`. Keep fixtures deterministic; assert on ranges
  and membership, not on values a random generator produced.
- Comments explain **why**. Most of the ones in `src/` document one of the landmines above.
- New behaviour that an author can express in YAML needs: the zod schema, the compile step, the
  request path, a fixture route in `tests/fixtures/mocks/demo.yaml`, a test, and a README row.
