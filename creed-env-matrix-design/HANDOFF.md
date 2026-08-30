# creed-env-matrix-design — handoff

**Purpose** The Env Matrix Viewer frontend — **the only frontend in this repo**.
**Skill** `creed-env-matrix` · requirements outline: `CLAUDE.md` · user docs: `README.md` / `README.zh-CN.md`

## Run

```bash
npm install

# A — no database, no JDK
npm run mock                                        # mock API on :3001
VITE_API_TARGET=http://localhost:3001 npm run dev   # UI on :5173

# B — real backend on :3001: creed-resource-env-matrix with -Dspring-boot.run.profiles=dev, then
VITE_API_TARGET=http://localhost:3001 npm run dev

# C — the module's normal HTTPS profile on :18095 (what the committed .env points at)
npm run dev
```

All three serve the identical contract, so no frontend change is needed to switch.

**`npm run dev` with no override is C, not A.** The committed `.env` holds
`VITE_API_TARGET=https://localhost:18095`, so the UI comes up fully populated from whatever HTTPS
backend happens to be running — with or without `npm run mock` — and a backend that has not been
restarted since a contract change looks exactly like a broken frontend. This cost real debugging
time; check the proxy target first. A shell-exported value wins over `.env`, and both are read by
`loadEnv` in `vite.config.ts`.

## Current state

Complete and verified in a browser against the real backend.

- **Matrix (`/`)** — `service × country` grid, per-dimension filters, three-way scheme filter,
  conflict highlighting with a badge, conflicts-only toggle, health summary + re-check, conflict
  panel listing each colliding address.
- **Topology (`/topology`)** — the same filtered slice as a graph (`@antv/g6`): one card per
  endpoint, boxed by **participant** (an environment slice: app system + country + env instance),
  those boxes boxed again **by app system**, with **declared** arrows between participants and
  derived lines for same-host / same-IP / conflicting addresses. **A release is the required scope**,
  not a tier. Two layouts, four flow directions (`→ ← ↓ ↑`), a **Layers & order** dialog that writes
  `env_release_node.layer` / `.sort_order` back into the release, click for details, hover to
  highlight.
  Verified in a browser against `npm run mock` **and** against the HTTPS backend on real PostgreSQL:
  pin + reorder → save → the two changed rows (and only those) updated in `env_release_node` → full
  reload draws the stored layout → **Reset all** + save returns every row to `null` / `0`.
  **The derivation from endpoints to graph is documented in `README.md` → "How the graph is derived
  from the endpoints"** (and the same section in `README.zh-CN.md`) — keep it in step with
  `pages/Topology/buildGraph.ts`, which is the only place that join lives.
- **Config (`/config`)** — two tabs. *Endpoints*: the full table, add/edit/delete via one
  page-level modal, save-back-to-database. *Release topology*: a release list, its participants and
  its connections, saved with one authoritative batch write per release.
- **i18n** en / zh-CN throughout, including the antd locale bundle; persisted in `localStorage`.
- `antd lint src` → 0 issues. `tsc -b` and `vite build` clean.

## Landmines

Twelve bugs were found and fixed here in browser testing. **Don't reintroduce them:**

1. **Stale-response race** — StrictMode's double mount fires an unfiltered request that can resolve
   *after* a filtered one and overwrite the grid. `pages/Matrix/index.tsx` guards with a request-id
   ref; keep that guard on any new filter-driven fetch.
2. **Per-row modals** — a `ModalForm` with a `trigger` inside a table cell loses its open state when
   the cell re-renders, so the first click does nothing. Use **one page-level controlled modal**.
3. **`scroll={{x:'max-content'}}` + a `fixed:'right'` column** collapses the last scrolling column to
   a few pixels. Use an explicit numeric width.
4. **`sticky` together with `scroll.y`** renders a second, offset header. `scroll.y` alone is enough.

G6-specific, all four from `pages/Topology/TopologyGraph.tsx`:

