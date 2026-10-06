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
| BFF | `server/bff.js` —— 托管 `dist/`，**负责 Splunk 会话代理**（`server/splunk/`），其余 `/api` 反向代理到后端 |

---

## 1. 快速开始

### 方式 A —— Mock 后端（无需数据库、无需 JDK）

```bash
npm install
npm run mock       # 终端 1 —— Mock API 运行在 :3001（含 Splunk 会话代理，始终为 mock）
npm run dev:mock   # 终端 2 —— UI 运行在 :5173，两个代理目标都指向 :3001
```

（直接 `npm run dev` 连不到 mock —— 见下面的《`npm run dev` 实际连的是哪个后端》。）

### 方式 B —— 真实后端（PostgreSQL）

```bash
# 1. 首次需要创建数据库
docker exec creed-artifactory-db createdb -U artifactory env_matrix

# 2. 在 :3001 启动后端（明文 HTTP；首次启动 Flyway 会自动建表并写入种子数据）
cd .. && mvn -pl creed-resource/creed-resource-env-matrix spring-boot:run \
  -Dspring-boot.run.profiles=dev -Dspring-boot.run.workingDirectory="$PWD"

# 3. 在 :3002 启动 BFF —— Splunk 页面调用它（审计默认存内存；SPLUNK_AUDIT_STORE=pg 或 mysql 时写入数据库）
npm run bff

# 4. 启动 UI，并把代理指向 :3001
VITE_API_TARGET=http://localhost:3001 npm run dev
```

浏览器打开 <http://localhost:5173/>。

### 方式 C —— 部署形态：BFF

```bash
npm run build
cp .env.server.example .env.server.local   # 填入密钥；已被 git 忽略
npm run bff                                # :3002 —— dist/ + Splunk 会话代理 + /api 反向代理
```

<http://localhost:3002/> 一个进程搞定：`/api/env-matrix/splunk/*` 由 BFF 自己处理，其余 `/api/*`
反向代理到 `ENV_MATRIX_API_TARGET`（默认 `https://localhost:18095`，除非设置
`ENV_MATRIX_API_INSECURE=false`，否则不校验证书），其他路径返回 `dist/`，前端路由回落到
`index.html`。审计默认存在内存中；`SPLUNK_AUDIT_STORE=pg` 或 `mysql` 时写入数据库，连不上数据库则拒绝启动。

**完整的 编译 → 打包 → 运行 指南（systemd、Docker、反向代理、检查清单、故障排查）：[DEPLOY.zh-CN.md](./DEPLOY.zh-CN.md)。**

### `npm run dev` 实际连的是哪个后端

**仓库里提交的 `.env` 指向的是 HTTPS 配置，不是 mock：**

```
VITE_API_TARGET=https://localhost:18095
VITE_SPLUNK_TARGET=http://localhost:3002
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

`/api/env-matrix/splunk` 单独代理到 `VITE_SPLUNK_TARGET`：Java 服务已不再提供这些接口，由 BFF
（`npm run bff`，:3002）或 mock（:3001）应答 —— `npm run dev:mock` 会把两个目标都指向 mock。

### 脚本

| 脚本 | 作用 |
|---|---|
| `npm run dev` | Vite 开发服务器（`:5173`），将 `/api` 代理到 `VITE_API_TARGET`（`.env`：`https://localhost:18095`） |
| `npm run dev:mock` | 等同 `npm run dev`，但 `VITE_API_TARGET` 与 `VITE_SPLUNK_TARGET` 都指向 mock（`:3001`） |
| `npm run mock` | Node Mock API（`:3001`，可用 `PORT=…` 修改） |
| `npm run bff` | BFF（`:3002`，可用 `BFF_PORT` 修改）；会加载 `.env.server.local`，全部变量见 `.env.server.example` |
| `npm run package:bff` | 把 `dist/` 和 BFF 复制到 `release/env-matrix-bff/`，只安装 `pg` —— 见 [DEPLOY.zh-CN.md](./DEPLOY.zh-CN.md) |
| `npm run test:server` | 用 `node --test` 跑 Splunk 会话代理的测试（`server/**/*.test.js`） |
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

