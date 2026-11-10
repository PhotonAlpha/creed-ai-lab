# CLAUDE.md — Env Matrix Viewer 项目构建需求大纲

本文件是项目的**构建需求大纲（requirements outline）**，供 Claude Code 在本仓库工作时参考。
当前仓库尚未落地源码，以下内容描述「要构建什么」与「如何构建」。

## 1. 项目目标

构建一个 **环境主机/端口映射矩阵查看器（Env Matrix Viewer）**：

- 以 Ant Design Pro 为模版 构建前端项目
- 以矩阵形式展示各环境的 `host` `ip` `port` ，便于排查冲突与核对配置。
- 如果存在http https 两种配置，能有个filter 能过滤掉不需要的信息
- creed-resource 下创建一个新的resource，提供一个API，使 UI/UX 能查询同数据，测试通过之后对接后端API。
- 支持对数据的增删改查（CRUD），数据库信息为 jdbc:postgresql://127.0.0.1:5432/env_matrix  username: artifactory password: artifactory_pw 。
- 提供健康检查功能，健康检查可以先后端mock 状态

## 2. 数据模型

数据单元为 **endpoint**，由以下维度唯一标识：

| 维度               | 取值示例                                                                                        |
|------------------|---------------------------------------------------------------------------------------------|
| App system       | `CCS` / `MS` / `AliYunTeir` / `TencentTeir`                                                 |
| Tier             | `SIT` / `UAT` / `NFT` / `PROD`                                                              |
| Env instance     | `UAT1`..`UAT5`、`SIT1`..`SIT5` 等（当前种子数据：`SIT1`-`SIT2`、`UAT1`-`UAT3`、`NFT1`、`PROD1`）        |
| Country          | `CN` / `SG` / `MY` / `HK` / `GD` / `ID`                                                     |
| Service          | `MS1`..`MS6` / `CCS1`..`CCS6` / `AliYunTeir1`..`AliYunTeir4` / `TencentTeir1`..`TencentTeir3` |
| Instance         | `Green` / `Green2` / `Green3` …（Active-Standby）                                             |
| Scheme           | `http` / `https` —— **属于身份的一部分**：同一服务同时暴露 http 与 https 是两条记录，不是重复         |
| Host / ip / Port | 实际的 `host` `ip` `port` 映射                                                                   |

> 维度取值以纯文本存储、不使用枚举；`GET /api/env-matrix/dimensions` 从实际数据推导过滤选项，
> 因此新增取值只需插入数据，无需改代码（但仍需按第 7 节约定同步本表）。

**拓扑关系**由三张表承载：`env_release`（一个被命名的 release）、`env_release_node`（**参与者**，
即一个环境切面 `(应用系统, 国家/地区, 环境实例)`，外加画在哪里的 `layer` / `sort_order`）、
`env_release_link`（两个参与者之间的连接，带 `direction`）。

`layer` 可空：`null` 表示「按连接关系推导」，不能用 `0` 表达（`0` 是第 0 列）；`sort_order` 非空，
默认 0。两者由拓扑图的「层级与顺序」对话框写入，随 `PUT /releases/{id}/topology` 一起保存。

节点是切面而不是应用系统，因为同一个应用系统可以在一条链里出现两次：
`SG CCS SIT3 → Global-CCS SIT2 → CN CCS SIT5`。release 负责说明哪些切面属于一起 —— 这也让
envInstance / country / service / instance 保持互不关联，只作为数据存在。

`country = '*'` 表示不区分国家。与 endpoint 之间**没有外键** —— 参与者可以指向尚未录入任何
endpoint 的切面，这个缺口正是本工具要暴露的。

- **冲突（conflict）**：在应当唯一的范围内，两个 endpoint 解析到相同 `host:port` 或 `ip:port`。
  「应当唯一的范围」由 `env-matrix.conflict.scope` 显式配置：`TIER_ENV`（默认，单个环境实例内唯一）/
  `TIER`（整个层级内唯一）/ `GLOBAL`（全局唯一）。默认不把「同一地址在两个不同环境中复用」判为冲突。


## 3. 技术栈

