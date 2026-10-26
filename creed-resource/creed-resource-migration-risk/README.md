# creed-resource-migration-risk

Migration sync risk for a codebase that was migrated (Spring Boot 4, Jackson 3, Camel 4 ...) while another
team keeps committing to the old repository. Page: `/migration-risk` in `creed-env-matrix-design`.

**Inputs** — either **git**: repo1 (old) path + branch, the **baseline** (the repo1 commit the migration started
from, or the last sync — suggested automatically as the merge-base when the two repositories are clones of one
origin), repo2 (migrated) path + branch; or **three plain folders**: baseline / repo1 / repo2, no git at all.
The two repositories need no shared history. Optionally a **release** name: the result is saved, each item can be
reviewed as *safe* (expected) or *needs attention*, and a re-run of the release keeps the verdicts of unchanged items.
Every Java file can be opened as RefactoringMiner's side-by-side AST diff (Thymeleaf page, side A = upstream change,
side B = where the migration put it).

### repo1 branch, baseline, repo2 branch — what each one is

They are the three points in time the comparison uses:

```
repo1:  ──●─────────●────●   ← repo1 branch (now)
          ↑ baseline   the other team's new commits
           \
repo2:      ●────●────●──●   ← repo2 branch (now)
              the migration's commits
```

| Input | What it is | Role in the comparison |
|---|---|---|
| **repo1 branch** | the old repository's branch the other team still commits to (e.g. `master`); its newest commit is used | end of A: "what repo1 looks like now" |
| **Baseline** | repo1's commit when the migration started (or at the last sync) — a commit id, tag or branch | the **shared start** of A and B; must be in the repo1 branch's history |
| **repo2 branch** | the migrated repository's working branch (e.g. `develop/spring-boot-4`); its newest commit is used | end of B: "what the migrated code looks like now" |

- **A = baseline → repo1 branch**: what the other team changed since — what has to be synced.
- **B = baseline → repo2 branch**: where the migration put the baseline code and what it made of it — the
  old element → new element map.

Every element A touches is looked up through B, so the baseline decides what counts as new:

- **Too early** — changes made before the migration started show up as new: lots of items that need no syncing.
- **Too late** — changes before the baseline that were never synced **do not appear at all**. This is the
  dangerous direction: nothing tells you they were missed.
- **Two clones of one origin** — the page suggests the merge-base of the two selected branches, which is normally
  exactly the commit the migration branched from. After a sync, the next baseline is the repo1 commit synced up to.
- **A baseline that is not in the repo1 branch's history** raises a warning: A then includes another branch's changes.

**Folder mode**: baseline folder = baseline, repo1 folder = repo1 branch, repo2 folder = repo2 branch.
API fields: `baseline` / `repo1Head` / `repo2Head`; in folder mode `baselinePath` / `repo1Path` / `repo2Path`.

**Method** — baseline + two analyses + cross-check:

1. **A** = RefactoringMiner on repo1@baseline → repo1@HEAD: what the other team changed.
2. **B** = RefactoringMiner on repo1@baseline → repo2@HEAD: an old element → new element map.
3. Every element A touches is looked up through B; non-Java files are compared key by key (Spring config,
   `pom.xml`) or byte by byte; lines repo1 added are checked against migration-sensitive API rules.

Comparing repo1@HEAD with repo2@HEAD directly would mix *expected migration differences* with *new upstream
commits*; the shared baseline separates them.

| Level | Category | When |
|---|---|---|
| HIGH | `TARGET_MISSING` / `TARGET_SPLIT` | repo1 changed something repo2 deleted, or extracted/inlined/split |
| HIGH | `MIGRATION_API` | added upstream lines use an API the migration replaced (`migration-risk.api-rules`) |
| HIGH | `CONFIG_CHANGED` / `DEPENDENCY_CHANGED` | `application*.yml`/`.properties` / `pom.xml` changed; each key with repo2's value |
| MEDIUM | `RELOCATED` | the element is elsewhere in repo2 (new location given) |
| MEDIUM | `BOTH_MODIFIED` | the migration changed it too — three-way merge |
| MEDIUM | `DELETED_UPSTREAM` | repo1 deleted it, repo2 still has it |
| MEDIUM | `UPSTREAM_REFACTORING` | repo1 renamed / moved / extracted it — replay in repo2 |
| LOW/MEDIUM | `NON_JAVA_FILE` | other files: low if repo2 still has the baseline version |
| LOW | `AUTO_SYNC` | repo2 has the element unchanged since the baseline (imports aside) |
| LOW | `NEW_CODE` | new upstream code whose home exists in repo2 |

