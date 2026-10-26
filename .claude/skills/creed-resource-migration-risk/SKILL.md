---
name: creed-resource-migration-risk
description: creed-resource-migration-risk — migration sync risk between an old repo and its migrated copy (RefactoringMiner directory mode twice on one baseline, JGit, JDT; git branches or plain folders), the risk categories and how JavaRiskAnalyzer/FileRiskAnalyzer decide them, releases + item review in PostgreSQL (Flyway), the Thymeleaf AST-diff page, the demo repositories, and the /migration-risk page of creed-env-matrix-design. Use for risk rules, RefactoringMiner wiring, repository access, persistence/review, the diff view, the demo fixtures, or the page.
---

# creed-resource-migration-risk

Plain HTTP **18098**, no PKI. PostgreSQL `migration_risk` on `creed-artifactory-db` (or the `h2` profile) for
releases and reviews; the analysis itself is computed per request. Page `/migration-risk` in `creed-env-matrix-design`
(Vite proxy `/api/migration-risk` → `VITE_MIGRATION_RISK_TARGET`). See [[creed-platform]] for build/run,
[[creed-env-matrix]] for frontend conventions, [[creed-resource-veracode]] for the sibling module's shape.

## Shape

- `git/GitRepositories` — JGit, read-only: resolve, `diff` (rename detection + added line numbers), `read`,
  `blobId`, `export` a path subset into a scratch dir. Never checks out or touches the user's working tree.
- `service/RefactoringDetector` — RefactoringMiner `detectAtDirectories`, reduced to `DetectedRefactoring`
  / `CodeLocation` records (nothing else sees its API). `RefactoringIndex` indexes one run by its *before*
  side: method relocations / splits matched by **file + overlapping line range**, class moves by FQCN.
- `service/JavaStructure` — JDT parse: types, methods (`name(Type,...)`, constructors `<init>`), fields,
  each with lines and a **normalized** text (JDT `toString()` minus Javadoc, whitespace collapsed).
- `JavaRiskAnalyzer` (Java elements) + `FileRiskAnalyzer` (pom / Spring config via `ConfigDiff`, other
  files by blob id, `migration-risk.api-rules` on added lines) → `RiskItem`s; `MigrationRiskService`
  orchestrates, sorts, dedupes by id. `RepositoryGuard` = allowed roots.
- `demo/DemoRepositories` — `src/main/resources/demo/{repo1-baseline,repo1-head,repo2}` committed into two
  JGit repos at startup (tag `migration-baseline`, fixed idents/times → stable ids). Each upstream change in
  `repo1-head` targets exactly one category; `MigrationRiskApiTest` pins all twelve items. **Changing a
  fixture changes the test's expectations.**

- `source/` — `SourceTree` = `GitTree` (a commit) | `DirectoryTree` (a folder, `directory-excludes` skipped);
  `DirectoryDiff` = git-diff for folders (content ids are **git blob ids** in both, histogram diff for added
  lines, exact-content renames). `MigrationRiskService.withTrees(Inputs, fn)` opens either kind — the analysis
  and the diff view both start there.
- `source/MergedTree`: repo2A + repo2B as one tree — **paths not prefixed** (a module moved to repo2B at the same
  relative path must still match), first member wins on a collision (warning), `origin(path)` → item `target.repo`.
- `GlobalConfigAnalyzer`: config compared by **global key** across all `config-files` (`ConfigDiff.globalKeys`:
  bare key for the default profile, `[dev] key`, `[test] key`, `[messages_zh.properties] key`); change = the key's
  value *set* changed. Parsed once per content id. `FileRiskAnalyzer` skips these files (still API rules).
- `CodeTags`: `module` (nearest build-file dir) + `test` from the path alone; `skipTests` filters `changes` in
  `withTrees`, so the analysis, RefactoringMiner's input and the diff view all agree.
- `ReleaseStore` + `domain/Mr*` (Flyway `V1`, `V2` = `repo2b_path`): `mr_release` / `mr_analysis` (result JSON minus items) / `mr_item`
  (item JSON, review columns, `@Version`). Re-runs carry verdicts by `item_key` + `fingerprint`.
- `DiffViewService` + `web/DiffViewController` + `templates/diff.html`: `diffAtDirectories` on the same exported
  subset → `DirComparator` → RefactoringMiner's own `MonacoCore.makeDiffConfig()`; its `web/dist/*.js|css` are
  served from its jar at `/api/migration-risk/view/assets/`. Added / deleted files get a one-sided config.

- `AnalysisJobs` + `Progress`: background analyses on their own bounded pool, progress pushed over SSE (`SseEmitter`).
  `Progress.Stage` bands (RESOLVE 0 … MIGRATION 45, JAVA 80 … DONE 100); the job's `Progress` throws
  `CancelledException` at the next report after `DELETE`. `ApiErrors` maps exceptions to the error body for both
  the exception handler and the `failed` event.

## Rules that are easy to break

- **Diff side B is head → repo2, the analysis's run B is baseline → repo2.** Run B must share its left side with
  run A for the cross-check; a reader porting a change wants repo1's current code on the left. Don't "unify" them.
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
- Both runs share the exported `before/` tree, so a *before* location means the same code in A and B.
  Export only changed files (+ repo2 same path + same file name, capped by `max-candidates-per-name`):
  whole repos are slow and memory-hungry.
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
- **The baseline is the input people get wrong.** The page prefilled the demo tag `migration-baseline`;
  replacing only the two paths then failed every real analysis with "unknown revision". The page now clears
  a demo baseline when repo1 changes and fills an empty one from `GET /refs`' merge-base suggestion
  (`BaselineAdvisor`, JGit `RevFilter.MERGE_BASE` in whichever repository holds both HEAD commits).
- `ConfigDiff`: SnakeYAML `SafeConstructor`, DOM with doctype disallowed — config from someone else's repo.
- Only `structural` refactorings (`RefactoringIndex.isStructural`) drive items; the migration run also
  yields CHANGE_ATTRIBUTE_TYPE / INLINE_VARIABLE ... by the hundred — the page hides them by default.

## Frontend

`src/pages/MigrationRisk/{index,AnalysisForm,ItemsTable,constants}`, `src/api/migrationRisk.ts`. Review notes use
one page-level `Modal` (not a popover per row — see [[creed-env-matrix]]); the reviewer name is kept in
`localStorage`. Diff links are plain `<a target=_blank>` to `diffUrl(source, side, file)`, labelled "Upstream change" /
"repo1 vs repo2" (never bare A / B — asked for). Module select + main/test Segmented filter the table; the form's
*Skip tests* switch is the backend `skipTests`. Stat boxes: HIGH / ATTENTION red, MEDIUM yellow, SAFE green,
from antd tokens (`color{Error,Warning,Success}{Bg,Border}`). Analyses run through
`useAnalysisJob` (EventSource; on `done`/`failed`/a closed stream it asks `GET /analyses/jobs/{id}` for the
terminal state, so a missed frame cannot strand the page) and `JobProgressCard`. The table's *Details* column
shows the first 3 detail lines (or the evidence), coloured by `+` / `-` / `~` prefix. One i18n key per backend `RiskCategory`
(`migrationRisk.category.*` + `.categoryHint.*`) and per `TargetState` / `ElementKind` / `UpstreamChange` —
adding an enum value backend-side needs both locale files. `CATEGORY_LEVEL` mirrors `RiskCategory.level`.