**Endpoint** —— 完整的端点表格，支持新增 / 修改 / 复制 / 删除，然后点击**保存到数据库**。
行内的**复制**会打开以该行取值预填的新增对话框，确认后才会添加。只要副本的 **主机名 + IP** 与表格中已有的
某一行相同，就无法确认 —— 需修改其中之一。这项检查只属于复制对话框：保存仍按七个维度校验身份，新增和修改
不受影响，因为同一服务的 http 与 https 监听本来就共用同一个 主机名 + IP。

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

### Splunk 会话（`/splunk`）

一个凭据代理：共享的 Splunk 账号保存在 **Node BFF**（`server/bff.js`，代码在 `server/splunk/`），
用户拿到可用的 Splunk Web 会话，却始终看不到密码。它原先位于 `creed-resource-env-matrix`，
Java 服务现已不再提供这些接口。

1. **一次性验证码。** 标准 TOTP（RFC 6238，HMAC-SHA1，6 位，30 秒一个窗口，前后各允许 1 个窗口的
   误差）。左侧卡片按**服务端**时钟倒计时；由于开启了 `ENV_MATRIX_TOTP_EXPOSE_CODE`，页面会轮流
   显示当前验证码 —— 因此任何能打开本页的人都能通过校验。关闭它，验证码就必须来自用
   `ENV_MATRIX_TOTP_SECRET` 绑定的身份验证器 App。一个验证码**只能使用一次**；同一地址一分钟内输错
   5 次，之后一分钟内返回 `429`。
2. **登录 Splunk。** **登录目标**下拉框列出 BFF 配置的各个 Splunk 实例（`SPLUNK_TARGETS=SIT,UAT`，
   每个目标配置 `SPLUNK_TARGET_<ID>_LOGIN_URL` / `_USERNAME` / `_PASSWORD`；未设置时只有一个 `default`
   目标，取自 `SPLUNK_LOGIN_URL` / `SPLUNK_USERNAME` / `SPLUNK_PASSWORD`）。选择后页面显示该目标的登录地址和
   用户名，BFF 登录时也随之换成该目标的账号；密码不会离开服务端 —— 页面只显示是否已配置。所选目标按浏览器
   记住。缺少地址、用户名或密码的目标会被标出，且无法提交。这些都在校验验证码**之前**检查：未知目标返回
   `400`，未配置齐全返回 `503`，两种情况验证码都不会被消耗。验证码通过后，BFF 先 `GET` 登录页拿到 `cval`
   cookie，再把 `username=…&password=…&cval=…` 以表单 `POST` 到该目标的登录地址，**不跟随重定向**
   （cookie 在 303 响应上），从 `Set-Cookie` 中读取 `splunkd_8000`。**默认不校验 Splunk 的 TLS
   证书**（`SPLUNK_TLS_INSECURE=true`：既不校验证书链，也不校验主机名）；设为 `false`（私有 CA 再配
   `SPLUNK_CA_FILE`）即开启校验。
3. **脚本。** 该值以脚本形式返回，粘贴到 Splunk 页面的开发者工具控制台执行：
   `document.cookie = "splunkd_8089=<值>; path=/; Secure; SameSite=Lax";` 读取的 cookie 名与写入的
   cookie 名是两个独立配置（`SPLUNK_SESSION_COOKIE` / `SPLUNK_SCRIPT_COOKIE_NAME`）。
4. **审计。** 每一次验证码校验、每一次 Splunk 调用都记一行审计 ——
   结果、原因、客户端地址、`X-Forwarded-For`、User-Agent、时间窗口、Splunk 状态码、耗时。同一次请求的
   两行共享一个关联 ID。cookie 本身**从不**保存 —— 只保存其 SHA-256 的前 16 个十六进制字符，足以与
   Splunk 自身日志中的会话对应。审计是强制的：写不进审计行时请求直接失败，绝不签发未记录的会话。
   **存到哪里由 `SPLUNK_AUDIT_STORE` 决定：** `memory`（默认 —— 保留最新 500 条，重启即丢失）、
   `pg`（PostgreSQL 的 `splunk_broker.splunk_audit`）或 `mysql`（`SPLUNK_DB_URL` 所指数据库中的
   `splunk_audit`，MySQL 8 / MariaDB 10.5+）；后两者的连接参数都是 `SPLUNK_DB_*`。

