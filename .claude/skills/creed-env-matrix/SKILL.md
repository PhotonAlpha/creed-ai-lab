---
name: creed-env-matrix
description: The Env Matrix Viewer feature — creed-resource-env-matrix (the only DB-backed resource server; PostgreSQL + Flyway, host/ip/port conflict detection, mockable health probe) and creed-env-matrix-design (the repo's only frontend; Vite + React 19 + antd 5 + Ant Design Pro, plus a node mock API and an AntV G6 topology graph). Use for endpoint/conflict/health logic, the Flyway schema or seed data, the dev-profile HTTP port, the topology graph, or any React/antd/Pro/G6 work in this repo.
---

# creed-env-matrix

Environment **host / ip / port** mapping matrix — a conflict-hunting tool. Two halves that share one contract:

- **`creed-resource/creed-resource-env-matrix`** — resource server, the **only module backed by a real database**.
- **`creed-env-matrix-design`** — the **only frontend in the repo**. Requirements outline lives in its `CLAUDE.md`.

See [[creed-platform]] for SSL bundles, the HTTPS listener and build/run basics. Structural sibling of [[creed-resource-catalog]]/[[creed-resource-order]]/[[creed-resource-payment]], but DB-backed rather than in-memory.

## Data model — the seven-dimension identity

`(appSystem, tier, envInstance, country, service, instance, scheme)` → one `host` / `ip` / `port`.

- **`scheme` is part of the identity**, not a duplicate: one service legitimately exposes both http and https. Enforced by `ux_env_endpoint_dimensions` (declared twice on purpose — in `V1__create_env_endpoint.sql` and on `@Table(uniqueConstraints=…)`, so the ddl-auto schema the tests use enforces the same rule).
- Dimension values are **plain text, not enums**. `GET /dimensions` derives the UI's filter options from values actually present, so a new country or `UAT6` extends the dropdowns with no code change. Adding values still requires syncing §2 of `creed-env-matrix-design/CLAUDE.md` (that file's own rule).
- `@Version` optimistic lock — the config page saves whole rows.

## Release topology (`env_release*`, `service/ReleaseService`)

The **second half of the module**, and the only relationship data in it. `env_endpoint` records
addresses; nothing in it says "A calls B", and nothing derived from a host/port ever can.

**A topology node is a slice, not an app system.** This was learned the expensive way: the first
attempt keyed a connection on `(tier, sourceApp, targetApp)` and could not hold

```
SG CCS SIT3  ->  Global-CCS SIT2  ->  CN CCS SIT5
```

because CCS appears twice. So:

| Table | Holds |
|---|---|
| `env_release` | name (unique), tier, status `DRAFT`/`ACTIVE`/`ARCHIVED` |
| `env_release_node` | a **participant** — `(appSystem, country, envInstance)` within one release, plus `layer` / `sort_order` (where the graph draws it) |
| `env_release_link` | a connection between two participants, plus `direction` |

A release is what says which slices belong together, which is also what keeps every other dimension
orthogonal — country, envInstance, service and instance stay plain data.

- **`country = '*'` means "not country-specific", and is NOT NULL on purpose.** Postgres lets
  several NULLs through a unique index, so the identity would need a `coalesce()` expression index —
  and `@UniqueConstraint` cannot express one, leaving the H2 test schema without the constraint.
- **`release.tier` is a label, never validated against the participants.** A promotion chain
  legitimately spans tiers; the UI warns, the API does not reject.
- **`env_release_link.release_id` is redundant but load-bearing** — the identity index needs it, and
  it is what the service checks both ends against so a link cannot stitch two releases together.
- **`layer` is nullable, and `null` is "derive it from the links"** (`V5`). The graph ranks
  participants by a longest path over the links; a number here overrides that ranking for that one
  participant. `0` cannot be the unset value — it means column 0, so a link added later that ought to
  push the box right would silently disagree with a number nobody chose. `sort_order` is NOT NULL
  default 0. Both are range-checked in `V5` **and** per row in `ReleaseService.validate`, because
  this contract answers a bad row with a 422 that names it, not a 400 for the whole release.