5. **Stale closures in G6 event handlers** — the canvas is built once and its handlers outlive every
   render, so hover/click callbacks must go through refs. Reading `model` directly froze the first
   render's *empty* model and hovering did nothing at all.
6. **Overlapping renders leave both datasets on the canvas** — `graph.render()` is async, and calling
   `setData` while one is in flight left the previous environment's cards beside the new ones.
   Renders are chained onto a promise ref so only one runs at a time.
7. **`animation: false` is deliberate** — `setElementState` applied during the enter tween froze
   nodes at partial opacity and the graph came up looking permanently dimmed.
8. **Fit-to-view must wait a frame** — on first paint ProCard is still sizing its body, so a fit
   measured then leaves the graph translated half out of view. A `requestAnimationFrame` plus a
   `ResizeObserver` re-fit covers it.

9. **Clearing a G6 state with `[]` does not repaint.** `setElementState(id, [])` writes the empty
   array into G6 5.1.1's data and resolves *successfully*, but the state-stage draw never puts the
   element back to its base style — so once the pointer touched a node and left, the graph stayed
   dimmed and no later hover could highlight anything. Every state map now sets the **same**
   properties in every branch and an explicit `normal` state restores them; nothing is left for G6
   to revert. A corollary: a state may only touch properties whose base value is the same for every
   element, which is why selection is a shadow rather than a thicker border (`lineWidth` and
   `stroke` differ on a conflicting endpoint).
10. **`npm run typecheck` used to check nothing.** The root `tsconfig.json` is solution-style
   (`files: []` + project references) and `tsc --noEmit` does not follow references — it exited 0 on
   a file full of type errors. The script is now `tsc -b --force`; use that, never bare `--noEmit`.
11. **A hidden tab panel is still in the accessibility tree.** The endpoints editor is kept mounted
    behind `display: none` (unmounting would refetch 1200+ rows and discard unsaved edits), which
    left the page exposing two "Save to database" buttons. `inert` on the hidden subtree fixes it.
12. **ProComponents does not read antd's `locale`.** Its built-in strings (form placeholders,
    pagination, column settings) stayed Chinese with the UI in English until `main.tsx` wrapped the
    tree in `ProConfigProvider` with the matching `enUSIntl` / `zhCNIntl`.

Other constraints:

- **Saving writes the whole table** (`deleteMissing: true`), so the page must always load the
  complete, unfiltered set and filter client-side. Do not add server-side filtering to this page.
- **Dependency pins are deliberate** — antd 5 (not 6), the React-19 patch, the `path-to-regexp`
  override, and `react-router-dom` 7.18.1. See `README.md` §9 before changing any of them.
- **`@antv/g6` on its own, not `@ant-design/graphs`** — the React wrapper drags in
  `styled-components@6` and `@antv/graphin`, and this app already carries one React-19 compat patch.
  G6 has no React peer dependency at all.
- **The topology graph runs no G6 layout.** `pages/Topology/buildGraph.ts` positions every node.
  `combo-combined` overlaps the group boxes, and no built-in layout knows the declared ranking.
- **Participant ranks come from the links, not from a constant.** `rankParticipants` is a
  longest-path layering over the stored `source -> target` orientation, ignoring an edge that closes
  a cycle. `direction` never enters the ranking: counting a `BIDIRECTIONAL` link both ways makes
  every such pair a two-cycle.
- **The topology page requires a release and fetches `/releases/{id}/topology` by id alone.**
  Narrowing by country filters the *endpoints*, never the wiring — a connection must not disappear
  because of a country filter.
- **An endpoint is claimed by the first participant whose slice matches, specific before wildcard.**
  A release may declare both `CCS/SG/SIT3` and `CCS/'*'/SIT3`; without the specific-first sort the
  wildcard swallows every region and the specific box renders empty.
- **The app-system box is presentation only, and in the layered view it is per _(app system, layer)_.**
  A topology node is still a slice — CCS can appear twice in one chain — so one box per app system
  across the whole graph would have to stretch over every column in between and overlap them.
