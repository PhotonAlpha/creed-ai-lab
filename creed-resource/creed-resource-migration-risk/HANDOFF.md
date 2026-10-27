# creed-resource-migration-risk — handoff

**Purpose** Migration sync risk: repo2 is a migration of repo1 (Boot 4 / Jackson 3 / Camel 4) and another
team keeps committing to repo1. Compare repo1 HEAD with repo2 HEAD directly (no baseline; repo2 may be two
repositories), list what differs, where each repo1 element lives in repo2, and how risky syncing it is (HIGH /
MEDIUM / LOW); save the result under a **release** and let people **review** each file (SAFE / ATTENTION / SYNCED);
show any file in RefactoringMiner's side-by-side viewer (Thymeleaf page, repo1 left, repo2 right).
**Skill** `creed-resource-migration-risk` · shared: `creed-platform` · frontend: `../../creed-env-matrix-design` (`/migration-risk`)

## Run

```bash
# Postgres (creed-artifactory-db container) — create the database once:
docker exec creed-artifactory-db createdb -U artifactory migration_risk
mvn -pl creed-resource/creed-resource-migration-risk spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

# no Docker: file H2 database under migration-risk.work-dir, same Flyway migrations
mvn -pl creed-resource/creed-resource-migration-risk spring-boot:run -Dspring-boot.run.profiles=h2 -Dspring-boot.run.workingDirectory="$PWD"

# MySQL 8: db/migration-mysql (same versions, MySQL syntax); create database migration_risk utf8mb4 / utf8mb4_bin first
CREED_MIGRATION_RISK_DB_PASSWORD=... mvn -pl creed-resource/creed-resource-migration-risk spring-boot:run -Dspring-boot.run.profiles=mysql -Dspring-boot.run.workingDirectory="$PWD"
```

Plain HTTP **18098**. The pom pins **RefactoringMiner 3.1.6** (since 2026-10-25), compiled for Java 23: run it on
**JDK 25** (`JAVA_HOME`), a JDK 21 start fails with `UnsupportedClassVersionError`. 3.0.13 is the newest Java-21 build.
Repositories / folders must lie under `CREED_MIGRATION_RISK_ROOTS` (comma-separated; default `${user.home}`).
Other knobs: `CREED_MIGRATION_RISK_DB_URL|USER|PASSWORD`, `CREED_MIGRATION_RISK_WORK_DIR`,
`CREED_MIGRATION_RISK_MAX_FILES` (5000), `CREED_MIGRATION_RISK_DEMO=false`, `migration-risk.api-rules`,
`migration-risk.directory-excludes` in `application.yml`.

## API (`/api/migration-risk`)

| | |
|---|---|
| `GET /settings` | `demo` (git repo1 / repo2 / repo2B + the same versions as folders), `allowedRoots`, `apiRules`, `maxChangedFiles` |
| `GET /refs?path=&head=` | one repository: `currentBranch`, `head`, `branches`, `remoteBranches`, `tags`, `recentCommits` (back from `head`) |
| `POST /analyses` `{repo1Path, repo1Head?, repo2Path, repo2Head?, repo2bPath?, repo2bHead?, skipTests?, release?}` | git mode. `repo2bPath`: a second migrated repo, merged with repo2 (`target.repo` = `repo2A`/`repo2B`); `skipTests`: test code out of the comparison. `release` = save it (release created on first use); response gets `analysisId`, `release`, `items[].review` |
| `POST /analyses/directories` `{repo1Path, repo2Path, repo2bPath?, skipTests?, release?}` | folder mode: plain directories, no git; files as on disk (uncommitted included), `directory-excludes` skipped |
| `POST /analyses/jobs` · `POST /analyses/directories/jobs` (same bodies) | **background** analysis: 202 `{jobId}` after a quick check of paths / revisions (a bad branch is still an immediate 400 on its field); 503 `busy` beyond `migration-risk.jobs.concurrency` + `queue` |
| `GET /analyses/jobs/{id}/events` | **SSE**: `stage` / `progress` (`{stage, percent, done?, total?, detail, elapsedMillis}`) then `done` or `failed` (the error body); stages replayed on every (re)connect; `:` heartbeat every 15 s |
| `GET /analyses/jobs/{id}` · `DELETE /analyses/jobs/{id}` | status + last progress + the result (once DONE) or error; cancel (stops at the next progress report) |
| `GET /analyses/{id}` | a saved run, items with their current review |
| `GET /releases` · `GET /releases/{id}/analyses` | releases with the latest run's counts + review progress; a release's runs, newest first |
| `PUT /items/{id}/review` `{status, note?, reviewer?}` · `PUT /items/review` `{itemIds[], ...}` | `SAFE` / `ATTENTION` / `SYNCED` / `PENDING` (undo); `@Version` → 409 `review_conflict` |
| `GET /analyses/{id}/resolved` | the previous run's items this run no longer has (synced / reverted) |
| `GET|POST /ignore-rules` · `DELETE /ignore-rules/{id}` | `{pattern (Ant), category?, scope? (repo1 path/URL), note?}`; applied to every later analysis, counted in `warnings` |
| `POST /export?format=xlsx|md` (body = the analysis) | sync checklist; unsaved analyses export too |
| `POST /items/{id}/jira` | creates the item's Jira issue once (`review.jiraKey`); mock unless `migration-risk.jira.mock=false` + `base-url` |
| `GET /view/diff?mode=GIT|DIRECTORY&repo1Path=&repo1Head=&repo2Path=&repo2Head=&file=&target=` | **HTML** (Thymeleaf): RefactoringMiner's Monaco side-by-side view, repo1 HEAD left, repo2 right (+ `repo2bPath`/`repo2bHead`/`skipTests`); non-Java files as a line diff; `target` = an explicit repo2 file; Prev/Next, Overview, Legend; zh/en by `Accept-Language` |