- **`direction` decides arrowheads only.** The stored `source -> target` orientation is what the
  layered view ranks on; counting a `BIDIRECTIONAL` link both ways makes every such pair a cycle.
- **No foreign key to `env_endpoint`, deliberately.** A participant may name a slice with no
  endpoints; the graph draws it as a placeholder, and that gap is what the viewer exists to surface.
- **`GET|PUT /releases/{id}/topology` saves participants and links together**, authoritative for
  that release only. A link may point at a participant created in the same payload with
  `{"ref": "..."}` — they are one graph, a link cannot exist without its ends, and "add a participant
  and connect it" is the commonest edit. Per-row routes would need two round trips and leave an
  orphan participant in between.
- **Children are deleted explicitly, not by the FK cascade** — the entities map the relationship as
  a plain id column, so the H2 test schema has no foreign key at all.
- Rejections mirror the endpoint side: `409` duplicate release name, `422` with per-row `issues`
  (each tagged `section: nodes|links`) for a topology save — validated in full before anything is
  written.

## Conflict detection (`service/ConflictDetector`)

Two keys checked independently: **`host:port`** (two endpoints on one listener) and **`ip:port`** (two hostnames, one address — the clash DNS hides).

- **Scope** = `env-matrix.conflict.scope`: `TIER_ENV` (default, bucket `tier/envInstance`) | `TIER` | `GLOBAL`. The default deliberately does *not* flag the same address reused across separate environments — that is what environments are for.
- Rows differing **only by `scheme` do collide**: one port cannot serve both.
- Overlapping groups are **deduped by member-id set**, preferring `HOST_PORT` (it names the thing an operator changes).
- Detection runs on the **filtered** set, so highlighting always explains itself from the rows on screen; narrowing to one side of a clash makes it vanish. Single-row reads (`GET /endpoints/{id}`) compare against the whole table instead, since a lone row could never conflict.
- Conflicts are **reported, never rejected** — recording a clash you just found is the point. Only duplicate *identities* are rejected (409).

## Health probe (`service/HealthProbeService`)

`env-matrix.health.mode` = `mock` (default) | `real`.

- **`mock`** touches no network: state is a pure function of `host:port` + a rotatable seed. The matrix describes environments this process cannot reach, so a real probe would return a uniform wall of `DOWN`. Keyed on the **address, not the id**, so two endpoints in a conflict report the same state.
- Mock states are **stable across calls by design** (a re-rolling state makes the grid flicker); `POST /health/recheck` rotates the seed for a visible, deterministic change.
- **`real`** is a plain TCP connect — proves something is listening, nothing more. The UI always shows the active mode.

## Batch save (`PUT /endpoints`)

The config page's "save": the whole table in one transaction.

- `deleteMissing: true` makes the payload authoritative — anything absent is deleted. **The UI must therefore always hold the complete, unfiltered set**; sending a filtered subset would delete whatever the filter hid. That is why the config page filters client-side only.
- All rows validated **before** anything is written; failure ⇒ `422` with per-row `issues` (indexed into the submitted array) and nothing written.
- Unchanged rows are skipped via `differs(...)`, so a one-field edit reports "1 updated", not "1235 updated". The node mock implements the same rule.

## Profiles & ports

| Profile | Port | Transport |
|---|---|---|
| `primary` / `secondary` | 18095 / 18096 | HTTPS (bundle `creed-env-matrix-server`) |
| `cloud` | 18095 | HTTPS, config from [[creed-config-server]] |
| **`dev`** | **3001** | **plain HTTP** — the port the mock also uses; not the proxy default |