**密钥**来自环境变量；`ENV_MATRIX_TOTP_SECRET`、`SPLUNK_PASSWORD`、`SPLUNK_DB_PASSWORD` 也可以用
`NAME_FILE`（存放该值的文件 —— Docker / Kubernetes secret、Vault agent 输出文件）。`npm run bff`
还会加载 `.env.server.local`；全部变量见 `.env.server.example`。

**真实调用由开关控制。** `SPLUNK_ENABLED` 默认 `false`：客户端直接返回伪造的 `mock-…` 值，不发出
任何请求。Node mock 始终伪造，且审计只保存在内存中。页面会把 mock 值明确标注出来。

**为什么用独立 schema**（仅 pg）。 审计表是 `splunk_broker.splunk_audit`，而不是 `public.splunk_audit`：
后者是 Java 模块的 Flyway V6，无法从该模块删除；如果 Node 先建出同名表，全新数据库上 V6 就会失败。
BFF 首次启动时会把 `public.splunk_audit` 中已有的记录复制一次过来，原表保持不动 —— 确认无用后可手动删除。

验证码一经校验即被消耗，因此遇到「已使用」或 Splunk 调用失败时，页面会清空输入框并提示等待下一个验证码。

### AES 加解密（`/aes`）

对配置值加密或解密，并**按服务器**保存密文，用来核对真实配置文件中应有的内容。布局遵循
`creed-resource-env-matrix/docs/design.png`：上方是密钥与取值，左下是服务器列表，右下是已保存的结果。

**算法** —— 与真实配置文件使用的规则一致；Java 服务（`AesCryptoService`）与 node mock（`server/aes.js`）
完全相同，由同一个测试向量锁定，`openssl kdf PBKDF2` + `openssl enc` 可复现：

```
Secret Key = randomkey + host + ip                          直接拼接，无分隔符
key        = PBKDF2WithHmacSHA256( UTF-8(Secret Key),
                                   UTF-8(salt), 65536, 256 ) 32 字节 -> AES-256
iv         = UTF-8(Initialization Vector)                   正好 16 字节
ciphertext = Base64( AES/CBC/PKCS5Padding(key, iv, UTF-8(明文)) )
```

- **Secret Key 不是输入项**，而是按服务器推导出来的，所以**同一个值在每台服务器上的密文都不同**。
  由于是直接拼接，(randomkey, host) 为 `("ab", "c…")` 与 `("a", "bc…")` 时得到同一个密钥。
- **IV 按 UTF-8 字节计数**，不是按字符：输入框显示 `n / 16`，一个汉字算 3 个字节。
- **salt 必填**（任意非空文本，按 UTF-8 字节使用）；缺失时返回 400 并指明 `salt`，与 IV 错误一样。
- **保存的内容：** 每条记录的密文和 randomkey。IV 和 salt 从不保存、不写日志、不放进 URL —— 解密时都需要重新
  输入。需要注意：Secret Key 完全由已保存或公开的数据（randomkey、host、ip）构成，所以**拿到数据库副本后，只差
  IV 和 salt 就能解密全部明文**。
  这是真实系统的规则，此处照搬是为了核对其配置文件。
- CBC 没有完整性校验：密钥错误几乎总会被识别（填充错误或结果不是 UTF-8），但极少数情况下会解出一小段乱码。

**「密钥与取值」是一个数组**，每行分两排：第一排是 **IV 和 salt**；第二排是 randomkey、只读的
**Secret Key（预览）**、属性键、明文和**预览**密文。可以添加、复制、删除行，也可以「以 JSON 编辑」：

```json
[
  { "iv": "0123456789abcdef", "salt": "s4lt", "randomKey": "r4nd0m",
    "propertyKey": "db.password", "plainValue": "db-p@ss", "encryptedValue": "" }
]
```

对话框可以替换或追加行；未知字段名会被拒绝而不是静默忽略，旧的 `secretKey` 字段会被拒绝并提示它现在由
系统推导。最多 200 行。

**操作流程。**
1. 在**服务器列表**中勾选服务器 —— 列出配置中所有不重复的 `host:ip`，可按应用系统过滤。
2. **预览服务器**（卡片右上角；默认是第一台勾选的服务器，也可以另选）是表单预览所用的服务器：Secret Key
   列显示它的 `randomkey + host + ip`，「全部加密」/「全部解密」也用它。IV 不可用或缺少 salt 的行会被标红并跳过，
   其他行照常处理。
