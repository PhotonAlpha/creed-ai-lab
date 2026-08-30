# Env Matrix Viewer

[简体中文](./README.zh-CN.md)

Environment **host / ip / port** mapping matrix — a viewer and editor for the endpoints of the
`CCS` / `MS` / `AliYunTeir` / `TencentTeir` estate across `SIT` / `UAT` / `NFT` / `PROD`.

The point of the tool is **conflict hunting**: seeing at a glance where two endpoints that should be
distinct resolve to the same address.

| | |
|---|---|
| Frontend | React 19 + TypeScript + Vite 8 + Ant Design 5 / Ant Design Pro components |
| Backend | [`creed-resource-env-matrix`](../creed-resource/creed-resource-env-matrix) (Spring Boot 3.5, PostgreSQL) |
| Mock backend | `server/index.js` — same contract, no database required |

---

## 1. Quick start

### Option A — mock backend (no database, no JDK)

```bash
npm install
npm run mock                                        # terminal 1 — mock API on :3001
VITE_API_TARGET=http://localhost:3001 npm run dev   # terminal 2 — UI on :5173
```

(The env var is not optional — see *Which backend `npm run dev` actually talks to* below.)

### Option B — real backend (PostgreSQL)

```bash
# 1. create the database once
docker exec creed-artifactory-db createdb -U artifactory env_matrix

# 2. start the backend on :3001 (plain HTTP, Flyway migrates + seeds on first run)
cd .. && mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"

# 3. start the UI, pointed at :3001
VITE_API_TARGET=http://localhost:3001 npm run dev
```

Open <http://localhost:5173/>.

### Which backend `npm run dev` actually talks to

**The committed `.env` points at the HTTPS profile, not the mock:**

```
VITE_API_TARGET=https://localhost:18095
```

So a plain `npm run dev` proxies `/api` to `creed-resource-env-matrix` on **:18095** (`primary` /
`secondary` / `cloud`; `secure: false` in `vite.config.ts` is what accepts the Creed-CA certificate).
Options A and B above both answer on **:3001**, so they need the target pointed back at it:

```bash
VITE_API_TARGET=http://localhost:3001 npm run dev
```

Worth knowing before debugging a change that "does not work": with the HTTPS backend running, the UI
comes up fully populated whether or not `npm run mock` is running, so a stale backend build looks
exactly like a broken frontend. A shell-exported `VITE_API_TARGET` wins over `.env`; both are read by
`loadEnv` in `vite.config.ts`.

### Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server on `:5173`, proxying `/api` → `VITE_API_TARGET` (`.env`: `https://localhost:18095`) |
| `npm run mock` | Node mock API on `:3001` (`PORT=… ` to change) |
| `npm run build` | Type-check and produce `dist/` |
| `npm run typecheck` | Types only |

---

## 2. Data model

One **endpoint** is identified by seven dimensions and maps to one `host` / `ip` / `port`.

| Dimension | Example values |
|---|---|
| App system | `CCS`, `MS`, `AliYunTeir`, `TencentTeir` |
| Tier | `SIT`, `UAT`, `NFT`, `PROD` |
| Env instance | `SIT1`–`SIT2`, `UAT1`–`UAT3`, `NFT1`, `PROD1` |
| Country | `CN`, `SG`, `MY`, `HK`, `GD`, `ID` |
| Service | `MS1`–`MS6`, `CCS1`–`CCS6`, `AliYunTeir1`–`AliYunTeir4`, `TencentTeir1`–`TencentTeir3` |
| Instance | `Green`, `Green2` (Active-Standby) |
| Scheme | `http`, `https` |

`scheme` is **part of the identity**: one service legitimately exposes both an http and an https
endpoint, and those are two rows rather than a duplicate.

Dimension values are stored as plain text, not enums. `GET /api/env-matrix/dimensions` derives the
UI's filter options from the values actually present, so inserting a row with a new country or a
`UAT6` extends the dropdowns with no code change.