- 前端：React + TypeScript + Vite + Ant Design Pro。
- 后端：[creed-resource](../creed-resource)下创建新的resource
- 联调：Vite 把 `/api` 代理到 `VITE_API_TARGET`（`.env` 里是 `https://localhost:18095`）；
  连 mock 或 `dev` profile 需要 `VITE_API_TARGET=http://localhost:3001`。
  `/api/env-matrix/splunk` 单独代理到 `VITE_SPLUNK_TARGET`（`.env`：BFF `http://localhost:3002`）。
- Veracode 分析：`/api/veracode` 单独代理到 `VITE_VERACODE_TARGET`（`.env`：`http://localhost:18097`，
  `creed-resource-veracode`，明文 HTTP，Jira 在后端进程内 mock，自带示例 Veracode XML）。
- 迁移同步风险：`/api/migration-risk` 单独代理到 `VITE_MIGRATION_RISK_TARGET`（`.env`：`http://localhost:18098`，
  `creed-resource-migration-risk`，明文 HTTP，后端自带示例仓库）。
- CCRG 比较工具：`/api/ccrg` 单独代理到 `VITE_CCRG_TARGET`（`.env`：`http://localhost:8600`，
  `creed-resource/ccrg-tool-starter`，明文 HTTP），代理去掉 `/ccrg` 前缀。
- Splunk 会话代理：Node BFF（`server/bff.js`），审计默认存内存，开关 `SPLUNK_AUDIT_STORE=pg` / `mysql` 时写入 PostgreSQL / MySQL。

## 4. 目录结构（目标）

```
.
├── src/                 # React 前端
│   ├── pages/
│   │   ├── Matrix       # 矩阵视图（首页 /）
│   │   ├── Topology     # 矩阵拓扑图（/topology）
│   │   ├── Config       # CRUD 编辑页（/config）
│   │   ├── Splunk       # Splunk 会话代理（/splunk）
│   │   ├── Aes          # AES 加解密（/aes）
│   │   ├── Veracode     # Veracode 报告分析（/veracode，后端 creed-resource-veracode）
│   │   ├── MigrationRisk # 迁移同步风险（/migration-risk，后端 creed-resource-migration-risk）
│   │   └── Ccrg         # CCRG 比较工具（/ccrg/*，迁移自 ccrg-tool-ui，后端 ccrg-tool-starter）
│   └── api/             # 前端 API 封装
├── server/
│   ├── index.(js|ts)    # mock API 服务
│   ├── mock.json        # 数据源（唯一真相）
│   ├── bff.js           # 部署用 BFF：dist/ + Splunk 会话代理 + /api 反向代理（:3002）
│   └── splunk/          # Splunk 会话代理实现，mock 与 BFF 共用
├── grafana/             # Grafana 演示与说明
└── vite.config.ts       # 含 /api → :3001 代理
```

## 5. 功能需求

### 5.1 矩阵视图（`/`）
- 以 **service × country** 聚合展示单元格；支持按 App system / Tier / Env instance 过滤。
- 高亮存在冲突的单元格。

### 5.2 矩阵拓扑图（`/topology`）
- 把当前过滤切面画成图：一个 endpoint 一个节点，按**参与者**分组（G6 combo）。
- **必须选定一个 release**（不是 Tier）。参与者与连接存在数据库中，在「配置编辑 → Release 拓扑」页
  维护，由 `/api/env-matrix/releases*` 提供增删改查。`env_endpoint` 只记录地址，没有任何一列表达
  调用关系，因此这层关系必须单独声明。
- 依赖箭头画在参与者分组之间，不画在 endpoint 之间。没有匹配 endpoint 的参与者画成虚线占位节点；
  不属于任何参与者的 endpoint 以横幅计数提示。
- 按国家/地区、环境实例收窄只过滤框内的 endpoint，**绝不过滤连接关系**。
- 列的顺序（层级）由连接关系推导：按存储的 `source -> target` 方向做最长路径分层。
- 其余连线全部由现有数据推导：同 `host`（同机）、同 `ip` 不同 `host`（DNS 别名）、以及
  `/conflicts` 返回的地址冲突。