Errors: `{error, message, fields?, time}` — 403 `path_not_allowed`, 400 `invalid_repository` / `validation_failed`,
404 `not_found`, 409 `review_conflict`, 422 `too_many_changes`, 500 `analysis_failed`. Path and revision errors
carry `fields[0].field` (`repo1Path`, `repo2Head`, `path`, ...) so the page marks that input.

## How it decides

`TreeDiff.diff(repo2, repo1)` over whole trees (content ids; old = repo2's path, new = repo1's; identical content at
another path = RENAMED, not reported). One RefactoringMiner run **repo1 → repo2** over the differing Java files +
repo2 same-name files (`RefactoringIndex`: before = repo1, after = repo2). `JavaRiskAnalyzer`, per repo1 class: find it
in repo2 (class move, same path, same file name; else `TARGET_MISSING` / `TARGET_SPLIT`), then per method: same
normalized text → nothing; different → `CONTENT_DIFFERS` (line diff of the method); not there → RM split
(`TARGET_SPLIT`), RM move/rename (`RELOCATED`, or `ALREADY_SYNCED` when identical), overload (`CONTENT_DIFFERS`), else
`TARGET_MISSING`; fields compared as sets; repo2-only methods / classes / files → `ONLY_IN_REPO2`. `FileRiskAnalyzer`:
pom dependency diff, `.vm` drafted as `.ftl` and diffed with repo2's (whitespace ignored; equal → `ALREADY_SYNCED`),
other files line by line (`NON_JAVA_FILE`), a repo1-only file found by name elsewhere → moved, API rules on repo1-only
lines. `GlobalConfigAnalyzer`: `key -> {file -> value}` per tree, a key differs when its value set differs; repo2
holding it only under the Boot new name → compared there (`CONFIG_IN_SYNC` when equal). `MigrationRiskService` drops
an API match alone in its file (the migration's own rewrite). Details read like `git diff repo1 repo2` (`-` = repo1,
`+` = repo2), the same direction as the diff page.

## Current state

