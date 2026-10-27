# creed-resource-migration-risk

Migration sync risk for a codebase that was migrated (Spring Boot 4, Jackson 3, Camel 4 ...) while another
team keeps committing to the old repository. Page: `/migration-risk` in `creed-env-matrix-design`.

**Inputs** — either **git**: repo1 (old) path or https URL + branch, repo2 (migrated) path + branch, optionally
repo2B (+ branch); or **plain folders**: repo1 / repo2 / repo2B, no git at all. The repositories need no shared
history, and there is **no baseline**: repo1 HEAD is compared with repo2 HEAD directly (repo2A + repo2B read as one
code base). Optionally a **release** name: the result is saved, each file can be reviewed as *safe* (expected),
*needs attention* or *synced*, and a re-run of the release keeps the verdicts of unchanged files. Every file can be
opened in RefactoringMiner's side-by-side viewer (repo1 left, repo2 right).

**Method**

1. Every file that differs between repo1 and repo2 (content ids; an identical file at another path is a move,
   not a difference).
2. **One RefactoringMiner run, repo1 → repo2**, over the differing Java files (plus repo2 files with the same name):
   where repo2 moved, renamed, extracted or split repo1's classes and methods.
3. Java classes compared member by member on JDT-normalized text — formatting, Javadoc and **imports do not count**,
   so a file whose only difference is `javax.* → jakarta.*` imports reports nothing.
4. Config compared key by key across all properties / yml files of both repo2s; `pom.xml` dependency by dependency;
   Camel XML routes by route id; Velocity templates drafted as FreeMarker and diffed with repo2's `.ftl`; any other
   file (scripts, other XML ...) line by line.
5. Lines repo1 has and repo2 lacks are checked against `migration-risk.api-rules`; a match **alone** in a file is
   taken as the migration's own rewrite and dropped, next to another difference it stays as a porting hint.

**Without a baseline, who made a difference cannot be known**: a method the migration rewrote and a method the
other team changed look the same — both `CONTENT_DIFFERS`. The diff page shows which. Details lines read like
`git diff repo1 repo2`: `- L12:` is repo1's line, `+ L12:` repo2's.