3. 「保存到所选服务器」发送的是**明文**；后端用每台勾选服务器各自的 Secret Key 把每一行加密一次，在一个
   事务中保存。对同一台服务器再次保存同一个属性键会替换原值；两行属性键相同会被拒绝。已保存了表单中任一
   属性键的服务器会标记「已保存」。

**结果列表**在解密结果旁显示每条记录的 **Secret Key**（下方是它的 randomkey）—— 核对真实配置文件时
比对的正是这两项。「解密所选」使用每条记录自己的 Secret Key，以及表单中**属性键相同**那一行的 IV 和 salt
（表单只有一行时，所有记录都用这一行的）；逐行返回结果。修改 IV、salt 或 randomkey 会清空解密结果列。
「载入」把一条记录放进属性键相同的那一行（否则放进一个尚未填写取值的行并保留它的 IV 和 salt，再否则新增一行），
并把该记录的服务器设为预览服务器；「删除」删除记录。

按旧规则（`SHA-256(Secret Key + randomkey)`、Secret Key 由用户输入，以及不带 salt 的
`SHA-256(randomkey + host + ip)`）保存的记录，在新规则下无法解密。

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
| `GET` | `/splunk/totp` | TOTP 周期 / 位数 / 误差 + 服务端时间、Splunk 模式 —— **`/splunk/*` 由 BFF / mock 提供，不在 Java 服务中** |
| `GET` | `/splunk/totp/current` | 当前验证码（仅在开启 `expose-current-code` 时；否则 `404`） |
| `POST` | `/splunk/session` | `{code, target?}` → cookie 脚本（`target` 为登录目标 id，省略时用默认目标）；`400` 未知目标，`401` 验证码错误/已使用，`429` 已锁定，`502` Splunk 失败，`503` 未配置 |
| `GET` | `/splunk/audit` | 审计记录，按时间倒序（`?limit=`，默认 50） |
| `GET` | `/aes/servers` | 从 endpoint 中取不重复的 `(appSystem, host, ip)`；`?appSystem=` 过滤 |
| `POST` | `/aes/encrypt` | `{iv, randomKey, host, ip, plainValue}` → `{encryptedValue}`；Secret Key = randomKey + host + ip；IV 不是 16 字节时返回 `400` 并指出 `iv` |
| `POST` | `/aes/decrypt` | `{iv, randomKey, host, ip, encryptedValue}` → `{plainValue}`；`422 decrypt_failed` |
| `POST` | `/aes/encrypt/batch` | `{items: [{iv, randomKey, host, ip, value}]}` → 逐行返回 `{index, value \| error, field, message}` |
| `POST` | `/aes/decrypt/batch` | 同上，`value` 为密文；逐行返回，`no-store` |
| `GET` | `/aes/records` | 已保存的密文及推导出的 `secretKey`；`?appSystem=`、`?propertyKey=` |
| `POST` | `/aes/records` | `{propertyKey, plainValue, iv, randomKey?, note?, servers[]}` → 按服务器加密，存在则更新 |
| `POST` | `/aes/records/batch` | `{items: [{propertyKey, plainValue, iv, randomKey?, note?}], servers[]}` → 每项按每台服务器加密保存，一个事务；属性键重复或 IV 无效返回 `400` |
| `PUT` | `/aes/records/{id}` | 更新；与其他记录身份冲突时返回 `409 duplicate_aes_record` |
| `DELETE` | `/aes/records?ids=…` | 批量删除 → `204`；不存在的 id 忽略 |
| `POST` | `/aes/records/decrypt` | `{items: [{id, iv}]}`（Secret Key 取自记录）→ 逐行返回 `{id, plainValue \| error, message}` |

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
│       ├── Config/     # 增删改查编辑页（/config）
│       └── Aes/        # AES 加解密（/aes）
├── server/
│   ├── index.js        # Mock API —— 契约一致，零依赖
│   ├── aes.js          # mock 的 /aes/* 接口与加解密实现（测试向量见 aes.test.js）
│   ├── mock.json       # Mock 数据源，保存时会被回写
│   ├── bff.js          # 可部署的服务：dist/ + Splunk 会话代理 + /api 反向代理
│   └── splunk/         # Splunk 会话代理（TOTP、登录客户端、pg/内存审计），两者共用
└── vite.config.ts      # /api/env-matrix/splunk → VITE_SPLUNK_TARGET，/api → VITE_API_TARGET
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