2026-10-28: **baseline removed** (asked for): repo1 HEAD vs repo2 directly, one RM run, categories `CONTENT_DIFFERS` /
`ONLY_IN_REPO2` new (`BOTH_MODIFIED` / `DELETED_UPSTREAM` / `UPSTREAM_REFACTORING` / `AUTO_SYNC` / `NEW_CODE` kept only so
saved runs load), `SyncDetector` / `BaselineAdvisor` (now `RepositoryRefs`) / three-way view / diff side A gone,
`GitRepositories.diff` replaced by `TreeDiff` over any two trees. Flyway `V4` makes `baseline_ref` nullable. **MySQL**:
`db/migration-mysql` V1–V4 + `mysql` profile (`flyway-mysql`, `mysql-connector-j`), verified on a throwaway
`mysql:8.4` container: migrations applied, two runs saved with delta, review (Chinese note), Jira, load, releases.
Demo: `demo/repo1` (old `repo1-head`), 13 files (7 HIGH) vs repo2, 13 (5 HIGH) with repo2B. **63 tests pass.**
Frontend typecheck + build pass; not seen in a browser; not run on real repos since the change.
2026-10-28 (later): **Camel routes by route id** (`CamelRoutes` parser, `CamelRouteAnalyzer`; `ElementKind.ROUTE`),
across all route files of each tree; non-route content per file. Demo: repo1's `order-route.xml` gained `order-audit`
(moved to repo2's `camel/audit-route.xml`, `camel:` prefix — not reported) and `order-cancel` (missing in repo2 → HIGH):
13 files (8 HIGH) vs repo2, 6 HIGH with repo2B. **67 tests pass.**

2026-10-27 (later): sync tracking (`SyncDetector` → `ALREADY_SYNCED`, auto `SYNCED`, delta, resolved, review memory
with changed parts — Flyway `V3`), config renames (`ConfigRenames`), remote https repos (`RemoteRepositories`,
`RepositoryLocator`), ignore rules, export (POI), Jira (`JiraIssues`), three-way view, Velocity → FreeMarker drafts,
diff side B `target=`. Demo: `OrderValidator` (synced in repo2), `spring.redis.*` (renamed in repo2): 13 files.
**69 tests pass.** Frontend typecheck + build pass; pagination fix not seen in a browser.

2026-10-27: the diff page covers non-Java files too (line diff; `.vm` side B opens repo2's `.ftl`), so every row
has both diff buttons. **59 tests pass.**
2026-10-27: **one record per file** — element-level findings are `items[].parts`; the demo gives 12 file records
(5 HIGH) with repo2 alone, 12 (4 HIGH) with repo2B, 11 with `skipTests`. **58 tests pass**. Reviews saved on
element rows do not carry over to file rows (different item keys).

2026-10-26: repo2A + repo2B, `skipTests`, module / test tags, `.vm → .ftl`, global config keys, diff B = repo1
head → repo2 head, readable diff names. Demo grew `repo2b` (templates as `.ftl`, the test, `config/application.yml`),
an `invoice.vm` / `receipt.vm`, a test class and a key moved to `application.properties`: 16 items (repo2 alone) /
16 with repo2B (4 HIGH) / 15 with `skipTests`. **57 tests pass**. Flyway `V2` adds `mr_analysis.repo2b_path`.
Not yet run against the real repos or seen in a browser.

Earlier: **42 tests pass** on JDK 25 (`MigrationRiskApiTest` pins every category on the demo and checks folder mode gives
the identical items; `ReleaseReviewApiTest` save / review / carry-over on H2 **with Flyway**; `DiffViewTest`;
unit tests). Verified 2026-10-25 over HTTP through the Vite proxy on a real pair — repo1 `creed-ai-lab-ops`
(`master`), repo2 `creed-ai-lab` (`develop/spring-boot-4`): suggested baseline `c9025a4`, 9 items, saved as release
`R-demo-1`, review persisted across a restart (h2 profile), diff page side A lists the 4 new `creed-none-author`
files. Against PostgreSQL 15 (`creed-artifactory-db`, database `migration_risk`): Flyway applied `V1`, two runs
of release `R2026.11` saved, the second carried 6 verdicts over (5 SAFE + 1 ATTENTION; 3 left PENDING).
The diff page and the new frontend were **not seen in a browser** (the extension could not attach).

SSE verified 2026-10-25 through the Vite proxy on a large run (repo1 `creed-ai-lab` 30 commits back → 726 changed
files / 364 Java, 782 items, ~330 s): stages replayed on re-subscribe, heartbeats every 15 s for the whole of run A,
live `MIGRATION` / `JAVA` / `done` events — no buffering.

## Open items

- Progress inside a RefactoringMiner run is unknowable (one call, no callback): on 2026-10-25 a run over 364 Java
  files took ~5.5 min with nothing to report but elapsed time. Without a baseline the run covers every differing
  Java file — on a migrated project most of them. Cancelling does not interrupt a run in
  flight — the job stops at the next report. Jobs live in memory (lost on restart; kept `jobs.retention` = 30 min).
- The diff view recomputes the AST diff per inputs — cached, 8 entries — synchronously.
- The diff page loads Bootstrap / jQuery / Monaco 0.39 from CDNs (as RefactoringMiner's own page does): it needs
  internet access in the browser.
- No export, no Backstage entry, not routed by `creed-gateway-proxy`. No accounts: `reviewer` is free text.
- No baseline = no way to tell the migration's rewrites from new upstream work: both are `CONTENT_DIFFERS`.
- Camel routes are compared by route id for the XML DSL only; the Java DSL is compared as Java (method by method), the YAML DSL as a plain file.