---

## 3. Conflicts

A conflict is two endpoints resolving to the same address where they ought to be unique. Two keys
are checked independently:

- **`host:port`** — the obvious clash, two logical endpoints pointing at one listener;
- **`ip:port`** — the clash DNS hides, two hostnames resolving to one address.

### Uniqueness scope

"Where they ought to be unique" is explicit and configurable — `env-matrix.conflict.scope`:

| Scope | Meaning |
|---|---|
| `TIER_ENV` *(default)* | unique within one `tier/envInstance`, e.g. `UAT/UAT1` |
| `TIER` | unique across a whole tier, so `UAT1`…`UAT5` must not overlap |
| `GLOBAL` | unique across the entire estate |

The default deliberately does **not** flag the same address reused in two different environments —
that is what separate environments are for. Endpoints differing only by `scheme` *do* collide: one
port cannot serve both http and https.

Detection runs over the **filtered** set, so the highlighting always explains itself from the rows
currently on screen. Narrowing the filter to one side of a clash makes the conflict disappear.

The seeded dataset contains four deliberate conflicts (see the `note` column) so the conflict panel
is not empty on a fresh database.

---

## 4. Health check

Health is **mocked on the backend** by default (`env-matrix.health.mode=mock`): the state is a pure
function of `host:port` and a rotatable seed, and no network traffic is generated. The matrix
describes environments this process generally cannot reach, so a real probe would report a uniform
wall of `DOWN` and tell you nothing.

Mocked states are **stable across calls** on purpose — a state that re-rolled on every render would
make the matrix flicker. The **Re-check** button rotates the seed, which changes them
deterministically.

Set `env-matrix.health.mode=real` for a plain TCP connect to `ip:port`. That proves something is
listening; it says nothing about whether the service behind the port is healthy. The UI always shows
the active mode so mocked green ticks are never mistaken for a real reachability report.

The mock server ports Java's exact `String.hashCode`, so a given `host:port` reports the **same**
state in both backends at the same seed.

---

## 5. Pages

### Matrix (`/`)

`service` rows × `country` columns. Each cell stacks that intersection's endpoints — typically the
Active-Standby pair times the schemes in use — showing scheme, port, instance and a health dot.

- Filter by any dimension; `scheme` is a three-way All / http / https control.
- Conflicting cells are highlighted with a badge, and the colliding port is red.
- **Conflicting cell** toggle hides every clean row.
- The conflict panel lists each colliding address with the endpoints that claim it.

On load the view defaults to the first environment instance. Unfiltered, every cell would stack the
endpoints of all environments and the grid would be unreadable. The default is a visible, clearable
filter value rather than a hidden query parameter.

### Topology (`/topology`)

The same filtered slice as a graph. One card per endpoint — service name, `ip:port`, instance,
scheme, and a health stripe down the left edge — boxed by participant, and those boxes boxed again
by app system.

Four kinds of line, each toggleable from the toolbar:

| Line | Meaning | Source |
|---|---|---|
| Solid arrow | declared dependency, participant → participant | one `env_release_link` row |
| Grey dashes | two endpoints answer on the same `host` | derived from `/endpoints` |
| Blue dots | two hostnames resolve to the same `ip` | derived from `/endpoints` |
| Red dashes | the same `host:port` or `ip:port` is claimed twice | `/conflicts` |

**The arrows are declared data, not observed.** `env_endpoint` records addresses; no column anywhere
says "A calls B". The wiring lives in its own tables, edited on the **Configuration → Release
topology** tab.

A **release** is a named set of environment slices and the connections between them:

| Table | What it holds |
|---|---|
| `env_release` | name, tier (a label), status (`DRAFT`/`ACTIVE`/`ARCHIVED`) |
| `env_release_node` | a **participant** — `(appSystem, country, envInstance)`, plus where to draw it (`layer`, `sort_order`) |
| `env_release_link` | a connection between two participants, plus `direction` |