- **topology 与 endpoint 的推导关系是单向的**：参与者按 `(应用系统, 国家/地区, 环境实例)` 认领
  endpoint（`country='*'` 匹配所有，具体切面优先于通配切面，第一个匹配者胜出）；endpoint 不会反过来
  产生参与者或连接。完整说明见 `README.md` / `README.zh-CN.md` 的「topology 与 endpoint 之间的
  推导关系」一节 —— 改动这层关系时必须同步该节。
- 参与者分组再按 **应用系统** 聚类成外层分组框（G6 嵌套 combo）；分层视图里外框按
  *(应用系统, 层级)* 划分，因为同一应用系统可以出现在多个层级上。
- 分层方向可切换 `→ ← ↓ ↑`；「层级与顺序」可把参与者钉到指定层级或调整顺序，写入
  `env_release_node.layer` / `.sort_order`（暂存后一次保存，走 `PUT /releases/{id}/topology`）。
  只有布局/方向/分组这类**不改变图的含义**的偏好留在 `localStorage`。
- 配置编辑页的参与者表单不编辑这两个字段，但保存时必须原样带上 —— 该保存对整个 release 具有权威性。
- 图库为 `@antv/g6` 5.x；两种布局的坐标均由 `buildGraph.ts` 自行计算，不使用 G6 内置布局。

### 5.3 配置编辑页（`/config`）
- 表格化展示全部 endpoint，支持增、删、改。
- 「保存到文件」按钮：校验后通过 写回 数据库。

### 5.4 Splunk 会话（`/splunk`）
- **由 Node BFF（`server/bff.js`，实现在 `server/splunk/`）提供，已从 `creed-resource-env-matrix` 剥离。**
- 凭据存储：环境变量，或 `NAME_FILE` 指向的文件（`ENV_MATRIX_TOTP_SECRET` / `SPLUNK_PASSWORD` /
  `SPLUNK_DB_PASSWORD`）；`npm run bff` 会加载 `.env.server.local`，变量清单见 `.env.server.example`。
- 身份验证：标准 TOTP（RFC 6238，30 秒一个窗口，允许 ±1 个窗口误差），密钥为 `ENV_MATRIX_TOTP_SECRET`。
- 页面轮流显示当前 OTP（`ENV_MATRIX_TOTP_EXPOSE_CODE`，按需求默认开启），用户手动输入后发起请求。
- Splunk 调用使用 `node:https`；**默认返回 mock 值**，`SPLUNK_ENABLED=true` 后才真正调用获取 cookie。
  **按需求默认跳过 Splunk 证书校验**（`SPLUNK_TLS_INSECURE=true`，证书链与主机名都不校验）。
- 登录目标为下拉框：`SPLUNK_TARGETS=SIT,UAT` 配置多个 Splunk 实例，每个目标有自己的
  `SPLUNK_TARGET_<ID>_LOGIN_URL` / `_USERNAME` / `_PASSWORD`（不回退到全局变量）；切换目标时页面显示的
  登录地址、用户名随之改变，BFF 也用该目标的账号登录，密码从不发给浏览器。未设置 `SPLUNK_TARGETS` 时只有一个
  `default` 目标（`SPLUNK_LOGIN_URL` / `SPLUNK_USERNAME` / `SPLUNK_PASSWORD`）。
- OTP 通过后由 BFF 表单登录 Splunk，从响应 cookie 取 `splunkd_8000`，返回
  `document.cookie = "splunkd_8089=…; path=/; Secure; SameSite=Lax";` 供复制。
- 审计：每次 OTP 校验与 Splunk 调用都记一行（不保存 cookie 值，只保存指纹）；写不进审计则请求失败。
  **默认存内存**（最新 500 条，重启即丢失）；`SPLUNK_AUDIT_STORE=pg` 时写入 Postgres
  `splunk_broker.splunk_audit`，`mysql` 时写入 `SPLUNK_DB_URL` 所指 MySQL 数据库的 `splunk_audit`。mock 始终存内存。

### 5.5 AES 加解密（`/aes`）
- 设计图：`creed-resource/creed-resource-env-matrix/docs/design.png`。
- 算法（与真实配置文件一致）：`Secret Key = randomkey + host + ip`（直接拼接），
  `key = PBKDF2WithHmacSHA256(Secret Key, UTF-8(salt), 65536, 256)`，AES-256/CBC/PKCS5Padding，
  IV 为 UTF-8 正好 16 字节，salt 必填，密文 Base64。**页面不输入 Secret Key**，每台服务器各不相同。
  Java（`AesCryptoService`）与 mock（`server/aes.js`）必须保持一致 —— 两边测试锁定同一个向量。
