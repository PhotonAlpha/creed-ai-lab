---
name: creed-resource-migration-risk
description: creed-resource-migration-risk — migration sync risk between an old repo and its migrated copy (repo1 HEAD vs repo2 directly, no baseline; one RefactoringMiner directory-mode run, JGit, JDT; git branches or plain folders; PostgreSQL / MySQL / H2), the risk categories and how JavaRiskAnalyzer/FileRiskAnalyzer decide them, releases + item review in PostgreSQL (Flyway), the Thymeleaf AST-diff page, the demo repositories, and the /migration-risk page of creed-env-matrix-design. Use for risk rules, RefactoringMiner wiring, repository access, persistence/review, the diff view, the demo fixtures, or the page.
---

# creed-resource-migration-risk

Plain HTTP **18098**, no PKI. PostgreSQL `migration_risk` on `creed-artifactory-db` (or the `h2` profile) for
releases and reviews; the analysis itself is computed per request. Page `/migration-risk` in `creed-env-matrix-design`
(Vite proxy `/api/migration-risk` → `VITE_MIGRATION_RISK_TARGET`). See [[creed-platform]] for build/run,
[[creed-env-matrix]] for frontend conventions, [[creed-resource-veracode]] for the sibling module's shape.

## Shape

- `git/GitRepositories` — JGit, read-only: resolve, `blobIds` (one tree walk: every path + blob id), `read`, refs.
  Never checks out or touches the user's working tree.
