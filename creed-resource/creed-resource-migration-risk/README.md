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

What it cannot tell: behaviour that changed under identical code (Jackson 3's defaults, for example) —
that is what tests are for. Run notes, API and limits: [`HANDOFF.md`](HANDOFF.md).
