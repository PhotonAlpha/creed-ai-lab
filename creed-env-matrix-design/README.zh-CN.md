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
npm run mock                                        # 终端 1 —— Mock API 运行在 :3001
VITE_API_TARGET=http://localhost:3001 npm run dev   # 终端 2 —— UI 运行在 :5173
```

（这个环境变量不是可选的 —— 见下面的《`npm run dev` 实际连的是哪个后端》。）

### 方式 B —— 真实后端（PostgreSQL）

```bash
# 1. 首次需要创建数据库
docker exec creed-artifactory-db createdb -U artifactory env_matrix

# 2. 在 :3001 启动后端（明文 HTTP；首次启动 Flyway 会自动建表并写入种子数据）
cd .. && mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"

# 3. 启动 UI，并把代理指向 :3001
VITE_API_TARGET=http://localhost:3001 npm run dev
```

浏览器打开 <http://localhost:5173/>。

### `npm run dev` 实际连的是哪个后端

**仓库里提交的 `.env` 指向的是 HTTPS 配置，不是 mock：**

```
VITE_API_TARGET=https://localhost:18095
```

因此直接 `npm run dev` 会把 `/api` 代理到 **:18095** 上的 `creed-resource-env-matrix`
（`primary` / `secondary` / `cloud`；`vite.config.ts` 里的 `secure: false` 才让 Creed-CA 自签证书
可用）。上面 A、B 两种方式都监听 **:3001**，需要把目标改回去：

```bash
VITE_API_TARGET=http://localhost:3001 npm run dev
```

排查「改了没生效」之前值得先确认这一点：只要 HTTPS 后端在跑，无论有没有启动 `npm run mock`，界面都
会正常显示数据 —— 于是一个没重启的旧后端，看起来和前端坏掉一模一样。shell 里导出的
`VITE_API_TARGET` 优先于 `.env`，两者都由 `vite.config.ts` 中的 `loadEnv` 读取。

### 脚本

| 脚本 | 作用 |
|---|---|
| `npm run dev` | Vite 开发服务器（`:5173`），将 `/api` 代理到 `VITE_API_TARGET`（`.env`：`https://localhost:18095`） |
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

### 拓扑图（`/topology`）

把当前过滤出的切面画成图。每个 endpoint 一张卡片——服务名、`ip:port`、实例、协议,左侧色条表示健康
状态——先按参与者装进虚线分组框,这些分组框再按应用系统装进外层分组框。

四种连线,工具栏可逐个开关:

| 连线 | 含义 | 来源 |
|---|---|---|
| 实线箭头 | 声明的依赖,参与者 → 参与者 | 一行 `env_release_link` |
| 灰虚线 | 两个 endpoint 落在同一个 `host` 上 | 由 `/endpoints` 推导 |
| 蓝点线 | 两个主机名解析到同一个 `ip` | 由 `/endpoints` 推导 |
| 红虚线 | 同一个 `host:port` 或 `ip:port` 被占用两次 | `/conflicts` |

**箭头是声明出来的数据,不是观测到的。** `env_endpoint` 记录的是地址,没有任何一列表达「A 调用 B」。
连接关系有自己的表,在 **配置编辑 → Release 拓扑** 页维护。

一个 **release** 是一组被命名的环境切面加上它们之间的连接:

| 表 | 内容 |
|---|---|
| `env_release` | 名称、环境层级(仅作标签)、状态(`DRAFT`/`ACTIVE`/`ARCHIVED`) |
| `env_release_node` | 一个 **参与者** —— `(应用系统, 国家/地区, 环境实例)`,以及画在哪里(`layer`、`sort_order`) |
| `env_release_link` | 两个参与者之间的连接,以及 `direction` |

**本页必须选定一个 release。** 连接之所以不能只写两个应用系统,是因为同一个应用系统可以在一条链里
出现两次:

```
SG CCS SIT3  →  Global-CCS SIT2  →  CN CCS SIT5
```

所以拓扑图的节点是一个 *切面*,而 release 负责说明哪些切面属于一起。这也正是让其他维度保持正交的
方式 —— 国家/地区、环境实例、服务、实例都只是纯数据,仅由 release 串联起来。

每个参与者会收拢匹配其切面的 endpoint;`country = '*'` 表示「不区分国家」,匹配所有区域。没有任何
匹配 endpoint 的参与者画成**虚线占位节点** —— 「已接入拓扑」与「已录入矩阵」之间的这个缺口,正是这
个工具要暴露的东西。当前视图中不属于任何参与者的 endpoint 会以横幅计数提示,而不会画出来。

按国家/地区或环境实例收窄只过滤*框内的 endpoint*,绝不过滤连接关系 —— 否则一条连接会因为无关的过滤
条件而消失。

