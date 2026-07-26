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

- **冲突（conflict）**：在应当唯一的范围内，两个 endpoint 解析到相同 `host:port` 或 `ip:port`。
  「应当唯一的范围」由 `env-matrix.conflict.scope` 显式配置：`TIER_ENV`（默认，单个环境实例内唯一）/
  `TIER`（整个层级内唯一）/ `GLOBAL`（全局唯一）。默认不把「同一地址在两个不同环境中复用」判为冲突。


## 3. 技术栈

- 前端：React + TypeScript + Vite + Ant Design Pro。
- 后端：[creed-resource](../creed-resource)下创建新的resource
- 联调：Vite 将 `/api` 代理到 `http://localhost:3001`。

## 4. 目录结构（目标）

```
.
├── src/                 # React 前端
│   ├── pages/
│   │   ├── Matrix       # 矩阵视图（首页 /）
│   │   └── Config       # CRUD 编辑页（/config）
│   └── api/             # 前端 API 封装
├── server/
│   ├── index.(js|ts)    # mock API 服务
│   └── mock.json        # 数据源（唯一真相）
├── grafana/             # Grafana 演示与说明
└── vite.config.ts       # 含 /api → :3001 代理
```

## 5. 功能需求

### 5.1 矩阵视图（`/`）
- 以 **service × country** 聚合展示单元格；支持按 App system / Tier / Env instance 过滤。
- 高亮存在冲突的单元格。

### 5.2 配置编辑页（`/config`）
- 表格化展示全部 endpoint，支持增、删、改。
- 「保存到文件」按钮：校验后通过 写回 数据库。


## 6. 命令

```bash
npm install
npm run dev    # 启动 UI（:5173）
```

- 矩阵视图：`http://localhost:5173/`

## 7. 约定（给 Claude 的工作准则）

- 基于Ant Design Skills
- 前端文案保持中英文一致风格创建中英文的文档。
- 每次代码调整完成之后，将文案文档做出相应的更新
- 新增维度取值时，同步更新本文件第 2 节的数据模型表。