- **Combo gutters are measured card to card and must clear the boxes.** `GROUP_GAP` has to fit two
  18px paddings plus the next participant's label; `APP_GROUP_GAP` two more paddings plus the
  cluster's own label. Under those numbers the boxes touch and each label is drawn on its neighbour's
  border. Both are in `pages/Topology/topology.config.ts` with the arithmetic written out.
- **A nested combo must share its parent's `fillOpacity`/`strokeOpacity`.** The hover states set both
  to fixed values, so a box whose base value differs can never be restored (landmine 9). App-system
  boxes are told apart by a solid stroke and a bigger label, not by a different opacity.
- **The cluster layout shelf-packs app systems, and the shelf width counts the gutters.** Measuring
  only the cards makes a sheet of one-participant systems look like almost no area at all, the target
  width comes out narrower than a single row, every cluster lands on its own shelf and fit-to-view
  shrinks a 1300px column of cards to nothing.
- **Layer pins and ordering live on the release** (`env_release_node.layer` / `.sort_order`, added by
  `V5`), not in the browser: a pinned layer is a claim about the estate, so the next person to open
  the release sees the same graph. `layer` is **nullable** — `null` is "derive it from the links" and
  cannot be spelt `0`, which means column 0. A pin replaces that one participant's layer and leaves
  its downstream alone. Only orientation / layout / grouping stay in `localStorage`.
- **Every writer of `PUT /releases/{id}/topology` must resend `layer` and `sortOrder`.** The save
  replaces the release's whole topology, so the configuration page carries both fields through
  untouched (`ParticipantRow`) even though it cannot edit them — omitting them clears the layering.
- **The layer editor stages its edits and saves once.** One request per keystroke would rewrite the
  whole release on every digit, and a rejection would leave half the table applied.
- **The editor's `_key` becomes the payload's `ref`.** A participant added in the browser has no id,
  and the connection rows have to name it before the save assigns one — `toTopologyRequest` is the
  only place that translation happens, and it is the easiest thing here to break.
- `server/mock.json` is **rewritten in place** when the config page saves in mock mode; expect a git
  diff after using the UI that way.
- The mock server ports Java's exact `String.hashCode` so mocked health matches the Spring backend.
  Keep both sides in step when the contract changes.

## Open items

- **The arrows are participant level, by design.** A release declares which slices talk; nothing
  records which *endpoint* calls which, and drawing an endpoint-to-endpoint arrow would assert
  something the data cannot support. If per-endpoint call edges are ever wanted they need their own
  table — `buildGraph` would gain one more edge kind and nothing else here would change.
- **Cross-release occupancy conflicts are not implemented.** Two ACTIVE releases both claiming SIT3
  is a scheduling clash and arguably the strongest argument for this model, but it was deliberately
  left for a later round.
- **A circular layout was tried and dropped.** Nodes are 196px cards, so a ring of one environment's
  forty endpoints is ~3000px across and fit-to-view shrinks the labels out of legibility. It would
  need a second, dot-sized node type to be worth having.

- **`grafana/` contains notes only, not dashboards.** The target layout in `CLAUDE.md` lists a
  `grafana/` directory; since the repo already has a provisioned stack under `monitoring/`, this
  holds a document explaining how the module's metrics reach it. If actual dashboards were wanted,
  they are not built.
- **`npm audit` reports one high** (`GHSA-qwww-vcr4-c8h2`, react-router RSC-mode CSRF). Not reachable
  in a plain `BrowserRouter` SPA and there is no fixed 7.x. Re-check when 7.19+ ships.
- Bundle is ~2.1 MB (650 kB gzipped) in a single chunk — no code splitting. Fine for an internal
  tool; revisit if it is ever exposed more widely.
- No frontend tests. Verification so far has been type-checking, `antd lint`, and manual browser
  testing.