**A release is the required scope on this page.** The reason a connection cannot simply name two app
systems is that one app system can appear twice in a single chain:

```
SG CCS SIT3  →  Global-CCS SIT2  →  CN CCS SIT5
```

So a topology node is a *slice*, and a release is what says which slices belong together. That is
also what keeps the other dimensions orthogonal — country, envInstance, service and instance stay
plain data, tied together only by a release.

Each participant collects the endpoints matching its slice; `country = '*'` means "not
country-specific" and matches every region. A participant with no matching endpoints is drawn as a
**dashed placeholder** — that gap between "wired into the topology" and "recorded in the matrix" is
exactly what this viewer is for. Endpoints in view that no participant claims are counted in a
banner rather than drawn.

Narrowing by country or environment instance filters the *endpoints inside the boxes*, never the
wiring — a connection must not vanish because of a country filter.

Column order comes from the connections themselves: `rankParticipants` is a longest-path layering
over the stored `source -> target` orientation, so the flow axis *is* the hierarchy. `direction`
never enters the ranking — counting a two-way link both ways would make every such pair a cycle. Two
layouts: **Layered** and **By app system**, both positioned by `pages/Topology/buildGraph.ts` rather
than by a G6 layout.

#### How the graph is derived from the endpoints

The two halves of this module record two different kinds of fact, and the graph is the join of them.
`env_endpoint` says **where something answers**; nothing in it, and nothing computable from a host or
a port, says that one thing calls another. `env_release_node` / `env_release_link` say **who talks to
whom**, and know nothing about addresses. Neither table has a foreign key to the other, deliberately.

Everything on screen comes from one of the two, or from a rule over one of them:

| On screen | Comes from | Rule |
|---|---|---|
| Endpoint card | one `env_endpoint` row in the filtered set | drawn only if some participant claims it |
| Dashed placeholder | one `env_release_node` row | the participant matched no endpoint |
| Participant box | one `env_release_node` row | always drawn, empty or not |
| Where a box sits | `env_release_node.layer` / `.sort_order` | overrides the derived layer for that participant |
| App-system box | the participant boxes | grouping by `appSystem` — presentation only |
| Solid arrow | one `env_release_link` row | box → box, never card → card |
| Grey dashes | endpoints | same `host`, chained in port order |
| Blue dots | endpoints | same `ip`, different `host`, one card per hostname |
| Red dashes | `GET /conflicts` | the backend decided the collision; the graph only draws it |
| Layer (column / row) | the links | longest path over `source -> target`, see below |
| Endpoint counter, "unclaimed" banner | endpoints | claimed by nobody |

**The claim rule** is the whole join, and it runs one way — endpoints into participants:

```
participant(appSystem, country, envInstance)  ⟕  endpoint(appSystem, country, envInstance)

country = '*' on the participant matches every country
the first matching participant wins, specific slices sorted before wildcards
```

Three consequences worth knowing before reading a graph:

- **An endpoint no participant claims is not on the canvas.** It is counted in a banner instead —
  the release does not cover it, which is a fact about the release, not about the endpoint.
- **A participant nothing matches is still on the canvas**, as a dashed placeholder. That gap between
  "wired into the topology" and "recorded in the matrix" is the thing this viewer exists to surface.
- **The endpoint filters never touch the wiring.** They change which cards sit inside a box; the
  boxes, the arrows and the layering are fetched by release id alone.

**Layering** is derived from the links and nothing else:

```
layer(p) = 0                                   if no declared link points at p
         = max(layer(source)) + 1              over every link source -> p
         (an edge that closes back onto the current path is skipped, so a
          user-declared cycle cannot hang the walk)
```

`direction` is not consulted: it decides arrowheads, and counting a `BIDIRECTIONAL` link both ways
would make every such pair a two-cycle with no defined layering.

