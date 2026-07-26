# 环境映射矩阵（Env Matrix Viewer）

[English](./README.md)

环境 **主机 / IP / 端口** 映射矩阵 —— 用于查看和编辑 `CCS` / `MS` / `AliYunTeir` / `TencentTeir`
在 `SIT` / `UAT` / `NFT` / `PROD` 各环境下的端点配置。

这个工具的核心目的是**排查冲突**：一眼看出哪些本应互不相同的端点，实际解析到了同一个地址。

| | |
|---|---|
| 前端 | React 19 + TypeScript + Vite 8 + Ant Design 5 / Ant Design Pro 组件 |
| 后端 | [`creed-resource-env-matrix`](../creed-resource/creed-resource-env-matrix)（Spring Boot 3.5、PostgreSQL） |
| Mock 后端 | `server/index.js` —— 契约完全一致，无需数据库 |

---

## 1. 快速开始

### 方式 A —— Mock 后端（无需数据库、无需 JDK）

```bash
npm install
npm run mock     # 终端 1 —— Mock API 运行在 :3001，数据来自 server/mock.json
npm run dev      # 终端 2 —— UI 运行在 :5173
```

### 方式 B —— 真实后端（PostgreSQL）

```bash
# 1. 首次需要创建数据库
docker exec creed-artifactory-db createdb -U artifactory env_matrix

# 2. 在 :3001 启动后端（明文 HTTP；首次启动 Flyway 会自动建表并写入种子数据）
cd .. && mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"

# 3. 启动 UI
npm run dev
```

浏览器打开 <http://localhost:5173/>。

两种方式都在 `3001` 端口提供完全相同的契约，因此前端无需任何改动即可切换。若要改为访问该模块常规的
HTTPS 配置：

```bash
VITE_API_TARGET=https://localhost:18095 npm run dev
```

### 脚本

| 脚本 | 作用 |
|---|---|
| `npm run dev` | Vite 开发服务器（`:5173`），将 `/api` 代理到 `:3001` |
| `npm run mock` | Node Mock API（`:3001`，可用 `PORT=…` 修改） |
| `npm run build` | 类型检查并产出 `dist/` |
| `npm run typecheck` | 仅做类型检查 |

---

## 2. 数据模型

一个**端点（endpoint）**由七个维度唯一标识，并映射到一组 `host` / `ip` / `port`。

| 维度 | 取值示例 |
|---|---|
| 应用系统 App system | `CCS`、`MS`、`AliYunTeir`、`TencentTeir` |
| 环境层级 Tier | `SIT`、`UAT`、`NFT`、`PROD` |
| 环境实例 Env instance | `SIT1`–`SIT2`、`UAT1`–`UAT3`、`NFT1`、`PROD1` |
| 国家/地区 Country | `CN`、`SG`、`MY`、`HK`、`GD`、`ID` |
| 服务 Service | `MS1`–`MS6`、`CCS1`–`CCS6`、`AliYunTeir1`–`AliYunTeir4`、`TencentTeir1`–`TencentTeir3` |
| 实例 Instance | `Green`、`Green2`（主备 Active-Standby） |
| 协议 Scheme | `http`、`https` |

`scheme` 是**身份的一部分**：同一个服务同时暴露 http 和 https 端点是合理的，这属于两条记录，而不是重复。

维度取值以纯文本存储，不使用枚举。`GET /api/env-matrix/dimensions` 会根据实际存在的数据推导出 UI 的
过滤选项，因此新增一条带有新国家或 `UAT6` 的记录后，下拉框会自动扩展，无需改代码。

---

## 3. 冲突

冲突指的是：在本应唯一的范围内，两个端点解析到了同一个地址。系统会独立检查两个键：

- **`host:port`** —— 最直观的冲突，两个逻辑端点指向同一个监听器；
- **`ip:port`** —— 被 DNS 掩盖的冲突，两个主机名解析到同一个地址。

### 唯一性范围

「本应唯一的范围」是显式且可配置的 —— `env-matrix.conflict.scope`：

| 范围 | 含义 |
|---|---|
| `TIER_ENV`（默认） | 在单个 `tier/envInstance` 内唯一，例如 `UAT/UAT1` |
| `TIER` | 在整个环境层级内唯一，即 `UAT1`…`UAT5` 之间不得重叠 |
| `GLOBAL` | 在整个环境体系内全局唯一 |

默认配置**刻意不会**把「同一地址在两个不同环境中复用」判为冲突 —— 环境相互隔离本来就是这个目的。
但仅 `scheme` 不同的端点**会**冲突：一个端口不可能同时提供 http 和 https 服务。

冲突检测基于**过滤后**的数据集运行，因此高亮结果始终能由当前屏幕上的行来解释。把过滤条件收窄到冲突
的一侧，该冲突就会消失。

种子数据中包含四处**刻意植入**的冲突（见 `note` 列），以保证全新数据库下冲突面板不为空。

---

## 4. 健康检查

健康状态默认由**后端模拟**（`env-matrix.health.mode=mock`）：结果是 `host:port` 与一个可轮换种子的
纯函数，不产生任何网络流量。矩阵描述的环境通常是本进程无法访问的，真实探测只会返回一整片 `DOWN`，
没有任何信息量。

模拟状态在多次调用之间**保持稳定**是刻意设计的 —— 如果每次渲染都重新随机，矩阵就会不停闪烁。点击
**重新检查**会轮换种子，从而以确定性的方式改变结果。

设置 `env-matrix.health.mode=real` 可改为对 `ip:port` 做 TCP 连接探测。它只能证明端口有监听，并不能
说明端口背后的服务是否健康。UI 始终显示当前模式，避免把模拟出来的绿色对勾误当作真实可达性报告。