- 「密钥与取值」是一个数组：每行分两排 —— 第一排 IV / salt，第二排 randomkey / Secret Key（预览，只读）/ 属性键 / 明文 / 预览密文；
  可增删行，也可「以 JSON 编辑」导入导出（未知字段拒绝）。表单的全部加密 / 全部解密针对「预览服务器」（勾选的服务器之一）。
- 服务器列表从配置（`env_endpoint`）读取不重复的 host / ip，按 App system 和 envInstance（多选）过滤，每台带 envInstance 标签。
  结果列表有同样的两个过滤（相互独立；记录的 envInstance 取自其服务器）。
- 保存：发送明文，后端按每台勾选服务器各自的 Secret Key 加密，所有行 × 所有服务器一个事务；属性键重复则拒绝。
  后端 CRUD 在 `creed-resource-env-matrix`（`env_aes_record`，Flyway V7/V8/V9）：保存密文、randomkey、**IV 和 salt**
  （V9，按需求）—— 结果列表无需输入即可解密，但数据库副本也因此可以直接解密全部值。
- 结果列表显示每条记录的 Secret Key（及 randomkey），用于核对真实配置文件；可勾选后批量解密
  （记录自身的 Secret Key + 表单中属性键相同那一行的 IV 和 salt）或删除。

### 5.6 Veracode 分析（`/veracode`）
- 后端：`creed-resource/creed-resource-veracode`；调用 Jira 使用 Spring `@HttpExchange`，本地先 mock。Jira 客户端**不校验证书和主机名**（按需求）。
- 报告来源两种（页面上两个标签页）：**上传 Veracode 详细报告**（XML，或打包了 .xml / .xsd 的 ZIP：读取其中所有 .xml，含子目录，其他文件忽略）；
  **Confluence 附件**：输入页面 ID（或粘贴页面链接）列出该页面全部附件，勾选 .xml / .zip（其他类型不可选），由后端下载并分析。
  Confluence 用用户自己的令牌（「Confluence 令牌」对话框，Cloud = 邮箱 + API 令牌，Data Center = 个人访问令牌，可「测试连接」，
  存储方式同 Jira 令牌，请求头 `X-Confluence-Token` / `X-Confluence-User`）；后端未配置 `veracode.confluence.base-url` 时只显示上传。
  两种来源都读取 SCA 部分（对接 Jira）和 SAST 静态分析漏洞（按 CWE + 类别对接 Jira，见下）；报告的影响版本（根节点 `version`，上传前可手工改）**只在表格中展示**，不参与 Jira 查询和创建。
- **Jira 版本**是可搜索的多选下拉（版本多时不撑大页面，列表来自测试连接 / 分析结果）：查找时按所选版本过滤（`affectedVersion in`，不选 = 全部版本），
  新建时关联所选版本，所以创建必须正好选一个；切换版本会自动重新分析。
  默认值在后端配置 `veracode.jira.defaults`：`affects-versions`（测试连接后，若页面还没选 Jira 版本且该 Jira 有这些版本则选中，没有则页面不变）、
  `fields`（Component/s 等创建字段，按字段 id 或名称；Jira 没有的取值忽略）。
- 按 CVE（无 CVE 时按公告编号）+ Component(s) + 所选 Jira 版本在 Jira 中查找是否存在：
  存在则列出 Jira 编号、状态、优先级、经办人；不存在则可按需创建（单行 / 所选 / 全部缺失，或分析时自动创建）。
- 一行 = 一个 Veracode application + 一个 `cve_id` + 一个组件 `file_name`（同一 CVE 在两个 jar 中是两行、两个 Jira）。
  表格列：状态 / 标题（`cve_id|cwe_id`，缺失为 Unknown）/ 组件文件 / 严重级别 / CVSS / 影响版本 / 描述（完整 `cve_summary`，超过 4 行折叠，可逐行展开 / 收起）/ Jira。