A participant may then be **pinned**: `env_release_node.layer` replaces that participant's number and
leaves everything downstream where the links put it. `null` — the state every participant starts in —
means "derive it", and cannot be spelt `0`, which means "column 0". `sort_order` does the same for
position along the other axis, with app systems moving as blocks (a cluster takes the lowest sort
order of its members). Both are edited from the graph's **Layers & order** dialog.

Nothing here runs the other way. Adding an endpoint never creates a participant or an arrow; adding a
link never creates an endpoint. Whatever the graph shows about who calls whom was typed by somebody
on the **Configuration → Release topology** tab.

#### Reading controls

| Control | What it does | Stored |
|---|---|---|
| **Layered / By app system** | hierarchy along one axis, or one block per app system | this browser |
| **→ ← ↓ ↑** | which way the layered hierarchy runs; layer 0 sits at the tail of the arrow | this browser |
| **Group by app system** | draw (and pack together) a box around every participant of one app system | this browser |
| **Layers & order** | pin a participant to a layer, or reorder app systems along the cross axis | the release |

The split is between how someone reads a picture and what the picture says. Orientation and the
app-system boxes are reading habits, kept in `localStorage` so they survive picking another release.
A pinned layer is a claim about the estate — "this slice is a step of its own, whatever the links
imply" — so it belongs to the release, in `env_release_node.layer` / `.sort_order`, where the next
person to open it sees the same graph.

**Layers & order** stages its edits and writes them with one **Save**; the graph does not move until
that lands. The write is the same authoritative `PUT /releases/{id}/topology` the configuration page
uses, so it resends the release's whole topology with only those two fields changed. Clearing a cell
(`auto`) hands that participant back to the derived layering, and **Reset all** hands back the whole
release — both still need saving.

Because an app system can legitimately appear in several layers, the app-system box in the layered
view is per *(app system, layer)*: `SG CCS SIT3` in column 0 and `CN CCS SIT5` in column 2 are two
boxes, not one box stretched across everything between them.

### Configuration (`/config`)

Two tabs.

**Endpoints** — the full endpoint table with add / edit / delete, then **Save to database**.

Saving writes the **whole table**: rows removed in the UI are deleted in the database. The page
therefore always loads the complete, unfiltered set and narrows client-side — sending a filtered
subset would delete everything the filter hid. Rows that did not actually change are neither counted
nor written, so a one-field edit reports "1 updated", not "1235 updated".

Validation failures come back as `422` with per-row issues and **nothing is written** — the whole
save is one transaction.

**Release topology** — a release list on the left, the selected release's participants and
connections on the right, one save. Saving is authoritative for that release only: rows removed here
are deleted, and other releases are never touched.

Participants are edited with free-text fields (a slice with no endpoints yet is legal and shows as a
placeholder); connections pick both ends from the release's own participants, so a participant has
to exist before it can be connected. `layer` and `sort_order` are not editable here — a layer only
means something next to the boxes around it, so it is set on the graph — but they travel with every
row this page saves, because the save is authoritative and a payload that omitted them would clear
the layering somebody arranged. A participant added but not yet saved is still selectable —
the editor sends it as a `ref` that the save resolves to the new row's id. Declaring both `A → B`
and `B → A` is rejected; that is what `BIDIRECTIONAL` is for.

---

## 6. API