## What RefactoringMiner does here

[RefactoringMiner](https://github.com/tsantalis/RefactoringMiner) compares two versions of Java code and reports
the **refactorings** between them — a class moved or renamed, a method renamed, moved, pulled up, extracted,
inlined, split — each with the exact file and line range on both sides. It also produces an **AST diff**. It works
on two directories with no git history needed, which is what lets repo1 be compared with a repo2 that does not
share its history.

This module uses it in exactly three places, always on the exported subset of files repo1 changed:

| Use | Call | What it answers | Feeds |
|---|---|---|---|
| **Run A** — repo1 baseline → repo1 head | `detectAtDirectories` | Which structural refactorings the other team made upstream | `UPSTREAM_REFACTORING` items; pairs a moved file's old and new version; stops an upstream rename from showing up as one deleted and one added method |
| **Run B** — repo1 baseline → repo2 head | `detectAtDirectories` | Where the migration put each baseline element: the **old element → new element map** | `RELOCATED` (moved / renamed method or class, with its new location), `TARGET_SPLIT` (extracted / inlined / split / merged), and the class lookup behind every other category |
| **Diff view** — the same pair as A or B | `diffAtDirectories` + `MonacoCore.makeDiffConfig()` | Which code was inserted, deleted, updated or moved, and where to | The Thymeleaf side-by-side page (`/api/migration-risk/view/diff`) |

Example (demo): run B reports `Move Class OrderProcessor → order.processing.OrderProcessor` and `Rename Method
handle → process`, with line ranges. The upstream edit to `OrderProcessor.handle()` is matched against that range,
so the item says "now at `processing/OrderProcessor.java:9`, `process(orderId String)`" instead of "method gone".

**What it does not do — this module does:**

- **Plain modifications.** RefactoringMiner reports refactorings, not edits: a method whose body changed without
  being moved or renamed does not appear in its output. Which methods and fields repo1 modified, and whether
  repo2 still has the baseline version, comes from comparing JDT-normalized declarations (`JavaStructure`).
- **Risk grading** (`JavaRiskAnalyzer`, `FileRiskAnalyzer`), **non-Java files** (`ConfigDiff` for pom / Spring
  config, content ids for the rest), **migration-API rules**, **git access** (JGit), **persistence and review**.
- Only structural refactorings drive items (`RefactoringIndex.isStructural`). The migration also produces
  hundreds of detail refactorings (variable renames, type changes, annotations); they are listed in the page's
  A / B tabs but hidden by default.

Technical constraints (details in the skill): the version is pinned in the pom (3.1.x = Java 23 bytecode → JDK 25);
several of its dependencies are excluded (Jetty 9, Spring 4.1.6, GitHub/Perforce clients); its jar's own
`logback.xml` is overridden by `logging.config`; directories must be passed as real absolute paths.

## Options and what the table adds

- **repo2B** — when the migration split repo1 into two repositories, give the second one as `repo2bPath`
  (and `repo2bHead`). repo2 and repo2B are read as one code base: a class, template or config key found in
  either counts as found, and each item's target says `repo2A` or `repo2B`.
- **Skip tests** (`skipTests`) — test code (`src/test/**`, `*Test.java`, `*IT.java`, test resources) is left
  out before anything is compared. Without it, every item still carries a `test` flag and a `module` (nearest
  directory with a `pom.xml` / `build.gradle`), which the page filters on.
- **Templates converted by the migration** — a changed `x.vm` that repo2 has as `x.ftl` is `TEMPLATE_CONVERTED`
  (MEDIUM), with the upstream line diff to port by hand. `migration-risk.extension-mappings` holds the pairs.
- **Config keys, globally** — every properties / yml file (Spring's `application*` / `bootstrap*`, plus
  `config-files`) is reduced to keys named without the file: `shop.order.max-items`, `[dev] server.port`. A key
  moved from `application.yml` to `application.properties` is no change; a key repo2 keeps in another file is
  found. Each changed key is one item: `CONFIG_CHANGED` (HIGH) or, when repo2 already has the new value,
  `CONFIG_IN_SYNC` (LOW).
- **Diff views** — *Upstream change* = repo1 baseline → repo1 HEAD; *repo1 vs repo2* = repo1 HEAD → repo2 HEAD.

## Limits

What it cannot tell: behaviour that changed under identical code (Jackson 3's defaults, for example) —
that is what tests are for. Run notes, API and limits: [`HANDOFF.md`](HANDOFF.md).