- **`dev` and `test` exclude `SslObservabilityAutoConfiguration`** and disable the SSL health indicator. Boot's `SslMeterBinder` eagerly opens **every declared SSL bundle** at startup for certificate-expiry gauges, and there is **no property to disable it** — so `creed.https.enabled=false` is not enough; a missing keystore fails the whole context. This is the landmine to remember if you add a no-PKI profile to any module.
- Tests use H2 + ddl-auto (Flyway off — V1/V2 are PostgreSQL-specific) and must set `spring.application.name` + `server.port`, because the `actuator` profile is `include`d from `application.yml` and cannot be un-included by a profile-specific file; OTel interpolates both and an unresolvable placeholder fails the context.
- DB: `jdbc:postgresql://127.0.0.1:5432/env_matrix` on the `creed-artifactory-db` container. Create once: `docker exec creed-artifactory-db createdb -U artifactory env_matrix`.

## Frontend (`creed-env-matrix-design`)

Vite 8 + React 19 + **antd 5.29.3** + `@ant-design/pro-components` 2.8.10 + **`@antv/g6` 5.1.1**.
`npm run mock` serves the contract from `server/index.js` on `:3001` with no database.

**`npm run dev` does not talk to it by default.** The committed `.env` holds
`VITE_API_TARGET=https://localhost:18095`, so a plain `npm run dev` proxies `/api` at whatever
HTTPS instance of the module is running; reaching the mock (or the `dev` profile) needs
`VITE_API_TARGET=http://localhost:3001 npm run dev`. The failure this produces is nasty: the UI comes
up fully populated from the HTTPS backend, so a backend that was not restarted after a contract
change is indistinguishable from a broken frontend. Check the proxy target *before* debugging the
code. The value is read with `loadEnv` because a Vite config file runs *before* `.env` is loaded, so
`process.env.VITE_API_TARGET` is always `undefined` there; a shell-exported value still wins.

**Dependency constraints — do not "upgrade" past these without re-checking:**
- **`@antv/g6` 5.1.1**, added for the topology page — see that section for why not `@ant-design/graphs`.
- **antd 5, not 6.** pro-components 2.8.10 declares `antd: ^4.24.15 || ^5.11.2`; the antd-6-compatible Pro (`3.1.14-5`) is a pre-release.
- **`@ant-design/v5-patch-for-react-19` is required** and imported first in `main.tsx` — antd 5 targets React 16–18.
- **`path-to-regexp` override to 8.4.2** — pro-layout pins the ReDoS-affected 8.2.0 exactly.
- **`react-router-dom` stays at 7.18.1** despite one open advisory (RSC-mode CSRF, unreachable in a plain `BrowserRouter` SPA). Downgrading is worse: 7.11.0 carries 14 advisories 7.18.0 fixed.

**Two traps that hid other bugs — check these before trusting a green run:**
- **`tsc --noEmit` against the root `tsconfig.json` checks nothing.** It is solution-style
  (`files: []` + references) and `--noEmit` does not follow references, so it exits 0 on a codebase
  full of type errors. `npm run typecheck` is now `tsc -b --force`; `npm run build` always used it.
- **ProComponents ignores antd's `locale`** and keeps its own catalogue. `main.tsx` wraps the tree in
  `ProConfigProvider` with `enUSIntl`/`zhCNIntl`; without it Pro's placeholders and pagination stay
  Chinese while the rest of the UI is English.

**Bugs already found and fixed here — don't reintroduce:**
- **Stale-response race**: StrictMode's double mount fired an unfiltered request that resolved *after* the filtered one and overwrote the grid. Guarded with a request-id ref in `pages/Matrix/index.tsx`.
- **Per-row modals**: one `ModalForm` per table row lost its trigger state on cell re-render, so the first Edit click did nothing. Use **one page-level controlled modal**, never a per-row `trigger`.
- **`scroll={{x:'max-content'}}` + a `fixed:'right'` column** collapses the last scrolling column — use an explicit numeric width.
- `sticky` together with `scroll.y` renders a second, offset header. `scroll.y` alone already pins the header.

**Conventions**: one root `ConfigProvider` in `main.tsx` (+ `AntdApp` so `App.useApp()` gives themed `message`/`modal`); cell highlighting via `onCell`→`className` and antd tokens bridged to CSS variables — **no `.ant-*` overrides**; `zh-CN.ts` typed as `Record<keyof typeof enUS, string>` so a missing translation is a compile error. Per the repo's antd skill, query `antd info <Component> --format json --version 5.29.3` before writing component code.