Base path `/api/env-matrix`. Filters are repeated query parameters —
`?tier=UAT&tier=SIT&scheme=https` — optional, and ANDed across dimensions.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/ping` | Liveness + active health-probe mode |
| `GET` | `/dimensions` | Distinct values per dimension, for the filter dropdowns |
| `GET` | `/endpoints` | Flat list (filterable) |
| `GET` | `/endpoints/{id}` | One endpoint |
| `POST` | `/endpoints` | Create → `201`, `409` on duplicate identity |
| `PUT` | `/endpoints/{id}` | Update |
| `DELETE` | `/endpoints/{id}` | Delete → `204` |
| `PUT` | `/endpoints` | Batch save the whole table → `200`, or `422` with issues |
| `GET` | `/releases` | Releases; `?tier=` and `?status=` scope them |
| `GET` | `/releases/{id}` | One release |
| `POST` | `/releases` | Create → `201`, `409` on a duplicate name |
| `PUT` | `/releases/{id}` | Update |
| `DELETE` | `/releases/{id}` | Delete → `204`, taking its participants and connections |
| `GET` | `/releases/{id}/topology` | `{release, nodes[], links[]}` — the graph's data source |
| `PUT` | `/releases/{id}/topology` | Replace one release's topology → `200`, or `422` with issues |
| `GET` | `/matrix` | `service × country` grid + conflicts |
| `GET` | `/conflicts` | Conflict groups only |
| `GET` | `/health` | Per-endpoint states + summary + probe mode |
| `POST` | `/health/recheck` | Re-run the probe (rotates the mock seed) |

Errors use one envelope: `{error, message, fields?, time}`.

Recording a conflict is **allowed** — discovering and documenting a clash is the point of the tool,
so conflicts are reported, never rejected. Only duplicate *identities* are rejected (`409`).

---

## 7. Layout

```
.
├── src/
│   ├── api/            # typed client + DTOs mirroring the backend
│   ├── components/     # FilterBar, health tag/dot
│   ├── hooks/          # useDimensions
│   ├── locales/        # en-US / zh-CN + provider
│   └── pages/
│       ├── Matrix/     # matrix view (/)
│       ├── Topology/   # topology graph (/topology)
│       │                # buildGraph.ts is pure: endpoints + conflicts + links -> nodes/edges
│       └── Config/     # CRUD editor (/config)
├── server/
│   ├── index.js        # mock API — same contract, no dependencies
│   └── mock.json       # mock data source, rewritten on save
└── vite.config.ts      # /api → :3001 proxy
```

`server/mock.json` is committed on purpose: it is the mock API's source of truth. The mock server
rewrites it in place when the config page saves, so expect a diff after using the UI in mock mode.

---

## 8. i18n

English and 简体中文, switched from the header. The choice persists in `localStorage`; the initial
language follows the browser. antd's own locale bundle is switched at the same time, so built-in
strings (pagination, empty states) follow too.

`zh-CN.ts` is typed as `Record<keyof typeof enUS, string>` — a missing translation is a compile
error, not a silent fallback. **When you change UI copy, update both files.**

---

## 9. Notes on dependencies

- **antd 5, not 6.** `@ant-design/pro-components@2.8.10` declares `antd: ^4.24.15 || ^5.11.2` and
  does not support antd 6. The antd-6-compatible Pro release (`3.1.14-5`) is still a pre-release.
- **`@ant-design/v5-patch-for-react-19`** is required and imported first in `main.tsx`: antd 5
  targets React 16–18, and without the shim it logs a compatibility warning and Modal/message
  misbehave on React 19.
- **`path-to-regexp` override.** `@ant-design/pro-layout` pins it to exactly `8.2.0`, which carries
  two high-severity ReDoS advisories; the `overrides` entry moves it to the fixed `8.4.2` without
  downgrading Pro.
- **`npm run typecheck` runs `tsc -b`, not `tsc --noEmit`.** The root `tsconfig.json` is
  solution-style, and `--noEmit` does not follow project references — it exits 0 on a codebase full
  of type errors.
- **`@antv/g6` alone, not `@ant-design/graphs`.** The React wrapper pulls in `styled-components@6`
  and `@antv/graphin` to save a thin `Graph` wrapper; this app already carries one React-19 compat
  shim and does not need another moving part. G6 itself declares no React peer dependency.
- **`react-router-dom` 7.18.1** still trips `GHSA-qwww-vcr4-c8h2` (RSC-mode CSRF) in `npm audit`.
  This is a plain `BrowserRouter` SPA with no RSC mode and no server actions, so the path is not
  reachable, and there is no fixed 7.x yet. Do **not** downgrade: 7.11.0 and earlier carry 14
  advisories that 7.18.0 fixed.