列的顺序由连接本身推导:`rankParticipants` 按存储的 `source -> target` 方向做最长路径分层,所以
流向轴本身就是层级关系。`direction` 不参与分层 —— 把双向连接当成两条边会让每一对都成为环。两种布局:
**分层** 与 **按系统聚类**,坐标都由 `pages/Topology/buildGraph.ts` 计算,不走 G6 布局。

#### topology 与 endpoint 之间的推导关系

本模块的两半记录的是两类不同的事实,而图是二者的连接(join)。`env_endpoint` 说明的是**某个东西在
哪里应答**;它里面没有任何一列、也没有任何可以从 host / port 算出来的东西,能说明「A 调用 B」。
`env_release_node` / `env_release_link` 说明的是**谁与谁通信**,而它们完全不知道地址。两张表之间
**故意不设外键**。

图上的每一样东西,要么直接来自其中一张表,要么来自作用在其上的一条规则:

| 图上元素 | 来源 | 规则 |
|---|---|---|
| Endpoint 卡片 | 过滤结果中的一行 `env_endpoint` | 只有被某个参与者认领时才画出来 |
| 虚线占位节点 | 一行 `env_release_node` | 该参与者没有匹配到任何 endpoint |
| 参与者分组框 | 一行 `env_release_node` | 无论有没有 endpoint 都会画 |
| 分组框画在哪 | `env_release_node.layer` / `.sort_order` | 覆盖该参与者由连接推导出的层级 |
| 应用系统分组框 | 参与者分组框 | 按 `appSystem` 聚类 —— 仅是展示层 |
| 实线箭头 | 一行 `env_release_link` | 画在分组框之间,绝不画在卡片之间 |
| 灰虚线 | endpoint | 同 `host`,按端口串成链 |
| 蓝点线 | endpoint | 同 `ip` 不同 `host`,每个主机名取一张代表卡片 |
| 红虚线 | `GET /conflicts` | 冲突由后端判定,图只负责画 |
| 层级(列 / 行) | 连接关系 | 按 `source -> target` 做最长路径,见下 |
| Endpoint 计数、「未认领」横幅 | endpoint | 没有被任何参与者认领 |

**认领规则**就是这个 join 的全部,而且只有一个方向 —— 由参与者去认领 endpoint:

```
参与者(应用系统, 国家/地区, 环境实例)  ⟕  endpoint(应用系统, 国家/地区, 环境实例)

参与者的 country = '*' 匹配所有国家/地区
第一个匹配上的参与者胜出;排序时具体切面排在通配切面之前
```

读图之前值得先知道由此带来的三个后果:

- **没有被任何参与者认领的 endpoint 不会出现在画布上**,而是记入横幅计数 —— 这说明的是 release
  没有覆盖到它,而不是这个 endpoint 有问题。
- **没有匹配到 endpoint 的参与者仍然会出现在画布上**,画成虚线占位节点。「已接入拓扑」与「已录入
  矩阵」之间的这个缺口,正是这个工具要暴露的东西。
- **endpoint 过滤条件永远不影响连线。** 它改变的是分组框里有哪些卡片;分组框、箭头和分层只按
  release id 拉取。

**分层**只由连接关系推导,不看别的:

```
layer(p) = 0                          若没有任何声明的连接指向 p
         = max(layer(source)) + 1     对每条 source -> p 的连接取最大值
         (回到当前路径上的边会被跳过,因此用户声明出来的环不会让遍历挂死)
```

`direction` 不参与:它只决定箭头样式,而把 `BIDIRECTIONAL` 当成两条边会让每一对都变成二元环,分层
也就无从谈起。

在此基础上,参与者可以被**钉住**:`env_release_node.layer` 替换该参与者的层号,其下游仍留在连接关系
给出的位置上。`null`(每个参与者的初始状态)表示「按连接推导」,而且不能用 `0` 表达 —— `0` 是「第 0
列」。`sort_order` 对另一条轴做同样的事,应用系统整体移动(一个聚类取其成员中最小的 sort_order)。
两者都在图上的 **层级与顺序** 对话框里编辑。

反方向的推导一概不存在:新增 endpoint 不会产生参与者或箭头,新增连接也不会产生 endpoint。图上关于
「谁调用谁」的一切,都是有人在 **配置编辑 → Release 拓扑** 页里录进去的。

#### 读图控件

| 控件 | 作用 | 保存在 |
|---|---|---|
| **分层 / 按系统聚类** | 沿一个轴展开层级,或每个应用系统一块 | 当前浏览器 |
| **→ ← ↓ ↑** | 分层视图的流向;第 0 层位于箭头的尾端 | 当前浏览器 |
| **按应用系统分组** | 为同一应用系统的参与者画一个外框,并把它们排在一起 | 当前浏览器 |
| **层级与顺序** | 把参与者钉到指定层级,或调整应用系统在垂直轴上的先后顺序 | release |