- 按 Jira 标题匹配：CVE 出现在标题任意位置即可（忽略大小写和 `-` `_` 空格，但不会匹配更长的编号）；无 `cve_id` 时按 `Unknown` + `cwe_id`；
  且组件 `file_name` 要作为完整的词出现在标题中（`log4j-api-2.14.1.jar` 或 `log4j-core-2.14.10.jar` 不算），标题模板含 `{file}` 时生效。
- 新建 Jira 标题 `{project} - {cve_id}|{cwe_id} - {file_name}`，`{project}` 为 `veracode.jira.project-name`（不是 Veracode application）；
  label：`veracode`、`{project}`、application label（不含 `cve_id`）（`veracode.applications` 中映射的名称，如 `GBT-T1-Creed-CCS` → `Creed-CCS`；没有映射时为 `app_name`）；
  描述表格按页面列顺序，含完整 `cve_summary`。
- 可一次上传多个 XML：不同 application 时表格可按 application 过滤、分组（可折叠）；相同 CVE + 相同 jar 的 Jira 已存在时不再重复创建，
  而是给它加上该 application 的 label（行上显示「缺少 label」，按钮为「添加 label」）。
  按钮按颜色区分：创建 = 蓝色（primary）+ ➕ 图标，添加 label = 橙色 + 标签图标（与「缺少 label」标签、统计卡片同色）；
  工具栏「创建全部缺失」只建缺失的，「添加全部 label」一键给所有缺少 label 的 Jira 加上，避免遗漏。
- 已关闭的 Jira：一个漏洞（SCA 或 SAST）找到的 Jira **全部**为 Closed / Done（`veracode.jira.closed-statuses`）时，该行显示红色「重新创建」，
  点击后弹框列出已关闭的 Jira 并提醒慎重，确认后只为这一行新建（`recreateClosed`，后端只接受单个 id，并重新检查状态）；批量创建仍视为已存在。
  application 显示在分组（折叠面板）标题上，单个报告时显示在上方的报告表中；只有关闭分组、多个 application 混在一个表里时才显示 application 列。
  查找用批量 JQL 搜索：每 50 条漏洞一次（Data Center `/rest/api/2/search`，Cloud `/rest/api/3/search/jql`）。
- 创建 Jira 使用**用户自己的访问令牌**：页面「Jira 令牌」中配置并「测试连接」（显示令牌对应的用户、是否能访问项目），
  默认存 sessionStorage，勾选「记住」存 localStorage；随请求以 `X-Jira-Token` 发给后端，后端不保存。未配置时创建按钮不可用。
  对话框先选 **Jira Cloud**（邮箱 + API 令牌，另带 `X-Jira-User`，后端以 Basic 认证）或 **Jira Data Center**（个人访问令牌，后端以 Bearer），两者都调用 REST API v2；`base-url` 为 `*.atlassian.net` 时默认选中 Cloud。
  「测试连接」逐项显示检查结果（令牌 → 能否创建该问题类型 → 是否有影响版本字段 → 项目是否有版本），失败项给出在 Jira 中的修复方法；
  问题类型可在对话框中选择（标出哪些类型有影响版本字段），随令牌保存，以 `X-Jira-Issue-Type` 发给后端，默认取 `veracode.jira.issue-type`。
- 页面布局默认与 Ant Design Pro 一致：ProLayout `layout="mix"`，顶部标题栏 + 左侧可折叠菜单（ProLayout 只给第一级菜单画图标，子菜单图标由 `App.tsx` 的 `withMenuIcon` 补上）；可在「整体风格设置」抽屉中修改（见 README 第 8a 节）。
- 表格可按关联 Jira 的属性过滤（状态 / 优先级 / 经办人 / 影响版本，多选），搜索框对 CVE、标题、组件、Jira 号与 Jira 标题做模糊匹配。
- SCA 统计：报告表下方按报告列出 `<software_composition_analysis>` 的统计（第三方组件 / 违反策略及违规组件数 / 黑名单组件）与计数
  （有漏洞的组件 / 漏洞数 / 各严重级别 / 最高 CVSS / 已缓解），多个报告时带合计行。