| Level | Category | When |
|---|---|---|
| HIGH | `TARGET_MISSING` | repo1 has the class / method / file, repo2 has it nowhere (same path, same name, a move) |
| HIGH | `TARGET_SPLIT` | repo2 extracted / inlined / split / merged it — no single counterpart |
| HIGH | `CONFIG_CHANGED` | a config key has another value in repo2, or none (also checked under its Boot new name) |
| HIGH | `DEPENDENCY_CHANGED` | the poms differ: dependency, managed version, property, parent |
| MEDIUM | `CONTENT_DIFFERS` | both have the method / fields / class, with different content |
| MEDIUM | `RELOCATED` | repo2 has it under another class, package or name, and it differs |
| MEDIUM | `MIGRATION_API` | repo1-only lines use an API the migration replaced — convert while porting |
| MEDIUM | `TEMPLATE_CONVERTED` | `x.vm` is `x.ftl` in repo2 and differs from the drafted conversion |
| MEDIUM | `NON_JAVA_FILE` | any other file differs (XML, scripts ...); the line diff in the details |
| LOW | `ONLY_IN_REPO2` | repo2 has a class / method / file / config key repo1 does not (usually the migration's) |
| LOW | `ALREADY_SYNCED` | repo2 has the same thing elsewhere: moved / renamed and identical, or a template matching the draft |
| LOW | `CONFIG_IN_SYNC` | repo2 holds repo1's value under the key's Spring Boot new name |

Runs saved before 2026-10-28 (with a baseline) still load; their categories `BOTH_MODIFIED`, `DELETED_UPSTREAM`,
`UPSTREAM_REFACTORING`, `AUTO_SYNC`, `NEW_CODE` are shown but no longer produced.

**Camel routes** (`CamelRouteAnalyzer` + `CamelRoutes`): every `.xml` of each tree with a `<route>` under
`<routes>` / `<camelContext>` / `<routeContext>` (Spring, Blueprint, `camel:` prefix or none) is reduced to
`route id -> steps`, one normalized line per element — `from uri=direct:order`, `filter`, `  simple: ${body} != null`,
`  to uri=...` — attributes sorted, namespaces / comments / formatting dropped. A route without an `id` is keyed by
its `from` uri. Routes are matched by id **across all route files**, so a route the migration moved to another file
(or to repo2B) is still found:

| Route | Category |
|---|---|
| same steps (wherever repo2 keeps it) | not reported |
| different steps, same file / another file | `CONTENT_DIFFERS` / `RELOCATED` (MEDIUM), details = step diff (`-` repo1, `+` repo2) |
| only in repo1 | `TARGET_MISSING` (HIGH), details = its steps |
| only in repo2 | `ONLY_IN_REPO2` (LOW) |

Content outside the routes (beans, `restConfiguration`, the REST DSL, data formats ...) is compared per file and
reported as `NON_JAVA_FILE`. XML that is not Camel, or does not parse, falls back to the line diff. The Java DSL
(`RouteBuilder` classes) is Java: compared method by method like any class. The diff page still shows the file
line by line.

## What RefactoringMiner does here

[RefactoringMiner](https://github.com/tsantalis/RefactoringMiner) compares two versions of Java code and reports
the **refactorings** between them — a class moved or renamed, a method renamed, moved, pulled up, extracted,
inlined, split — each with the exact file and line range on both sides. It works on two directories with no git
history, which is what lets repo1 be compared with a repo2 that does not share its history.

| Use | Call | What it answers | Feeds |
|---|---|---|---|
| **Analysis** — repo1 → repo2 | `detectAtDirectories` | Where repo2 keeps each repo1 class / method that is not at the same place | `RELOCATED` / `ALREADY_SYNCED` (moved / renamed, with the new location), `TARGET_SPLIT`, and the class lookup behind every other category |
| **Diff view** — the same pair | `diffAtDirectories` + `MonacoCore.makeDiffConfig()` | Which code was inserted, deleted, updated or moved, and where to | The Thymeleaf side-by-side page (`/api/migration-risk/view/diff`) |

Example (demo): the run reports `Move Class OrderProcessor → order.processing.OrderProcessor` and `Rename Method
handle → process`. `OrderProcessor.handle()` is compared with repo2's `process()` instead of being reported missing.

**What it does not do — this module does:** plain modifications (RefactoringMiner reports refactorings, not edits —
which methods differ comes from JDT-normalized declarations, `JavaStructure`); grading (`JavaRiskAnalyzer`,
`FileRiskAnalyzer`, `GlobalConfigAnalyzer`); non-Java files; API rules; git access (JGit); persistence and review.
Only structural refactorings drive items; the hundreds of detail refactorings a migration produces are listed in
the page's refactorings tab but hidden by default.

Technical constraints (details in the skill): the version is pinned in the pom (3.1.x = Java 23 bytecode → JDK 25);
several of its dependencies are excluded (Jetty 9, Spring 4.1.6, GitHub/Perforce clients); its jar's own
`logback.xml` is overridden by `logging.config`; directories must be passed as real absolute paths.

## Options and what the table adds

- **One record per file** — every differing file is one row to review; the methods, config keys and API matches
  in it are its `parts`. The file takes its riskiest part's level. A file only repo2 has is a row too (LOW).
- **Sync tracking** — re-run a release as the sync goes on. A file made only of `ALREADY_SYNCED` / `CONFIG_IN_SYNC`
  parts is reviewed `SYNCED` automatically; a difference that disappears is in the *Resolved* tab. Each file is
  tagged NEW / CHANGED / SAME against the previous run; a file that changed after its review comes back pending with
  the old verdict and the parts that are new, different or gone.
- **repo2B** — when the migration split repo1 into two repositories: `repo2bPath` (+ `repo2bHead`). Both are read as
  one tree; each target says `repo2A` or `repo2B`.
- **Skip tests** (`skipTests`), **module / test tags**, **config renames** (Boot's deprecation metadata plus
  `migration-risk.config-renames`), **remote repositories** (`https://` URLs from `remotes.allowed-hosts`), **ignore
  rules**, **Excel / Markdown export**, **Jira issue per file** (mocked unless `migration-risk.jira` is set).
- **Database** — PostgreSQL by default (`db/migration`), `h2` profile without Docker, **`mysql` profile** for MySQL 8
  (`db/migration-mysql`, same versions, MySQL syntax): `create database migration_risk character set utf8mb4 collate
  utf8mb4_bin`, then `CREED_MIGRATION_RISK_DB_URL` / `_USER` / `_PASSWORD`.

## Limits

Without a baseline, every file the migration touched differs: on a real project the differing set is most of the
repository (`max-changed-files`, default 5000), RefactoringMiner runs over all of it, and `CONTENT_DIFFERS` mixes the
migration's rewrites with new upstream work. Behaviour that changed under identical code (Jackson 3's defaults) is
invisible — that is what tests are for. Run notes, API and limits: [`HANDOFF.md`](HANDOFF.md).

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

### 去掉基线（2026-10-28）

按要求改为 repo1 HEAD 与 repo2（repo2A + repo2B）直接比较，不再有基线：只跑一次 RefactoringMiner（repo1 → repo2），类别改为描述"哪里不同"（新增 `CONTENT_DIFFERS`、`ONLY_IN_REPO2`），对比视图只剩"repo1 对比 repo2"，三方对比视图和 merge-base 建议随基线一起去掉。上面依赖基线的建议（三方合并、三栏视图、"跳过 repo2 中与基线相同的文件"）因此不再适用。另新增 MySQL 支持（`mysql` profile，`db/migration-mysql`）。

## TODO

- [ ] ~~**三方合并定风险**（第一节第 1 项）~~：需要基线作公共祖先，去掉基线后不再适用；如果以后要做，需要把基线作为可选输入加回来。
- [ ] **复核人身份**：接入 creed-author-server（OAuth2），复核记录用登录身份代替手填的名字。
- [ ] 第一节第 3 项（按文件内容缓存、跳过不需要的对比、两次运行并行）。
- [ ] 第二节其余：多模块的全局配置按模块匹配、pom 解析 BOM 实际版本、repo2A / repo2B 同路径文件两份都比。
- [ ] 第四节：定时分析 + 新的高风险项通知。
