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

- **Sync tracking** — re-run a release as the sync goes on. A finding repo2 already has in repo1 HEAD's form
  (same method, fields, file, pom values; or the replaced API gone from repo2's file) is `ALREADY_SYNCED`, and a file
  made only of those is reviewed `SYNCED` automatically. Each file is tagged NEW / CHANGED / SAME against the previous
  run, and the *Resolved* tab lists what the previous run had and this one does not. A file that changed after its
  review comes back pending, showing the old verdict and which findings are new or different.
- **Config renames** — `spring.redis.host` changed upstream is looked up in repo2 as `spring.data.redis.host` too:
  Spring Boot's own deprecation metadata plus `migration-risk.config-renames` (prefix renames end in `.`).
- **Remote repositories** — in git mode a repository can be an `https://` URL from `remotes.allowed-hosts`: cloned
  bare under the work dir, fetched before use; tokens from `remotes.credentials` (environment), never the request.
- **Ignore rules**, **Excel / Markdown export**, **Jira issue per file** (mocked unless `migration-risk.jira` is set),
  **three-way view** (baseline | HEAD | repo2, lines marked by who changed them) and FreeMarker drafts (`≈` lines) for
  changed Velocity templates.

- **One record per file** — every changed file is one row to review; the methods, config keys and API matches
  found in it are its `parts` (shown when the row is expanded). The file takes its riskiest part's level. Line
  by line differences are what the diff views are for.

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

## 体验升级
一、最推荐的三项

1. 对每个文件做三方合并，用"能不能自动合并"来定风险
- 现在的风险等级是按规则推出来的，比如"两边都改了"就判中风险。但"两边都改了"可能完全不冲突，也可能冲突很严重。
- 可以对每个文件做三方合并：基线作公共祖先，repo2 作我方，repo1 head 作对方，用 JGit 自带的合并算法。
  - 合并无冲突：降为低风险，标记"可自动合并"。
  - 有冲突：详情里直接展示冲突的代码块。
- 文件被迁移挪了位置时，就合并到 repo2 里的新路径。
- 再进一步：对所有可自动合并的文件生成补丁，或者直接在 repo2 上建一个同步分支。这是对用户最大的提效。

2. 跨多次分析跟踪同步进度
- 现在复核只有三种状态：待复核、安全、需关注。没人知道"需关注"的那些后来同步了没有。
- 新增"已同步"状态：重新分析同一个 release 时自动判断。repo2 里该文件的内容已等于 repo1 head 的，或配置项已是新值，就自动标成已同步。
- 两次分析之间的对比视图：新出现了哪些、已解决哪些、还剩哪些。
- 这样每个 release 的进度就有了：页面上显示"已同步 / 总数"，统计框也就真正有用了。

3. 缓存，降低大项目的耗时
- 之前的真实运行里，repo1 那次 RefactoringMiner（上游 baseline → head）一次就花了约 5.5 分钟，而且一次调用内部不汇报进度。
- 按文件内容缓存结果：同一个 release 重跑时，只重新分析内容变过的文件。重跑的耗时应该能从几分钟降到几秒。
- 跳过不需要的对比：repo2 里内容与基线完全相同的文件，迁移根本没碰过，不需要再跑基线 → repo2 那一侧的对比。
- 其他：
  - 两次对比互相独立，可以并行跑。
  - 后台任务和对比视图的缓存现在只放在内存里，重启就没了。可以存进数据库。

二、准确性：现在已知会误判的地方

- 多模块项目的全局配置比较：键名里不带模块。模块 A 的 server.port 改了，而 repo2 只有模块 B 恰好是这个值，也会被判成"已同步"。应该提供按模块匹配的选项，或者优先比较同一模块。
- Boot 4 改名的配置项：比如 spring.redis.* 改成了 spring.data.redis.*，现在一律报"repo2 缺失"（高风险）。Spring Boot 的 jar 里自带配置元数据，里面写着哪些键改成了什么。读出来就能识别成"已改名，repo2 已有新键"，能去掉一大类误报。
- pom 依赖：现在看不出版本由 BOM 管理的情况（显示为 managed）。如果解析出实际生效的版本，"repo1 升了版本，repo2 的 BOM 本来就更新"就不会再是高风险。
- repo2A / repo2B 有同路径文件时：只比较 repo2A 的那份。对于根目录的 pom.xml、README 这类文件，这几乎必然发生，应该两份都比，或者让用户指定以哪个为准。
- 配置项落在 repo2B 的另一个文件时：「repo1 对比 repo2」的 diff 右边是空的。应该改为打开配置项实际所在的那个文件。
- 按文件合并之后的复核：文件里任何一处发现变了，整个文件就回到待复核。可以记住每处发现的结论，只把"复核之后变了的那几处"高亮出来。

三、使用体验

- 按模块汇总：结果表上方加一个按模块分组的小表格或热力图，显示各模块高/中/低风险数。大项目里人们更习惯先按模块分工。
- 大结果集：上千个文件时，表格需要后端分页或虚拟滚动；筛选条件放进 URL，方便把链接发给同事。
- 两边都改了的文件，用三栏视图：基线、repo1 head、repo2 并排显示，比在两个 diff 之间来回切换直观得多。如果做了第一项的三方合并，第四栏可以放合并结果。
- .vm → .ftl 给出转换建议：上游改动的那些行可以按简单规则自动翻译成 FreeMarker（如 $x → ${x}、#foreach → <#list>），作为参考贴在详情里。
- 忽略规则：提供按路径（文件模式）或类别的忽略规则，按项目保存，用于生成代码、文档、CI 配置等。现在每次分析都要人工把它们标成安全。
- 导出与集成：
  - 导出 Excel 或 Markdown 同步清单。
  - 把"需关注"的条目一键建 Jira 任务。veracode 模块已有 Jira 客户端和 mock，可以复用。
- 复核人身份：现在复核人是随手填的文本。项目里有自己的认证服务（auth server），接上后复核记录才可信。

四、项目级能力

- 支持远程仓库：现在只能读服务器本地路径。支持 git URL + 凭据并自动 fetch，就不用先手工 clone 到服务器上。
- 定时分析：比如每天夜里对 release 分支自动跑一次，出现新的高风险项就通知负责人。配合第一节第 2 项，就成了持续的同步监控。

如果要选，我建议的顺序是：第一节第 1 项（三方合并）→ 第一节第 2 项（同步进度跟踪）→ 第二节的 Boot 配置改名识别。三项都能直接减少人工复核量。另外，任何新功能之前都值得先在 creed-ai-lab-ops / creed-ai-lab 这对真实仓库上跑一遍最近加的功能，确认没有大量误报。要的话我可以从这里开始，也可以把这份分析整理成可分享的页面。

### 实现状态（2026-10-27）

已实现：第一节第 2 项（同步进度跟踪：`ALREADY_SYNCED` / 复核状态 `SYNCED`、与上一次分析的对比 NEW / CHANGED / SAME、"已解决"列表）；第二节的 Boot 配置改名识别、配置项落在 repo2B 另一个文件时的 diff、按文件合并之后的复核记忆；第三节除复核人身份外的全部（按模块汇总、筛选条件和分页放进 URL、三方对比视图、`.vm → .ftl` 转换建议、忽略规则、Excel / Markdown 导出、一键建 Jira）；第四节的远程仓库（https:// git URL）。另修复了页面分页（每页条数切换无效、筛选后停在空页）。

## TODO

- [ ] **三方合并定风险**（第一节第 1 项）：先在真实项目上核验现有结果，再决定是否实现。
- [ ] **复核人身份**：接入 creed-author-server（OAuth2），复核记录用登录身份代替手填的名字。
- [ ] 第一节第 3 项（按文件内容缓存、跳过不需要的对比、两次运行并行）。
- [ ] 第二节其余：多模块的全局配置按模块匹配、pom 解析 BOM 实际版本、repo2A / repo2B 同路径文件两份都比。
- [ ] 第四节：定时分析 + 新的高风险项通知。