- SAST（静态分析漏洞，`severity/category/cwe/staticflaws/flaw`）与 SCA **在同一页面**，用标签页「SCA 组件漏洞 / SAST 代码漏洞」区分（`?tab=sast`）：
  - Jira：**未关闭**的漏洞（已缓解、已修复的不建单）按 **CWE + 类别（flaw 的 `categoryname`）** 合并为一个 Jira，跨文件、行号和 application；
    标题 `{project} - CWE-89 - {categoryname}`，**按这个标题查找和创建**（查找不带版本条件）。
  - 项目、问题类型、label 与 SCA 分开，在 `application.yml` 的 `veracode.jira.sast` 配置（`{project}` / label 为 `IDB VERACODE STSA`，
    label 中的空格变为 `_`；另加各 application 的 label，缺少时「添加 label」，与 SCA 一致）；SAST 标签页有自己的影响版本选择（该问题类型要求时必选）和 Jira 字段。SAST 读取 Jira 失败只在 SAST 标签页报错，不影响 SCA。
  - 「SAST 统计」在报告表下方；SAST 标签页里是 Jira 表格（可展开看每处代码）和全部漏洞列表（含已缓解、已修复）。
- 导出到 Confluence：输入父页面 ID（或链接），用用户自己的 Confluence 令牌在其下新建子页面；后端用 Thymeleaf 模板
  （`templates/veracode/confluence-report.html`）生成 `sample/confluence-template.png` 的格式（汇总 / SCA Issues / SAST Issues），
  标题 `{project} - {version} - Reports (SAST,SCA) - {yyyy-MM-dd HH-mm-ss}`（`veracode.confluence.export-title`；project / version 默认取报告的，可在对话框修改）。
  页面底部「Veracode report(s):」附上分析的报告文件（上传的或从 Confluence 取的原文件，作为新页面的附件并链接）。
  SCA / SAST 两个表格按 repository 排序；SAST 的 Details 与页面的「Jira 标题」列相同（按 application 三行）。
- application 映射（`veracode.applications`：`app_name` → 名称 + 链接）：页面表格、Jira 描述、Confluence 的 Repository 显示映射名称并可点击跳转，
  映射名称也是 Jira label；没有映射时用 `app_name`。
- Confluence 附件列表：按上传时间倒序，默认最多 50 个，默认只列文件名含 `dd_MMM_yyyy` 日期且为 `.zip` 的附件（可取消）；
  「报告日期」下拉框按文件名中的日期再筛选（最新在前），列出后默认选最新日期，可选「所有日期」。
- 上传 / 从 Confluence 分析时全屏 loading（`Spin fullscreen`，上传阶段显示百分比）。
- 新建 Jira 描述的 Source：上传的文件加为 Jira 附件；从 Confluence 下载的给出附件地址和所在页面的链接（`on page [标题|页面地址]`）。
- SAST 标签页「Jira 标题」列按 application 显示三行（`<名称>: <SAST project-name> - <类别>` / `CWE 117 <名称>` / `CWE 117 (Flaw IDs: …)`），
  合并了原「未关闭漏洞」列；只影响显示，Jira 标题本身不变。
- 支持导出 Excel（Severity 按等级填充背景色；*SCA summary* 工作表与页面的 SCA 统计一致；*SAST flaws* / *SAST summary* 两个工作表对应 SAST 部分）。
- 统计卡片（漏洞总数 / 已在 Jira / 缺失 / 已创建）按状态用不同背景色（主题 token）；表格分页受控，默认每页 50 条（20 / 50 / 100 / 200）。
- 刷新页面不丢数据：分析结果、所选 Jira 版本、字段取值、过滤条件、每页条数存 sessionStorage（随标签页关闭清除）；
  结果超出存储配额时只存上传 id，刷新后自动重新分析（后端上传保留 2 小时）。恢复时提示缓存时间，可重新分析或清除。
- 上传报告时显示进度：先显示上传百分比和字节数（XMLHttpRequest，fetch 没有上传进度）；上传完成后显示「正在解析并在 Jira 中查找」的进行中进度条（服务端这一步不报告进度，不显示百分比）。

