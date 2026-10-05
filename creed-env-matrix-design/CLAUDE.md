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
│   │   └── Aes          # AES 加解密（/aes）
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