Mock 服务器移植了 Java 的 `String.hashCode` 实现，因此在相同种子下，同一个 `host:port` 在两套后端中
返回**完全相同**的状态。

---

## 5. 页面

### 矩阵视图（`/`）

`service` 为行、`country` 为列。每个单元格汇总该交叉点上的所有端点 —— 通常是主备实例乘以在用的协议
—— 并显示协议、端口、实例和健康状态圆点。

- 支持按任意维度过滤；`scheme` 是「全部 / http / https」三态控件。
- 冲突单元格会高亮并带角标，冲突端口以红色显示。
- **冲突单元格**开关可隐藏所有无冲突的行。
- 冲突面板列出每个冲突地址，以及占用该地址的端点明细。

首次加载时默认选中第一个环境实例。若不过滤，每个单元格都会堆叠所有环境的端点，表格将无法阅读。该默
认值是一个可见、可清除的过滤条件，而不是隐藏的查询参数。

### 配置编辑（`/config`）

完整的端点表格，支持新增 / 修改 / 删除，然后点击**保存到数据库**。

保存会写入**整张表**：在界面上移除的行会从数据库中删除。因此该页面始终加载完整、未经服务端过滤的数据，
并在客户端做筛选 —— 提交一个被过滤过的子集会导致过滤掉的数据被删除。实际未发生变化的行既不会计数也
不会写库，所以只改一个字段时提示的是「更新 1 条」而不是「更新 1235 条」。

校验失败会返回 `422` 并携带逐行错误，且**不写入任何数据** —— 整次保存是一个事务。

---

## 6. API

基础路径 `/api/env-matrix`。过滤条件使用可重复的查询参数 —— `?tier=UAT&tier=SIT&scheme=https`
—— 均为可选，不同维度之间是「与」的关系。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/ping` | 存活探测 + 当前健康探测模式 |
| `GET` | `/dimensions` | 各维度的去重取值，用于过滤下拉框 |
| `GET` | `/endpoints` | 平铺列表（可过滤） |
| `GET` | `/endpoints/{id}` | 单个端点 |
| `POST` | `/endpoints` | 新增 → `201`；身份重复返回 `409` |
| `PUT` | `/endpoints/{id}` | 更新 |
| `DELETE` | `/endpoints/{id}` | 删除 → `204` |
| `PUT` | `/endpoints` | 整表批量保存 → `200`，或 `422` 并返回逐行错误 |
| `GET` | `/matrix` | `服务 × 国家` 矩阵及冲突 |
| `GET` | `/conflicts` | 仅返回冲突分组 |
| `GET` | `/health` | 各端点状态、汇总及探测模式 |
| `POST` | `/health/recheck` | 重新执行探测（轮换模拟种子） |

错误统一使用同一个结构：`{error, message, fields?, time}`。

记录冲突是**被允许的** —— 发现并记录冲突正是这个工具的意义，因此冲突只会被报告，绝不会被拒绝。只有
**身份重复**才会被拒绝（`409`）。

---

## 7. 目录结构

```
.
├── src/
│   ├── api/            # 类型化客户端 + 与后端对应的 DTO
│   ├── components/     # FilterBar、健康标签/圆点
│   ├── hooks/          # useDimensions
│   ├── locales/        # en-US / zh-CN 及 Provider
│   └── pages/
│       ├── Matrix/     # 矩阵视图（/）
│       └── Config/     # 增删改查编辑页（/config）
├── server/
│   ├── index.js        # Mock API —— 契约一致，零依赖
│   └── mock.json       # Mock 数据源，保存时会被回写
└── vite.config.ts      # /api → :3001 代理
```

`server/mock.json` 刻意纳入版本管理：它是 Mock API 的唯一真相来源。配置页保存时 Mock 服务器会就地重写
该文件，因此在 Mock 模式下使用界面后会看到它产生 diff。

---

## 8. 国际化

支持 English 与简体中文，通过顶部导航切换。选择会保存在 `localStorage`，初始语言跟随浏览器。antd 自带的
语言包会同步切换，因此分页、空状态等内置文案也会一起变化。

`zh-CN.ts` 的类型声明为 `Record<keyof typeof enUS, string>` —— 漏翻译会导致编译报错，而不是静默回退。
**修改界面文案时，请同时更新这两个文件。**

---

## 9. 依赖说明

- **使用 antd 5 而非 6。** `@ant-design/pro-components@2.8.10` 声明的 peer 依赖是
  `antd: ^4.24.15 || ^5.11.2`，不支持 antd 6；而兼容 antd 6 的 Pro 版本（`3.1.14-5`）目前仍是预发布版。
- **必须引入 `@ant-design/v5-patch-for-react-19`**，并在 `main.tsx` 中最先导入：antd 5 面向 React 16–18，
  缺少该补丁会打印兼容性告警，且 Modal / message 在 React 19 下行为异常。
- **`path-to-regexp` 版本覆盖。** `@ant-design/pro-layout` 将其锁定在 `8.2.0`，该版本存在两个高危 ReDoS
  公告；`overrides` 将其提升到已修复的 `8.4.2`，同时无需降级 Pro。
- **`react-router-dom` 7.18.1** 在 `npm audit` 中仍会命中 `GHSA-qwww-vcr4-c8h2`（RSC 模式 CSRF）。本项目
  是纯 `BrowserRouter` SPA，未使用 RSC 模式和 server actions，该路径不可达，且 7.x 尚无修复版本。**请勿降级**：
  7.11.0 及更早版本存在 14 个已在 7.18.0 修复的公告。