## Topology graph (`pages/Topology`, `/topology`)

The filtered slice as a graph: one **card node per endpoint**, boxed into a G6 **combo per
participant**, and those combos nested inside one **combo per app system**. Built on **`@antv/g6` 5.1.1** — deliberately *not* `@ant-design/graphs`, whose React
wrapper drags in `styled-components@6` and `@antv/graphin` for a wrapper thin enough to write here,
in an app that already carries one React-19 compat shim. G6 declares no React peer dependency.

**The endpoint → graph derivation is written out in `creed-env-matrix-design/README.md`**
("How the graph is derived from the endpoints", mirrored in `README.zh-CN.md`): which element comes
from which table, the claim rule, the layering formula, and the fact that the join runs one way only.
`buildGraph.ts` is the only implementation of it — change one, change the other.

**Edges come from two different places, and the distinction is the whole design:**

| Kind | Where from |
|---|---|
| `dep` | **Declared** — one `env_release_link` row, from `/releases/{id}/topology` |
| `colo` / `alias` / `clash` | **Derived** from `/endpoints` (same `host`, same `ip`) and `/conflicts` |

The graph is scoped to a **release**, and the inner group box is a **participant**, not an app
system — the app-system box around it is a second, purely visual level (below).
Declared arrows are drawn combo to combo: an endpoint-level arrow would assert which endpoint calls
which, and nothing in the data supports that. The topology is fetched by release id alone —
narrowing by country filters the endpoints inside the boxes, never the wiring.

An endpoint is claimed by the **first participant whose slice matches, specific before wildcard**: a
release may declare `CCS/SG/SIT3` alongside `CCS/'*'/SIT3`, and without that ordering the wildcard
swallows every region. A participant with no matching endpoints gets a dashed **placeholder node**;
endpoints no participant claims are counted in a banner rather than drawn.

Derived edges **chain** rather than clique — five endpoints on one host is one fact, not ten lines.

**Column order is derived from the links, not declared in code.** `rankParticipants` is a
longest-path layering over the stored `source -> target` orientation, ignoring an edge that closes
back onto the current path so a user-created cycle cannot hang the walk.

**The app-system box is presentation only, and in the layered view it is keyed on *(app system,
layer)*.** A topology node is still a slice — CCS legitimately appears twice in one chain — so a
single box per app system would have to stretch across every column between its two appearances and
overlap whatever sits there. `TopoCombo` therefore carries both an `appGroupId` (layered) and a
`clusterGroupId` (cluster), and `TopologyGraph` picks by layout.

**Four orientations, one layout function.** `layOutLayered` works in **(rank, cross)** space — rank
is the hierarchy axis, cross is what participants stack along — and only `place()` maps that to x/y,
negating rank for `RL`/`BT` and swapping the axes for `TB`/`BT`. The card is 196 × 52, so the wrap
limit swaps with the axis too (`MAX_ROWS` horizontally, `MAX_LANE_NODES_VERTICAL` vertically).

**Layer pins and cross-axis order live on the release**, in `env_release_node.layer` /
`.sort_order`: a pin is a claim about the estate, so everyone opening the release sees it. Only what
does not change the picture's meaning — orientation, layout, the app-system boxes — stays in
`localStorage` (`useTopologyView`). A pin replaces that one participant's rank and leaves its
downstream where the links put it; re-deriving from a pin would shunt half the graph for a one-box
correction.

`LayerEditorModal` **stages edits and saves once**: the only write is the authoritative
`PUT /releases/{id}/topology`, so it resends every participant and link with just those two fields
changed — one request per keystroke would rewrite the release on every digit and leave half the table
applied on a rejection. **Every other writer of that route must resend the two fields too**, which is
why `ParticipantRow` in the config page's release editor carries them through without exposing them.
The editor's table sorts by app system, *not* by the layer being edited, or the row being typed into
jumps out from under the cursor.

