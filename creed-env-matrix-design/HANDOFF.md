# creed-env-matrix-design — handoff

**Purpose** The Env Matrix Viewer frontend — **the only frontend in this repo**.
**Skill** `creed-env-matrix` · requirements outline: `CLAUDE.md` · user docs: `README.md` / `README.zh-CN.md`

## Run

```bash
npm install

# A — no database, no JDK
npm run mock     # mock API on :3001, data from server/mock.json
npm run dev      # UI on :5173

# B — real backend: start creed-resource-env-matrix with -Dspring-boot.run.profiles=dev, then
npm run dev
```

Both backends serve the identical contract on `:3001`, so no frontend change is needed to switch.
`VITE_API_TARGET=https://localhost:18095 npm run dev` points at the HTTPS profile instead.

## Current state

Complete and verified in a browser against the real backend.

- **Matrix (`/`)** — `service × country` grid, per-dimension filters, three-way scheme filter,
  conflict highlighting with a badge, conflicts-only toggle, health summary + re-check, conflict
  panel listing each colliding address.
- **Topology (`/topology`)** — the same filtered slice as a graph (`@antv/g6`): one card per
  endpoint, boxed by app system, with **declared** arrows between systems (from `env_app_link`) and
  derived lines for same-host / same-IP / conflicting addresses. **Tier is a required filter** — the
  wiring is declared per tier. Two layouts, click for details, hover to highlight.
  Verified in a browser against `npm run mock`; not yet exercised against the Postgres `dev` profile.
- **Config (`/config`)** — two tabs. *Endpoints*: the full table, add/edit/delete via one
  page-level modal, save-back-to-database. *Topology links*: the app-system wiring for one tier,
  same editing model, saved with a per-tier authoritative batch write.
- **i18n** en / zh-CN throughout, including the antd locale bundle; persisted in `localStorage`.
- `antd lint src` → 0 issues. `tsc -b` and `vite build` clean.

## Landmines

Eleven bugs were found and fixed here in browser testing. **Don't reintroduce them:**

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
11. **ProComponents does not read antd's `locale`.** Its built-in strings (form placeholders,
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
- **App-system ranks come from the links, not from a constant.** `rankAppSystems` is a longest-path
  layering over the stored `sourceApp -> targetApp` orientation, ignoring an edge that closes a
  cycle. `direction` never enters the ranking: counting a `BIDIRECTIONAL` link both ways makes every
  such pair a two-cycle.
- **The topology page requires a tier and passes it to `/links` alone.** Narrowing to one country
  filters the *endpoints*, never the wiring — a link must not disappear because of a country filter.
- `server/mock.json` is **rewritten in place** when the config page saves in mock mode; expect a git
  diff after using the UI that way.
- The mock server ports Java's exact `String.hashCode` so mocked health matches the Spring backend.
  Keep both sides in step when the contract changes.

## Open items

- **The arrows are app-system level, by design.** `env_app_link` declares which systems talk;
  nothing records which *instance* calls which, and drawing an endpoint-to-endpoint arrow would
  assert something the data cannot support. If per-endpoint call edges are ever wanted they need
  their own table — `buildGraph` would gain one more edge kind and nothing else here would change.
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
