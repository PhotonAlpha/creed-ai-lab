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
- **Config (`/config`)** — full table, add/edit/delete via one page-level modal, save-back-to-database.
- **i18n** en / zh-CN throughout, including the antd locale bundle; persisted in `localStorage`.
- `antd lint src` → 0 issues. `tsc -b` and `vite build` clean.

## Landmines

Four bugs were found and fixed here in browser testing. **Don't reintroduce them:**

1. **Stale-response race** — StrictMode's double mount fires an unfiltered request that can resolve
   *after* a filtered one and overwrite the grid. `pages/Matrix/index.tsx` guards with a request-id
   ref; keep that guard on any new filter-driven fetch.
2. **Per-row modals** — a `ModalForm` with a `trigger` inside a table cell loses its open state when
   the cell re-renders, so the first click does nothing. Use **one page-level controlled modal**.
3. **`scroll={{x:'max-content'}}` + a `fixed:'right'` column** collapses the last scrolling column to
   a few pixels. Use an explicit numeric width.
4. **`sticky` together with `scroll.y`** renders a second, offset header. `scroll.y` alone is enough.

Other constraints:

- **Saving writes the whole table** (`deleteMissing: true`), so the page must always load the
  complete, unfiltered set and filter client-side. Do not add server-side filtering to this page.
- **Dependency pins are deliberate** — antd 5 (not 6), the React-19 patch, the `path-to-regexp`
  override, and `react-router-dom` 7.18.1. See `README.md` §9 before changing any of them.
- `server/mock.json` is **rewritten in place** when the config page saves in mock mode; expect a git
  diff after using the UI that way.
- The mock server ports Java's exact `String.hashCode` so mocked health matches the Spring backend.
  Keep both sides in step when the contract changes.

## Open items

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