**The graph runs no G6 layout at all.** `buildGraph.ts` assigns every node an x/y for both layouts
(`layered` = one band per rank, groups wrapping into lanes past the wrap limit; `cluster` = one band
per app system, shelf-packed). `combo-combined` was tried and overlaps the boxes into mush, and no
built-in layout knows the declared ranking. G6 skips the layout stage when `options.layout` is
undefined. A **circular** layout was also tried and dropped: nodes are 196px cards, so a ring of
forty endpoints is ~3000px wide and fit-to-view kills the labels.

**G6-in-React gotchas, all found in browser testing here:**

- **Event handlers outlive every render.** The canvas is built once, so hover/click callbacks must
  reach `model`/`onSelect`/`applyStates` through refs. Reading them directly froze the first
  render's *empty* model and hover did nothing at all.
- **`graph.render()` is async — serialize it.** `setData` during an in-flight render left the
  previous filter's cards on the canvas beside the new ones. Renders chain onto a promise ref, and
  `destroy()` waits on that same ref (otherwise G6 logs "The graph instance has been destroyed" on
  StrictMode's second mount).
- **`animation: false`.** `setElementState` during the enter tween froze nodes at partial opacity —
  the graph came up looking permanently dimmed.
- **Fit-to-view needs a frame.** ProCard is still sizing its body on first paint; a fit measured
  then leaves the graph half out of view. `requestAnimationFrame` + a `ResizeObserver` re-fit.
- **A custom node must fade its own shapes.** `Rect` subclass shapes appended in `render` ignore the
  node's `opacity`, so the card washed out while its text stayed crisp.
- **Gate zoom behind Ctrl.** Plain wheel-to-zoom swallows the page scroll on a page with a filter
  bar above the canvas.
- **`register` is global** — guard with `getExtension` so Vite HMR does not re-register the type.
- **Combo gutters are measured card to card and have to clear the boxes.** `GROUP_GAP` must fit two
  18px paddings plus the next participant's label; `APP_GROUP_GAP` two more paddings plus the
  cluster's own label. Below those numbers neighbouring boxes touch and every label is drawn on the
  border of the box before it — the same mush `combo-combined` produced, one level up.
- **A nested combo must share its parent's `fillOpacity`/`strokeOpacity`.** The state maps below set
  both to fixed values, so a box whose base value differs can never be restored. App-system boxes are
  distinguished by a solid stroke and a bigger label instead.
- **An app-system box has no incident edge of its own** — the arrows belong to the participants
  inside it — so hover/selection has to walk one level further, and the box ids must be in the state
  payload or they stay bright while their contents dim.
- **The cluster layout's shelf width has to count the gutters.** Measured on cards alone, a sheet of
  one-participant systems has almost no area, the target width lands under one row, every cluster
  gets its own shelf, and fit-to-view shrinks the resulting 1300px column to nothing.
- **Clearing a state with `[]` silently does nothing.** `setElementState(id, [])` stores the empty
  array and resolves successfully, but the draw never repaints the element back to its base style —
  the graph stayed dimmed forever after the first hover. Every state branch must set the same
  properties and an explicit `normal` state must restore them. Corollary: a state may only touch
  properties whose base value is identical across elements, so selection is a shadow, not a thicker
  border — `lineWidth`/`stroke` differ on a conflicting endpoint and no state could put them back.

Colours come from `theme.useToken()` via `palette.ts`, not literals: G6 renders to canvas and shares
nothing with antd's CSS variables. That is the same token bridge `index.css` makes for matrix cells,
in the other direction.

## Mock API (`server/index.js`)

Dependency-free node, same contract on the same port, `mock.json` as the committed source of truth — `{ endpoints, releases, releaseNodes, releaseLinks }`, all rewritten in place on save (expect a diff after using the UI in mock mode). It ports **Java's exact `String.hashCode`**, so mocked health matches the Spring backend endpoint-for-endpoint at the same seed. Verified identical for dimensions/conflicts/matrix/health. Keep the two in step when changing the contract.