### 5.7 迁移同步风险（`/migration-risk`）
- 后端：`creed-resource/creed-resource-migration-risk`；RefactoringMiner（目录比较模式）+ JGit + JDT；结果存 PostgreSQL `migration_risk`（也支持 MySQL：`mysql` profile）。
- 两种比较方式：**Git**（repo1 / repo2 / 可选 repo2B 各自选择分支）；**文件夹**（repo1 / repo2 / 可选 repo2B 普通目录，不需要 git）。两个仓库不需要共同的 git 历史。
- **没有基线**：repo1 HEAD 与 repo2（repo2A + repo2B 合并为一个代码库）直接比较。一次 RefactoringMiner 分析（repo1 → repo2）找出移动 / 重命名 / 拆分；
  Java 按类逐个成员比较（格式、Javadoc、import 不算差异），配置项全局逐个 key 比较，pom 逐个依赖比较，`.vm` 先草拟成 `.ftl` 再比较，Camel XML 路由按 route id 跨文件逐步比较，其他文件逐行比较。
  规则表见模块 `README.md` 与页面中的「风险分级规则」。详情行按 `git diff repo1 repo2` 的方向：`-` 为 repo1，`+` 为 repo2。
- repo1 独有行按 `migration-risk.api-rules` 检查需迁移的 API；同一文件中只有 API 命中、没有其他差异时视为迁移的改写，不报告。
- 填写 **release** 时结果写入数据库并归到该 release；每个文件可人工 review：「安全」/「需关注」/「已同步」（可写备注）。
  同一 release 再次分析时，内容没变的文件沿用上次的结论。留空则只分析不保存。
- 「对比视图」：后端 Thymeleaf 页面 `/api/migration-risk/view/diff`，RefactoringMiner 的左右对比（Monaco）视图，左边 repo1、右边 repo2；
  只有一个按钮「repo1 对比 repo2」。基线时代的「上游改动」视图和三方对比视图已去掉。

### 5.8 CCRG 比较工具（`/ccrg/*`）
- 旧 Vue 应用 `ccrg-tool-ui` 的全部页面迁移到此：拉取配置 / 生成报告 / 批量导出 / 跨项目比较 / 快捷跳转 / 安全管理 / 字典管理，
  菜单为一个分组。页面布局参考 `ccrg-tool-ui/ui-current.png`：上方查询卡片，下方表格卡片 + 底部操作按钮。
- 后端响应为 `R<T>` 信封 `{code, data, msg}`，成功 `code = 0`；报告下载成功返回文件、失败返回 JSON（HTTP 都是 200），按 content-type 区分。
- 批量导出与跨项目比较共用 `pages/Ccrg/Comparison`（`mode`），差异见 `model.ts`。各页面保存的表单使用各自的 localStorage 键。
- 详细说明见 `README.md` / `README.zh-CN.md` 的「CCRG 比较工具」一节。

## 6. 命令

```bash
npm install
npm run dev                                        # 启动 UI（:5173），后端取自 .env
npm run mock                                       # 无需数据库的 mock API（:3001）
npm run dev:mock                                   # 让 UI（含 Splunk）全部连 mock
VITE_API_TARGET=http://localhost:3001 npm run dev  # 让 UI 连 dev profile（Splunk 仍走 :3002 的 BFF）
npm run bff                                        # BFF（:3002）：dist/ + Splunk + /api 代理
npm run test:server                                # Splunk 会话代理的 node:test 测试
npm run build && npm run package:bff              # 部署包 release/env-matrix-bff/（详见 DEPLOY.zh-CN.md）
```

- 矩阵视图：`http://localhost:5173/`
- 拓扑图：`http://localhost:5173/topology`

**`npm run dev` 默认连的不是 mock。** 提交在仓库里的 `.env` 是
`VITE_API_TARGET=https://localhost:18095`，即该模块常规的 HTTPS 实例。后果是：只要那个实例在跑，
界面就有数据，一个没重启的旧后端看起来和前端坏掉完全一样 —— 排查问题前先确认代理指向。

## 7. 约定（给 Claude 的工作准则）

- 基于Ant Design Skills
- 前端文案保持中英文一致风格创建中英文的文档。
- 每次代码调整完成之后，将文案文档做出相应的更新
- 新增维度取值时，同步更新本文件第 2 节的数据模型表。
- 类型检查用 `npm run typecheck`（即 `tsc -b`）。**不要用 `tsc --noEmit`** —— 根 `tsconfig.json`
  是 solution 形式，`--noEmit` 不跟随 project references，满是错误也会返回 0。
