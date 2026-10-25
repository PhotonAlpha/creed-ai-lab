# creed-resource-migration-risk — handoff

**Purpose** Migration sync risk: repo2 is a migration of repo1 (Boot 4 / Jackson 3 / Camel 4) and another
team keeps committing to repo1. Given the baseline, list what repo1 changed since, where each changed element
lives in repo2, and how risky syncing it is (HIGH / MEDIUM / LOW); save the result under a **release** and let
people **review** each item (SAFE = expected, ATTENTION = needs special care); show any file as RefactoringMiner's
side-by-side AST diff (Thymeleaf page).
**Skill** `creed-resource-migration-risk` · shared: `creed-platform` · frontend: `../../creed-env-matrix-design` (`/migration-risk`)

## Run

```bash
# Postgres (creed-artifactory-db container) — create the database once:
docker exec creed-artifactory-db createdb -U artifactory migration_risk
mvn -pl creed-resource/creed-resource-migration-risk spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

# no Docker: file H2 database under migration-risk.work-dir, same Flyway migrations
mvn -pl creed-resource/creed-resource-migration-risk spring-boot:run -Dspring-boot.run.profiles=h2 -Dspring-boot.run.workingDirectory="$PWD"
```

Plain HTTP **18098**. The pom pins **RefactoringMiner 3.1.6** (since 2026-10-25), compiled for Java 23: run it on
**JDK 25** (`JAVA_HOME`), a JDK 21 start fails with `UnsupportedClassVersionError`. 3.0.13 is the newest Java-21 build.
Repositories / folders must lie under `CREED_MIGRATION_RISK_ROOTS` (comma-separated; default `${user.home}`).
Other knobs: `CREED_MIGRATION_RISK_DB_URL|USER|PASSWORD`, `CREED_MIGRATION_RISK_WORK_DIR`,
`CREED_MIGRATION_RISK_MAX_FILES` (2000), `CREED_MIGRATION_RISK_DEMO=false`, `migration-risk.api-rules`,
`migration-risk.directory-excludes` in `application.yml`.

## API (`/api/migration-risk`)

| | |
|---|---|
| `GET /settings` | `demo` (git pair + baseline tag + the same versions as three folders), `allowedRoots`, `apiRules`, `maxChangedFiles` |
| `GET /refs?path=&head=` | one repository: `currentBranch`, `head`, `branches`, `remoteBranches`, `tags`, `recentCommits` (back from `head`) |
| `GET /baseline-suggestion?repo1Path=&repo1Head=&repo2Path=&repo2Head=` | merge-base of the two selected heads (`MERGE_BASE`) when one object DB holds both — two clones of one origin — else `NO_SHARED_HISTORY` |
| `POST /analyses` `{repo1Path, baseline, repo1Head?, repo2Path, repo2Head?, release?}` | git mode. `release` = save it (release created on first use); response gets `analysisId`, `release`, `items[].review` |
| `POST /analyses/directories` `{baselinePath, repo1Path, repo2Path, release?}` | folder mode: three plain directories, no git; files as on disk (uncommitted included), `directory-excludes` skipped |
| `GET /analyses/{id}` | a saved run, items with their current review |
| `GET /releases` · `GET /releases/{id}/analyses` | releases with the latest run's counts + review progress; a release's runs, newest first |
| `PUT /items/{id}/review` `{status, note?, reviewer?}` · `PUT /items/review` `{itemIds[], ...}` | `SAFE` / `ATTENTION` / `PENDING` (undo); `@Version` → 409 `review_conflict` |
| `GET /view/diff?mode=GIT|DIRECTORY&...&side=A|B&file=` | **HTML** (Thymeleaf): RefactoringMiner's Monaco side-by-side view. A = baseline → repo1 head, B = baseline → repo2; Prev/Next, Overview, Legend; zh/en by `Accept-Language` |

Errors: `{error, message, fields?, time}` — 403 `path_not_allowed`, 400 `invalid_repository` / `validation_failed`,
404 `not_found`, 409 `review_conflict`, 422 `too_many_changes`, 500 `analysis_failed`. Path and revision errors
carry `fields[0].field` (`repo1Path`, `baseline`, `path`, ...) so the page marks that input.

## How it decides

Two RefactoringMiner runs on one baseline: **A** baseline → repo1 head, **B** baseline → repo2, over the files repo1
changed only (`SourceTree`: a git commit or a folder — the analysis does not care which). Per changed method /
field set / class (`JavaRiskAnalyzer`): B split it → `TARGET_SPLIT`; B moved/renamed it → `RELOCATED`; else find
the class and the method by signature: identical normalized text → `AUTO_SYNC`, different → `BOTH_MODIFIED`,
absent → `TARGET_MISSING`. `FileRiskAnalyzer`: pom / Spring config key diffs with repo2's value per key, other
files by content id, API rules on added lines. Saving (`ReleaseStore`): a re-run of a release copies each
verdict whose item **fingerprint** (content minus line numbers) is unchanged (`carriedOverFrom`).

## Current state

**42 tests pass** on JDK 25 (`MigrationRiskApiTest` pins every category on the demo and checks folder mode gives
the identical items; `ReleaseReviewApiTest` save / review / carry-over on H2 **with Flyway**; `DiffViewTest`;
unit tests). Verified 2026-10-25 over HTTP through the Vite proxy on a real pair — repo1 `creed-ai-lab-ops`
(`master`), repo2 `creed-ai-lab` (`develop/spring-boot-4`): suggested baseline `c9025a4`, 9 items, saved as release
`R-demo-1`, review persisted across a restart (h2 profile), diff page side A lists the 4 new `creed-none-author`
files. Against PostgreSQL 15 (`creed-artifactory-db`, database `migration_risk`): Flyway applied `V1`, two runs
of release `R2026.11` saved, the second carried 6 verdicts over (5 SAFE + 1 ATTENTION; 3 left PENDING).
The diff page and the new frontend were **not seen in a browser** (the extension could not attach).

## Open items

- Synchronous analysis; no progress, no cancellation, no concurrency limit. The diff view recomputes the AST diff
  per (inputs, side) — cached, 8 entries.
- The diff page loads Bootstrap / jQuery / Monaco 0.39 from CDNs (as RefactoringMiner's own page does): it needs
  internet access in the browser.
- No export, no Backstage entry, not routed by `creed-gateway-proxy`. No accounts: `reviewer` is free text.
- Folder mode needs a baseline *folder*; two folders alone cannot separate migration changes from new ones.