这里的分界是「读图的人怎么看」与「这张图说了什么」。方向与应用系统外框属于阅读习惯,保存在
`localStorage`,换 release 也保留;而钉住的层级是对这套环境的一个判断 ——「不管连接怎么画,这个切面
就是独立的一步」—— 所以它属于 release 本身,存在 `env_release_node.layer` / `.sort_order`,下一个
打开这个 release 的人看到的是同一张图。

**层级与顺序**对话框把改动暂存,由一次 **保存** 一起写入;在保存落地之前,后面的图不会动。写入走的是
配置编辑页同一个具有权威性的 `PUT /releases/{id}/topology`,因此它会把该 release 的整份拓扑原样重发,
只替换这两个字段。清空单元格(`自动`)即把该参与者交还给推导出的分层,**全部重置**则交还整个
release —— 两者都仍需保存。

由于同一个应用系统完全可能出现在多个层级上,分层视图里的应用系统分组框是按 *(应用系统, 层级)* 来
分的:第 0 列的 `SG CCS SIT3` 和第 2 列的 `CN CCS SIT5` 是两个框,而不是一个横跨中间所有内容的
大框。

### 配置编辑（`/config`）

分为两个标签页。

**Endpoint** —— 完整的端点表格，支持新增 / 修改 / 删除，然后点击**保存到数据库**。

保存会写入**整张表**：在界面上移除的行会从数据库中删除。因此该页面始终加载完整、未经服务端过滤的数据，
并在客户端做筛选 —— 提交一个被过滤过的子集会导致过滤掉的数据被删除。实际未发生变化的行既不会计数也
不会写库，所以只改一个字段时提示的是「更新 1 条」而不是「更新 1235 条」。

校验失败会返回 `422` 并携带逐行错误，且**不写入任何数据** —— 整次保存是一个事务。

**Release 拓扑** —— 左侧 release 列表，右侧选中 release 的参与者与连接，一个保存按钮。保存只对该
release 具有权威性：在此删除的行会从数据库中删除，其他 release 完全不受影响。

参与者用自由输入编辑（尚无 endpoint 的切面是合法的，会显示为占位节点）；连接的两端从本 release 的
参与者中选取，所以必须先有参与者才能连线。`layer` 与 `sort_order` 在这里不可编辑 —— 层级只有放在周围
的分组框旁边才有意义，所以它在图上设置 —— 但这一页保存时会原样带上它们：保存对整个 release 具有权威性，
漏掉这两个字段就等于清掉别人排好的层级。刚添加、尚未保存的参与者同样可选 —— 编辑器会把它作为
`ref` 提交，由保存时解析成新行的 id。同时声明 `A → B` 和 `B → A` 会被拒绝，双向连接请用
`BIDIRECTIONAL`。

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
| `GET` | `/releases` | Release 列表；`?tier=` `?status=` 过滤 |
| `GET` | `/releases/{id}` | 单个 release |
| `POST` | `/releases` | 新增 → `201`；重名返回 `409` |
| `PUT` | `/releases/{id}` | 更新 |
| `DELETE` | `/releases/{id}` | 删除 → `204`，参与者与连接一并删除 |
| `GET` | `/releases/{id}/topology` | `{release, nodes[], links[]}` —— 图的数据源 |
| `PUT` | `/releases/{id}/topology` | 替换某个 release 的拓扑 → `200`，或 `422` 并返回逐行错误 |
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
│       ├── Topology/   # 拓扑图（/topology）
│       │                # buildGraph.ts 为纯函数：endpoints + conflicts + links -> 节点/边
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
- **`npm run typecheck` 用的是 `tsc -b`，不是 `tsc --noEmit`。** 根 `tsconfig.json` 是 solution
  形式，`--noEmit` 不会跟随 project references —— 即使满是类型错误也会返回 0。
- **只引 `@antv/g6`，不用 `@ant-design/graphs`。** 后者为了省下一层薄封装会带进
  `styled-components@6` 和 `@antv/graphin`；本项目已经为 antd 5 打了一个 React 19 兼容补丁，不需要
  再添一个变量。G6 本身不声明任何 React peer 依赖。
- **`react-router-dom` 7.18.1** 在 `npm audit` 中仍会命中 `GHSA-qwww-vcr4-c8h2`（RSC 模式 CSRF）。本项目
  是纯 `BrowserRouter` SPA，未使用 RSC 模式和 server actions，该路径不可达，且 7.x 尚无修复版本。**请勿降级**：
  7.11.0 及更早版本存在 14 个已在 7.18.0 修复的公告。