- **No baseline (since 2026-10-28, asked for)**: `source/TreeDiff.diff(repo2, repo1)` over any two trees (old = repo2's
  path, new = repo1's; identical content elsewhere = RENAMED); `AnalysisContext.Side` is `REPO1` / `REPO2`; one RM
  run repo1 → repo2. Categories say *what differs*: `CONTENT_DIFFERS`, `ONLY_IN_REPO2` new; `BOTH_MODIFIED`,
  `DELETED_UPSTREAM`, `UPSTREAM_REFACTORING`, `AUTO_SYNC`, `NEW_CODE` stay in the enum (and in the frontend's
  `LEGACY_CATEGORIES`) only so saved runs deserialize — never delete them while such runs exist.
- `service/RefactoringDetector` — RefactoringMiner `detectAtDirectories`, reduced to `DetectedRefactoring`
  / `CodeLocation` records (nothing else sees its API). `RefactoringIndex` indexes the run by its *before* (repo1)
  side: method relocations / splits matched by **file + overlapping line range**, class moves by FQCN.
- `service/JavaStructure` — JDT parse: types, methods (`name(Type,...)`, constructors `<init>`), fields,
  each with lines and a **normalized** text (JDT `toString()` minus Javadoc, whitespace collapsed).
- `JavaRiskAnalyzer` (class by class, member by member) + `FileRiskAnalyzer` (pom via `ConfigDiff.pomLines(repo1,
  repo2)`, `.vm` drafted as `.ftl` and diffed, other files line by line, `migration-risk.api-rules` on repo1-only lines)
  → `RiskItem`s; `MigrationRiskService` orchestrates, drops API matches alone in their file, sorts, dedupes by id.
  `RepositoryGuard` = allowed roots.
- `demo/DemoRepositories` — `src/main/resources/demo/{repo1,repo2,repo2b}` committed into JGit repos at startup
  (fixed idents/times → stable ids). Each difference targets one category; `MigrationRiskApiTest` pins them.
  **Changing a fixture changes the test's expectations.**

- `source/` — `SourceTree` = `GitTree` (a commit) | `DirectoryTree` (a folder, `directory-excludes` skipped);
  `DirectoryDiff` = git-diff for folders (content ids are **git blob ids** in both, histogram diff for added
  lines, exact-content renames). `MigrationRiskService.withTrees(Inputs, fn)` opens either kind — the analysis
  and the diff view both start there.
- `source/MergedTree`: repo2A + repo2B as one tree — **paths not prefixed** (a module moved to repo2B at the same
  relative path must still match), first member wins on a collision (warning), `origin(path)` → item `target.repo`.
- `CamelRouteAnalyzer` + `CamelRoutes`: Camel **XML** routes indexed by route id (else `from` uri) across every route
  file of each tree, steps normalized to one line per element (attributes sorted, prefixes / namespaces / comments
  dropped); kind `ROUTE`. Its `Result.files` are skipped by `FileRiskAnalyzer` (no second, line-level finding); XML
  that is not Camel or does not parse falls back to the line diff. Content outside routes is compared per file
  (`NON_JAVA_FILE`). Same DOM hardening as `ConfigDiff` (doctype disallowed).
- `GlobalConfigAnalyzer`: config compared by **global key** across all `config-files` (`ConfigDiff.globalKeys`:
  bare key for the default profile, `[dev] key`, `[test] key`, `[messages_zh.properties] key`); change = the key's
  value *set* changed. Parsed once per content id. `FileRiskAnalyzer` skips these files (still API rules).
- `FileRecords`: the API returns **one record per changed file** (`id` = `FILE:<path>`, kind FILE, level /
  category / target state of the riskiest part); the analyzers' element-level findings are its `parts`. Reviews,
  counts and carry-over are per file (the fingerprint covers the parts). Asked for: the diff page already shows a
  file line by line, so a row per method or key was noise. Tests find elements via `$.items[*].parts[...]`.
- `CodeTags`: `module` (nearest build-file dir) + `test` from the path alone; `skipTests` filters `changes` in
  `withTrees`, so the analysis, RefactoringMiner's input and the diff view all agree.
- `ReleaseStore` + `domain/Mr*` (Flyway `V1`, `V2` = `repo2b_path`, `V3` sync tracking, `V4` `baseline_ref` nullable):
  `mr_release` / `mr_analysis` (result JSON minus items) / `mr_item` (item JSON, review columns, `@Version`). Re-runs
  carry verdicts by `item_key` + `fingerprint`; changed parts = new, different **or gone** parts.
- **Two migration folders**: `db/migration` (PostgreSQL, also run on H2 `MODE=PostgreSQL` by the tests) and
  `db/migration-mysql` (profile `mysql`). **Every new migration goes into both, same version number.** MySQL
  differences: `auto_increment`, `datetime(6)`, `longtext` for JSON (text = 64 KB), table-level foreign keys (an inline
  `references` is silently ignored), `item_key(750)` prefix in the unique key (InnoDB 3072-byte limit), `modify` not
  `alter column`. No test runs MySQL (no Docker in tests) — verify by hand on a `mysql:8.4` container.
- `DiffViewService` + `web/DiffViewController` + `templates/diff.html`: `diffAtDirectories` (repo1 left, repo2 right)
  on the same exported subset → `DirComparator` → RefactoringMiner's own `MonacoCore.makeDiffConfig()`; its
  `web/dist/*.js|css` are served from its jar at `/api/migration-risk/view/assets/`. One-sided files get a one-sided
  config. **Non-Java files** (yml, properties, .vm / .ftl, Camel XML ...) get `FileDiff.text`: a JGit histogram line
  diff in the same MonacoCore config shape; repo1's file pairs with repo2's same path, else the converted / same-named
  one; `target=` names an explicit repo2 file. `indexOf` returns -1 for an unknown file and the page says so.

- `AnalysisJobs` + `Progress`: background analyses on their own bounded pool, progress pushed over SSE (`SseEmitter`).
  `Progress.Stage` bands (RESOLVE 0 … MIGRATION 45, JAVA 80 … DONE 100); the job's `Progress` throws
  `CancelledException` at the next report after `DELETE`. `ApiErrors` maps exceptions to the error body for both
  the exception handler and the `failed` event.

## Rules that are easy to break

- **Details read like `git diff repo1 repo2`**: `- L12:` = repo1's line, `+` = repo2's (fields, pom keys, config
  keys likewise) — the same direction as the diff page, where repo1-only lines are the left's `deleted` marks. Don't
  flip one without the other. Auto `SYNCED` reviews (`review_auto`, a file of only `ALREADY_SYNCED` /
  `CONFIG_IN_SYNC` parts) are never carried over; manual verdicts carry over by fingerprint.
- **An API-rule match alone in a file is dropped** (`withoutLoneApiMatches`): without a baseline every migrated file's
  old imports match — that is the migration's rewrite, not work. The fingerprint ignores a `repo2A` label (single repo2 = repo2A),
  otherwise adding repo2B to a release resets every review.
- **Ignore rules apply to file records** (`IgnoreRules.apply` after `FileRecords.group`), and tests share one H2:
  a test that adds a rule must delete it in `finally`.
- **Remote URLs: https / file only, hosts allow-listed, credentials from config** — a URL with `user:pass@` is
  refused. The mirror is bare (`refs/heads/*` = the remote's branches); fetch is throttled by `fetch-interval`
  because `/refs` is called while the user types.
- **Pagination on this page is controlled** (page / size / filters in the URL via `useSearchParams`). A literal
  `pageSize` prop on an antd Table makes the size changer a no-op — use `defaultPageSize` or control it.

- **Config keys must not be named by file** — that is what makes a moved key "no change" and a key repo2B keeps in
  `config/application.yml` "in sync". Test resources are a separate scope, or a test override reads as prod.
- Fingerprints append `target.repo` only when set, and strip `+ L12: ` / `- L12: ` prefixes: saved reviews from
  before repo2B keep carrying over.

- **RefactoringMiner version vs JDK**: 3.1.x is compiled for Java 23 (class 67), 3.0.13 is the last Java-21 build.
  The pom pins 3.1.6 since 2026-10-25 → run and test on JDK 25; JDK 21 fails with `UnsupportedClassVersionError`.
- **Exclusions in the pom are load-bearing**: github-api, p4java, spark-core, rendersnake, jcommander,
  logback-classic. Without them Jetty 9, javax.servlet and **Spring 4.1.6** land next to Boot 4 (176 MB →
  51 MB). `commons-io` must then be declared directly — it only came via github-api, and directory mode
  calls `FileUtils` (`NoClassDefFoundError` otherwise).
- **RefactoringMiner's jar ships a `logback.xml`** (root WARN, stderr). Boot prefers a plain `logback.xml` over
  `logback-spring.xml`, so it silently replaced creed-common-metrics' config: only the banner printed, no INFO,
  no log file, no OTLP logs. `logging.config: classpath:logback-spring.xml` in `application.yml` pins ours —
  keep it. Same trap for any library that bundles a logging config at its classpath root.
- **Pass RefactoringMiner real absolute paths.** It relativizes files by cutting the directory string's
  length off the absolute path: a relative dir gave `b/sers/ethan/...` (NoSuchFileException, 0 results), and
  macOS `/var` vs `/private/var` breaks it the same way. `RefactoringDetector.real()` does `toRealPath()`.
- **RefactoringMiner reports refactorings, not edits.** A method whose body changed is invisible to it;
  "which methods did repo1 modify" comes from comparing `JavaStructure` normalized texts. Don't try to
  derive modifications from its output.
- **Match methods by line range, not signature** — RM prints `public handle(orderId String) : String`.
- Export only differing files (repo1's version, repo2's, + repo2 same-name files capped by
  `max-candidates-per-name`): whole repos are slow and memory-hungry. Without a baseline that is still most of a
  migrated repo — `max-changed-files` defaults to 5000.
- `GitTree` caches `blobIds` from one walk: comparing whole trees asks every path's id, and a `TreeWalk.forPath`
  per path was quadratic.
- `RepositoryGuard` creates the work dir **before** `toRealPath()` — otherwise the demo root resolves to
  `/var/...` while the repos resolve to `/private/var/...` and every demo request is 403.
- **rendersnake: the jar only.** `MonacoCore` links rendersnake classes at load time (`NoClassDefFoundError:
  org/rendersnake/internal/CharactersWriteable`), so it is declared directly with a `*:*` exclusion — its own
  dependencies are the Spring 4.1.6 / Guice / javax.servlet set excluded from RefactoringMiner. GumTree `core`
  is declared too (RM lists it at runtime scope; `DiffViewService` compiles against `Diff`/`Pair`).
- **`DirComparator` lists mapped pairs only.** Added / removed files come from `getAddedFilesName()` /
  `getRemovedFilesName()`; without the one-sided config a new upstream module showed "no Java file differs".
- **The diff page lives under `/api/migration-risk/view/**`** on purpose: that prefix is what the Vite proxy (and any
  API reverse proxy) already forwards. Its links carry **commit ids**, so a saved run keeps showing its code.
- **Flyway needs `spring-boot-starter-flyway` on Boot 4** — bare `flyway-core` runs no migration, silently.
  Tests run `V1` on H2 (`MODE=PostgreSQL`, Flyway on), so write migrations that both accept.
- **SSE: the result is not an event.** `done` carries only ids; the page fetches `GET /analyses/jobs/{id}` (a big
  project's result is megabytes). Stage + terminal events are kept for replay, step events only as the latest
  one, throttled to 200 ms — a 2000-file run must not be 2000 frames. All writes to one job's emitters (events
  and the 15 s heartbeat comment) go through the job's lock: two threads writing one connection interleave
  frames. `X-Accel-Buffering: no` keeps nginx / the OpenShift router from buffering the stream.
- `ConfigDiff`: SnakeYAML `SafeConstructor`, DOM with doctype disallowed — config from someone else's repo.
- Only `structural` refactorings (`RefactoringIndex.isStructural`) drive items; the migration run also
  yields CHANGE_ATTRIBUTE_TYPE / INLINE_VARIABLE ... by the hundred — the page hides them by default.

## Frontend

`src/pages/MigrationRisk/{index,AnalysisForm,ItemsTable,constants}`, `src/api/migrationRisk.ts`. Review notes use
one page-level `Modal` (not a popover per row — see [[creed-env-matrix]]); the reviewer name is kept in
`localStorage`. Diff links are plain `<a target=_blank>` to `diffUrl(source, file, target?)`, one button "repo1 vs repo2" (a
repo2-only row passes repo2's path). Module select + main/test Segmented filter the table; the form's
*Skip tests* switch is the backend `skipTests`. Stat boxes: HIGH / ATTENTION red, MEDIUM yellow, SAFE green,
from antd tokens (`color{Error,Warning,Success}{Bg,Border}`). Analyses run through
`useAnalysisJob` (EventSource; on `done`/`failed`/a closed stream it asks `GET /analyses/jobs/{id}` for the
terminal state, so a missed frame cannot strand the page) and `JobProgressCard`. The table's *Details* column
shows the first 3 detail lines (or the evidence), coloured by `+` / `-` / `~` prefix. One i18n key per backend `RiskCategory`
(`migrationRisk.category.*` + `.categoryHint.*`) and per `TargetState` / `ElementKind` / `UpstreamChange` —
adding an enum value backend-side needs both locale files. `CATEGORY_LEVEL` mirrors `RiskCategory.level`.
