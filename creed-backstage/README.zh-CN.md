# creed-backstage — Creed 开发者门户

[English](README.md)

一个 [Backstage](https://backstage.io) 应用：Creed 各服务的 **Software Catalog**（软件目录），每个服务的
REST API 以 **Swagger UI** 展示（只读，带按 tag 过滤）。目录按**模块**划分 —— 每个功能领域一个模块
（`creed-env-matrix`、`creed-payment`、`creed-order` ……），各自拥有 System、Component 和 API 实体。
API 的 OpenAPI 文档按领域拆成多个 `.yml`，由 Redocly CLI 打包成一个单文件 `dist/openapi.yaml` 并提交到
仓库，目录通过 `$text` 读取它。

| | |
|---|---|
| 前端 | <http://localhost:3003>（不是 3000 —— 被 Grafana 占用） |
| 后端 | <http://localhost:7007> |
| 技术栈 | Backstage 1.x（新前端 + 新后端体系）、Yarn 4、Node 22/24、Redocly CLI 2 |

## 运行

```bash
cd creed-backstage
corepack yarn install        # Yarn 4 已在 .yarnrc.yml 中固定版本，由 corepack 运行，无需全局安装
corepack yarn start          # 同时启动前端和后端；点击 "Enter" 以访客身份登录
```

后端启动约一分钟后目录才完成第一轮处理，在此之前目录页是空的。SQLite 文件位于 `.sqlite/`（已被 git
忽略），删除该目录即可得到一个干净的目录。

**`corepack` 是什么。** Corepack 是 Node.js 自带的工具，按项目声明的版本运行包管理器 —— 这里是
`package.json` 中的 `"packageManager": "yarn@4.13.0"`（以及 `.yarnrc.yml` 中的 `yarnPath`）—— 因此无需全局
安装 yarn，所有人用的都是同一个版本。直接写 `corepack yarn <命令>` 即可；执行一次 `corepack enable` 之后可以
直接敲 `yarn`，但它会在全局 Node 安装目录里写入快捷命令，所以是可选的（CI 会执行它，因为容器用完即丢）。
Node 14.19 / 16.9 到 24 都自带 corepack；从 Node 25 起需要 `npm i -g corepack` 另行安装 —— 这也是
`bitbucket-pipelines.yml` 固定使用 `node:22` 的原因。

## 目录模块

```
catalog/
├── all.yaml                         Location：列出每个模块的 catalog-info.yaml
├── org.yaml                         Group creed-platform（所有者）
├── creed-backstage.yaml             本门户自身，作为一个 Component
├── creed-env-matrix/                ┐
├── creed-payment/                   ├ 每个模块一个目录，结构完全相同：
└── creed-order/                     ┘
    ├── catalog-info.yaml            System <模块> + Component + API <模块>-api
    ├── openapi.yaml                 入口文档：info、servers、security 手写；paths 为生成内容
    ├── paths/*.yml                  每个都是【完整的】OpenAPI 文档（openapi、info、paths、components）
    └── dist/openapi.yaml            生成的单文件打包结果 —— 需要提交；目录读取的是【这个文件】
```

**模块**就是 `catalog/` 下任何含有 `openapi.yaml` 的子目录。每个模块有自己的 `metadata.name`，因此目录中
`creed-payment` 和 `creed-order` 是两个独立的 System，各有独立的 API。所有 `openapi:*` 命令默认处理
**全部模块**，也可以只处理指定的模块：

| 命令 | 作用 |
|---|---|
| `corepack yarn openapi:build [模块…]` | combine + bundle —— 修改后只需运行这一条 |
| `corepack yarn openapi:combine [模块…]` | 重新生成 `openapi.yaml` 的 `paths:` / `components.securitySchemes` |
| `corepack yarn openapi:bundle [模块…]` | 用 Redocly 将 `openapi.yaml` 打包为 `dist/openapi.yaml` |
| `corepack yarn openapi:lint [模块…]` | 对打包结果执行 `redocly lint`（结构错误判为失败，风格规则只告警） |
| `corepack yarn openapi:check [模块…]` | `build --check` + `lint`：有过期或无效的文件时以非 0 退出（CI 使用） |
| `corepack yarn catalog:new <模块> [选项]` | 创建一个新模块的骨架（见下文第 3 节） |

```bash
corepack yarn openapi:build                          # 全部模块
corepack yarn openapi:build creed-payment            # 只处理一个
corepack yarn openapi:build creed-payment creed-order
```

写错模块名会直接报错并列出可用模块，而不是什么都不做。

## 脚本（`scripts/`）

用来生成 OpenAPI 文档的 Node 脚本，不属于 Backstage 本身。通过 `package.json` 中的命令调用，不要直接运行。

| 文件 | 命令 | 作用 |
|---|---|---|
| `openapi-combine.mjs` | `openapi:combine` | 读取模块的 `paths/*.yml`，把每个路径的 `$ref`（以及安全方案）写进该模块的 `openapi.yaml`；路径重复时报错 |
| `openapi-bundle.mjs` | `openapi:bundle` | 调用 Redocly CLI，把 `openapi.yaml` 及其引用的所有文件合成 `dist/openapi.yaml` —— catalog 用 `$text` 读取的就是它 |
| `openapi-lint.mjs` | `openapi:lint` | 用 `redocly.yaml` 对 `dist/openapi.yaml` 执行 `redocly lint`：结构错误判为失败，风格问题只警告 |
| `openapi-build.mjs` | `openapi:build`、`openapi:check` | 先 combine 再 bundle —— 改完文档只需要这一条。加 `--check` 时不写文件只报告；`openapi:check` 再加上 lint，CI 运行的就是它 |
| `catalog-new.mjs` | `catalog:new` | 创建模块骨架：`catalog-info.yaml`、`openapi.yaml`、示例 `/ping` 片段、`all.yaml` 中的条目，然后立即编译 |
| `lib/modules.mjs` | — | 以上脚本共用：列出 `catalog/` 下的模块，解析模块名和 `--check`，模块名写错时报错 |

```
paths/*.yml ──combine──▶ openapi.yaml ──bundle──▶ dist/openapi.yaml ──lint──▶ 是否合法
                         （$ref 引用列表）         （单文件，提交到仓库）
```

日常只需要记两条：改完后 `corepack yarn openapi:build <模块>`，提交前 `corepack yarn openapi:check`。

## API 定义是如何生成的

**`paths/` 下的每个文件都是一份完整的 OpenAPI 文档** —— 例如 `creed-env-matrix/paths/api-example.yml`
—— 有自己的 `components`，其中的接口以 `#/components/schemas/...` 引用它们。

**1. 合并（combine）。** `openapi.yaml` 列出所有路径：每个路径一个 `$ref`，指向**定义该路径的文件内部**。

```yaml
paths:
  /users/{userId}:
    $ref: ./paths/api-example.yml#/paths/~1users~1{userId}     # '/' 转义为 ~1（RFC 6901）
```

OpenAPI 允许对单个 path item 使用 `$ref`，但不允许对整个 `paths` 对象使用，所以只能每个路径一行 ——
这份列表由脚本生成，不要手写。`scripts/openapi-combine.mjs` 在两个文件定义了同一路径（或同名但内容不同的
security scheme）时报错，并且只重写 `paths:` 和 `components.securitySchemes`；注释、`info`、`servers`
和 `security` 保持原样。它取各文件中最高的 `openapi` 版本，版本不一致时给出警告。各片段自己的 `info`、
`servers`、`tags` 和顶层 `security` 不会被带入：以入口文档的为准。

**2. 打包（bundle）。** `scripts/openapi-bundle.mjs` 运行 `redocly bundle`，跟随所有 `$ref` 写出
`dist/openapi.yaml`，在文件开头加上 "GENERATED — do not edit" 注释；若仍残留外部 `$ref` 则报错。
片段中的 schema 会被提升到打包文件的 `components` 中；两个文件都定义了 `User` 时，Redocly 会用不同的
名字同时保留两者。

**3. 校验（lint）。** `scripts/openapi-lint.mjs` 使用 `redocly.yaml`（`extends: minimal`）对打包结果执行
`redocly lint`。它能发现「YAML 能解析、但意思已经变了」的问题 —— 最典型的是流式映射里未加引号的 `, `：
`{ description: Unknown id, or gone }` 会被解析成一个 `description` 加一个名为 `or gone` 的键。

**4. 读取。** API 实体的定义写法：

```yaml
definition:
  $text: ./dist/openapi.yaml
```

`$text` 原样插入文件内容，所以 Swagger UI 展示的正是仓库里经过评审的那份文件，后端也不需要任何 OpenAPI
插件。如果把 `$text` 指向 `./openapi.yaml`，Swagger UI 拿到的将是未解析的 `$ref`。

**`dist/openapi.yaml` 必须提交。** `.gitignore` 中的 `!/catalog/*/dist/` 让它不受全仓库 `dist` 忽略规则
的影响。仓库根目录的 `bitbucket-pipelines.yml` 负责保证它与源文件一致：PR 上运行 `openapi:check`；在
`master` / `master-spring-boot-3` 上重新生成全部模块，并以 `[skip ci]` 提交回仓库。

## 操作步骤

以下命令都在 `creed-backstage/` 下执行。无论改什么，提交中都必须同时包含修改过的源文件**以及**重新生成的
`openapi.yaml` / `dist/openapi.yaml` —— 两者不一致时 CI 会让 PR 失败。

### 1. 修改已有的 API

1. 编辑片段文件，例如 `catalog/creed-payment/paths/payments.yml`（summary、schema、tags 等）。
   `info` / `servers` / `security` 在该模块的 `openapi.yaml` 里修改 —— 它们不是生成的。
2. 重新生成该模块并检查：
   ```bash
   corepack yarn openapi:build creed-payment
   corepack yarn openapi:check creed-payment      # 与 CI 相同的检查，只针对这个模块
   ```
3. 查看效果：在 `corepack yarn start` 运行时，打开该 API 的页面 → 点 **Refresh**（或等待下一轮目录刷新），
   再进入 **Definition** 标签页。
4. 将片段文件、`openapi.yaml` 和 `dist/openapi.yaml` 一起提交。

### 2. 给模块新增一个 API 文件

1. 把一份**完整的** OpenAPI 文档（`openapi`、`info`、`paths`、`components`）放到
   `catalog/<模块>/paths/<名称>.yml`。每个接口都写上 `tags:` —— Swagger UI 的 *Filter by tag* 就按它过滤。
   路径写完整（如 `/api/payment/{id}`），模块 `servers:` 的 URL 只写到主机根路径：Spring Boot 3 默认不匹配
   末尾斜杠，若 server 以 `/api/payment` 结尾、路径写 `/`，实际请求的是 `/api/payment/`，会返回 404。
2. 执行第 1 节的第 2–4 步。若新文件定义了模块内其他文件已有的路径，或同名但定义不同的 security scheme，
   `combine` 会报错。

### 3. 新增一个模块

```bash
corepack yarn catalog:new creed-inventory \
  --title Inventory \
  --description "Stock levels per warehouse" \
  --component creed-resource-inventory \
  --server https://localhost:18097
```

| 选项 | 默认值 |
|---|---|
| `--title` | 由模块名推出：`creed-inventory` → `Inventory` |
| `--description` | `<title> — functional module` |
| `--component` | `creed-resource-<short>`（`<short>` 为去掉 `creed-` 前缀的模块名） |
| `--server` | `http://localhost:8080/api/<short>` |
| `--owner` | `group:default/creed-platform` |

它会创建 `catalog/creed-inventory/` —— `catalog-info.yaml`（System `creed-inventory`、Component
`creed-resource-inventory`、API `creed-inventory-api`，都带 `inventory` 标签）、`openapi.yaml`，以及一个示例
片段 `paths/inventory-health.yml`（`GET /ping`）—— 把模块加入 `catalog/all.yaml`（保留原有注释），并立即生成
`dist/openapi.yaml`。目录已存在时拒绝覆盖。之后：

1. 用真实接口替换示例片段（见第 2 节）；如果 API 需要令牌，在 `openapi.yaml` 中加上 `security:`。
2. `corepack yarn openapi:build creed-inventory && corepack yarn openapi:check creed-inventory`。
3. 提交 `catalog/creed-inventory/`（含 `dist/`）和 `catalog/all.yaml`。

手动创建也一样：上述三个文件，加上 `all.yaml` 的 `targets` 中的一行，然后运行 `openapi:build`。

### 4. Bitbucket 一次性设置

1. 确认仓库托管在 Bitbucket，并在 **Repository settings → Pipelines → Settings** 中开启
   **Enable Pipelines**。`bitbucket-pipelines.yml` 必须放在仓库根目录。
2. 如果 `master` 或 `master-spring-boot-3` 设置了分支限制，在 **Repository settings → Branch restrictions**
   中把 **Bitbucket Pipelines** 加入允许写入的用户，否则重新生成步骤会在 `git push` 时失败。
3. 提一个修改了 `creed-backstage/catalog/**` 的 PR，确认 *OpenAPI bundle is up to date* 步骤运行并通过。

### 5. CI 做什么

只有 `creed-backstage/catalog/**`、`creed-backstage/scripts/**` 或 `creed-backstage/redocly.yaml` 有改动时
流水线才会运行；它只安装根工作区（`yarn workspaces focus root`），并且总是覆盖**全部**模块。

| 触发条件 | 步骤 | 结果 |
|---|---|---|
| Pull request | `yarn openapi:check` | 任一模块的 `openapi.yaml` / `dist/openapi.yaml` 过期或缺失，或打包结果未通过 lint，则失败 |
| 推送到 `master` / `master-spring-boot-3` | `openapi:build` + `openapi:lint`，然后提交 | 如有变化，向同一分支推送提交 `catalog: regenerate OpenAPI bundle [skip ci]`；`[skip ci]` 防止再次触发流水线 |

**PR 检查失败时：** 运行 `corepack yarn openapi:build`，修复 `openapi:lint` 报告的问题，提交并推送。

### 6. 排错

| 现象 | 原因 / 处理 |
|---|---|
| `✗ … is out of date` | 运行 `openapi:build`（指定模块或全部），提交结果 |
| `no catalog module 'x' — available: …` | 模块名写错，或该目录下没有 `openapi.yaml` |
| `external $ref left after bundling` | 某个 `$ref` 指向的文件 Redocly 无法内联 —— 路径相对于包含该 `$ref` 的文件 |
| `path … is defined in both paths/a.yml and paths/b.yml` | 同一模块的两个片段定义了同一路径；只保留在一个文件中 |
| `security scheme … differs between …` | 两个片段定义了同名但设置不同的 scheme；改成一致，或改名 |
| `not a complete OpenAPI document` | 某个 `paths/*.yml` 缺少 `openapi:` 或 `paths:` —— 片段必须是完整文档 |
| lint 报 `struct` 错误：`Property 'or …' is not expected here` | `{ … }` 中有未加引号的 `, ` —— 给值加引号：`{ description: 'a, b' }` |
| Definition 页仍显示旧版本 | 目录还没重新读取该实体 —— 在实体页点 **Refresh** |
| 新模块没有出现 | 没加入 `catalog/all.yaml` 的 `targets`，或目录尚未刷新 `creed-catalog` 这个 location |
| Swagger UI 显示未解析的 `$ref` | `catalog-info.yaml` 中 `$text` 指向了 `openapi.yaml`，而不是 `./dist/openapi.yaml` |
| 流水线在 `git push` 时失败 | 分支限制 —— 见第 4 节第 2 步 |
| `git add` 没有带上 `dist/openapi.yaml` | `.gitignore` 中的 `!/catalog/*/dist/` 被删掉了 |

## 为什么目录要通过 HTTP 提供

`app-config.yaml` 把目录注册为 `url: http://localhost:7007/api/catalog-files/all.yaml`，而不是
`type: file` 类型的 location。Backstage 用 `new URL(path, location.target)` 解析占位符中的相对路径，并通过
URL reader 读取。file 类型 location 的 target 是一个裸的文件系统路径，无法构造出 URL，而且也没有读取本地
文件的 URL reader —— `$text: ./dist/openapi.yaml` 会直接失败。

因此 `packages/backend/src/catalogFiles.ts` 在设置了 `creed.catalogFiles.directory` 时，以只读方式在
`/api/catalog-files/*` 下提供 `catalog/` 目录，`backend.reading.allow` 只允许目录读取这一个路径。修改会在
目录下一次刷新时生效。

| 配置 | `creed.catalogFiles.directory` |
|---|---|
| `app-config.yaml`（`yarn start`） | `../../catalog` —— 本地检出的目录（相对于 `packages/backend`） |
| `+ app-config.production.yaml`（镜像） | `./catalog` —— 由 `packages/backend/Dockerfile` 复制进镜像 |
