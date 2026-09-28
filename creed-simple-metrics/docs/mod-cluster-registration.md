# 向 Apache HTTP Server（mod_cluster）注册本节点

> 代码：`com.creed.simple.modcluster.*` · 配置：`application.yml` 的 `creed.mod-cluster.*`
> 关联：[[creed-simple-metrics]] 技能

本模块把自己作为一个 **node** 注册到 Apache HTTP Server 的 `mod_proxy_cluster` / `mod_manager`
（即 `mod_cluster_manager`）上，并在**启动阶段以一个 banner 明确打印注册成功与否**。

---

## 1. 用的是哪一套实现

依赖 `org.jboss.mod_cluster:mod_cluster-container-tomcat-10.1:2.1.0.Final`——**上游官方的 Tomcat 容器
集成**，也就是 Red Hat JBoss Web Server（JWS 6.x = Tomcat 10.1）放在 `$JWS_HOME/tomcat/lib` 里、用
`server.xml` 这样挂的同一个库：

```xml
<Listener className="org.jboss.modcluster.container.tomcat.ModClusterListener"
          advertise="true" stickySession="true"
          stickySessionForce="false" stickySessionRemove="true"/>
```

我们这边没有 `server.xml`，所以 `ModClusterListenerConfiguration` 用代码做了这个元素的三件事：
按配置造出 listener、把它挂到 **Server** 上、给 **Engine** 设 `jvmRoute`。

几个事实：

- 版本要和嵌入的 Tomcat 对齐（`10.1`）。它的 tomcat 依赖是 `provided`，不会和 `tomcat-embed-core` 撞车。
- 类名：`org.jboss.modcluster.container.tomcat.ModClusterListener`。老包名
  `...container.catalina.standalone.ModClusterListener` 在 2.x 里只是个 `@Deprecated` 空壳子类；
  1.3.x/1.4.x 那一支是 javax 命名空间，**在 Tomcat 10.1 上装不起来**。
- 许可：2.x 是 **Apache-2.0**（1.3.x 是 LGPL-3.0），Maven Central 公开下载。JWS 的订阅费买的是
  支持与认证组合，不是这个库的授权。
- **协议层完全由库负责**：MCMP 握手（`CONFIG` → `ENABLE-APP` → `STATUS`）、动态负载因子、context
  发现、代理遗忘节点后重注册、session draining、停机摘除。本模块只补它唯一不给的东西——
  **「到底注册上没有」的结论**（§5）。

---

## 2. httpd 侧最小配置

MCMP 走的是一个**专门开了 `EnableMCPMReceive` 的 VirtualHost**，节点把命令发到它的根 URI `/`：

```apache
LoadModule proxy_cluster_module   modules/mod_proxy_cluster.so
LoadModule manager_module         modules/mod_manager.so
LoadModule advertise_module       modules/mod_advertise.so

Listen 6666
<VirtualHost *:6666>
    ServerName proxy.example:6666
    EnableMCMPReceive                  # ← 没有这一行，CONFIG 会被拒（旧文档误拼为 EnableMCPMReceive）
    ManagerBalancerName mycluster      # ← 必须与 creed.mod-cluster.balancer.name 一致
    ServerAdvertise Off                # 默认用静态 proxies 列表，不依赖组播广告

    <Location /mod_cluster_manager>    # 人读的状态页，不是 MCMP 端点
        SetHandler mod_cluster-manager
        Require ip 127.0.0.1
    </Location>
</VirtualHost>
```

> **坑 1：MCMP 端点不是 `/mod_cluster_manager`。**
> 那是给人看的状态页 handler，对 `CONFIG`/`STATUS` 这些自定义 method 只会返回 404/405。节点固定把
> MCMP 发到开了 `EnableMCPMReceive` 的 VirtualHost 的 `/`。

> **坑 2：节点是 HTTPS 时，httpd 要信任 Creed CA。**
> mod_cluster 告诉代理「回连我用 https」（取自 Tomcat connector），代理侧还需要 `SSLProxyEngine On` +
> `SSLProxyCACertificateFile <Creed CA>`，否则**注册是成功的、转发才失败**——现象是 banner 全绿但 502。

### 2.1 HTTPS 注册（MCMP over mTLS）—— 现成的 httpd：`.support/httpd`

`docker compose -f .support/httpd/docker-compose.yml up -d --build` 起一个 Apache 2.4.68 +
mod_proxy_cluster（源码编译）：6666 = MCMP，**双向 TLS**（`SSLVerifyClient require`，只认 Creed CA）；
9443 = 业务入口。节点侧：

| 属性 | 值 |
|---|---|
| `manager-scheme` | `https`（现为默认值） |
| `ssl.key-store` | `${creed.rootPath}/creed-gateway-partner-CLI-keystore.p12`（出站身份，clientAuth） |
| `ssl.trust-store` | `${creed.rootPath}/creed-gateway-partner-CLI-truststore.p12`（校验 `creed-httpd`） |
| `node.host` | **`192.168.65.254`**（Docker Desktop 宿主机网关的 IP，不能用 `host.docker.internal`） |

三个只在 HTTPS 下出现的坑（完整说明见 `.support/httpd/README.md`）：

- **`ssl=true` 时库一定加载 keystore**，默认 `~/.keystore`/JKS，缺文件就在 Tomcat 生命周期里抛
  `IllegalStateException`，看上去像「mod_cluster 什么都没做」。所以两个库都显式设置，并先转成绝对路径
  （库会相对 `catalina.base` 解析，内嵌 Tomcat 下那是临时目录）；文件不可读时启动即打 ERROR。
- **`node.host` 在节点本机解析**（`TomcatConnector.getAddress`）。解析不了 → `null` →
  `DefaultMCMPRequestFactory.createConfigRequest` NPE：代理照常回应 `INFO`/`STATUS`，却一直
  `MEM: Can't read node with "..." JVMRoute`。现在启动时对无法解析的值打 ERROR。
- **httpd 的 `SSLProxyEngine` 必须在 server 级**：mod_proxy_cluster 在主 server 上建 worker，写在
  VirtualHost 里 → 节点 `Status: NOTOK`，请求全部 503（`AH01961: failed to enable ssl support`）。

---

## 3. 嵌入式 Tomcat 里怎么挂（四个必须知道的点）

### 3.1 必须挂在 `Server` 上，而且要赶在它 init 之前

`TomcatEventHandlerAdapter` 只处理 **source 是 `Server`** 的 `AFTER_INIT` / `START` / `AFTER_START` /
`BEFORE_STOP` / `STOP`。挂到 Context 或 Host 上 → **一个事件都收不到、什么都不注册、也不报错**。

Boot 在 `getWebServer(...)` 里的顺序是：建 Context → `host.addChild(context)` → 跑
`TomcatContextCustomizer` → 之后才 `tomcat.start()`。所以 context customizer 这个时机刚好：父链
（Context → Host → Engine → Service → Server）已经接上，而 Server 还没 init。

### 3.2 `JVMRoute` 来自 Engine

节点身份取 `Engine.getJvmRoute()`（取不到则由 `JvmRouteFactory` 生成一个 UUID）。所以同一个 customizer
里顺手 `engine.setJvmRoute(...)`，默认 `<spring.application.name>-<port>`。`server.port=0` 时端口在这个
时机还不知道，会退化成让 mod_cluster 自己生成，并打一条 WARN。

### 3.3 `STATUS` 的节奏是 Engine 的 backgroundProcess

心跳发在 Engine 的 `PERIODIC_EVENT` 上，周期 =
`backgroundProcessorDelay × org.jboss.modcluster.container.catalina.status-frequency`（系统属性，默认 1）。
所以 `creed.mod-cluster.status-interval` 被映射成 Engine 的 `backgroundProcessorDelay`。

### 3.4 `setProxyList(String)` 会在**建 bean 时**做 DNS 解析并抛异常

它内部对每个 host 做解析，解析不出来就 `IllegalArgumentException` —— 从 `@Bean` 方法里抛出去就是
**整个应用起不来**。这和本功能的约定冲突（`fail-fast` 默认关，代理不可达只该打 banner）。所以
`ModClusterListenerConfiguration#parseProxies` 自己构造 `InetSocketAddress`，解析不出来 / 格式不对的
条目**跳过并 ERROR 一行**，每个地址包成 `ProxyConfigurationImpl` 交给 `setProxyConfigurations(...)`
（`setProxies(Collection<InetSocketAddress>)` 自 1.3.1.Final 起已弃用，它做的也只是这一层包装）。

---

## 4. 配置映射（`creed.mod-cluster.*` → mod_cluster）

| 属性 | 落到哪里 | 备注 |
|---|---|---|
| `proxies` | `setProxyConfigurations(...)`（`ProxyConfigurationImpl`，无本地绑定地址） | 见 §3.4；scheme 会被剥掉。不用已弃用的 `setProxies` |
| `manager-scheme: https` | `setSsl(true)` | MCMP 自身走 TLS，与节点的 `Type` 无关；**默认 https** |
| `ssl.key-store` / `-password` / `-type` / `key-alias` | `setSslKeyStore*` / `setSslKeyAlias` | 仅 https 时设置；路径转绝对路径 |
| `ssl.trust-store` / `-password` | `setSslTrustStore*`（类型同 key-store） | 校验代理证书 |
| `ssl.protocol` | `setSslProtocol` | 默认 `TLS` |
| `socket-timeout` | `setSocketTimeout` | 单次 MCMP 往返 |
| `status-interval` | Engine `backgroundProcessorDelay` | 见 §3.3 |
| `advertise` / `advertise-interface` / `advertise-security-key` | 同名 setter | 组播发现，默认关 |
| `load-metric-class` | `setLoadMetricClass` | 空 = 默认 busy connectors |
| `excluded-contexts` | `setExcludedContexts` | 库会归一化成 `host:path`（少一个斜杠） |
| `session-draining-strategy` | `setSessionDrainingStrategy` | `DEFAULT`/`ALWAYS`/`NEVER` |
| `node.host` / `node.port` | `setExternalConnectorAddress/Port` | **只覆盖对外公布值**；选哪个 connector 是 `connectorAddress/Port`，我们不设 |
| `node.jvm-route` | Engine 的 `jvmRoute` | 见 §3.2 |
| `node.domain` | `setLoadBalancingGroup` | MCMP 里叫 `Domain` |
| `node.load` | `setInitialLoad` | 只是**初始**值，之后由 LoadMetric 算 |
| `node.flush-packets/flush-wait/ping/smax/ttl/timeout` | 同名 setter（`timeout`→`setNodeTimeout`） | `smax<0` 不发，`-1` 也正是 mod_cluster 自己的哨兵 |
| `balancer.name/sticky-session/-force/-remove/max-attempts` | 同名 setter | |
| `balancer.wait-worker` | `setWorkerTimeout` | MCMP 里叫 `WaitWorker` |

**没有对应属性的两类**（容器说了算，别去找）：

- **contexts**——注册的是 Tomcat **实际部署的 context**，也就是 `server.servlet.context-path`：
  现在是 **`/simple`**（2026-09-27 之前是 ROOT `/`）。`/camel/*` 只是 `CamelHttpTransportServlet` 的
  servlet mapping，**永远不会**被注册。要排除某个用 `excluded-contexts`。httpd 按注册的 context 路由：
  `/simple/*` 转发到节点，其余路径 httpd 自己回 404（表里还残留一个注册了 `/` 的旧节点时是 503）。
- **sticky session 的 cookie 名 / path**——mod_cluster 直接读 Tomcat 自己的 session cookie 配置，
  `balancer.*` 里只有策略开关。


### 4.1 `ModClusterListener` 每个属性是什么意思

来源：`mod_cluster-core` / `mod_cluster-container-tomcat-10.1` 2.1.0.Final 的源码（`ModClusterConfig`、
`ModClusterListener`、`DefaultMCMPRequestFactory`）与 httpd 侧 `mod_manager.c` 的默认值。「本项目」一列是
`creed.mod-cluster.*` 的默认值。

**先理解一条发送规则**：节点注册时发的 `CONFIG` 报文**只携带与 httpd 默认值不同的参数**
（`DefaultMCMPRequestFactory#createConfigRequest`）。数值型属性为 `-1` 表示「不发送，用 httpd 的默认」；
布尔型只在偏离 httpd 默认时才发（例如 Java 侧 `stickySessionForce` 默认是 `false`，会发
`StickySessionForce=No`；我们设为 `true`，就什么都不发，由 httpd 的默认「开」生效）。所以抓包看到参数少，
不代表没配置。

#### 节点身份与连接（node → proxy 的 MCMP 通道）

| 属性 | 本项目 | 库默认 | 含义 |
|---|---|---|---|
| `proxyConfigurations` | `127.0.0.1:6666` | 空 | 要注册到哪些 httpd（MCMP 监听地址）。旧的 `setProxies` 已弃用，见 §3.4 |
| `ssl` | `true`（`manager-scheme: https`） | `false` | MCMP 本身是否走 TLS。与节点的 `Type`（httpd 回连节点用 http 还是 https）无关 |
| `sslKeyStore` / `sslTrustStore` 等 | `creed-gateway-partner-CLI` 的 p12 | `~/.keystore`、JKS | MCMP TLS 的客户端证书与信任库，见 §2.1 |
| `socketTimeout` | 10s | 20000 ms | 单次 MCMP 往返（CONFIG / STATUS / INFO…）的超时 |
| `advertise` | `false` | `null`（没配代理列表时自动开） | 是否监听 httpd 的组播广告来**自动发现**代理，而不是用静态列表 |
| `advertiseGroupAddress` / `Port` / `Interface` / `SecurityKey` | — | `224.0.1.105:23364` | 组播地址、网卡、广告签名密钥（须与 httpd `AdvertiseSecurityKey` 一致） |
| `excludedContexts` | 空 | 空 | 永不注册的 context，`host:/path`，逗号分隔 |
| `autoEnableContexts` | `true` | `true` | 注册后 context 是否自动 `ENABLE`；`false` 则要在状态页/JMX 手动启用才接流量 |
| `jvmRoute`（Engine 上设置） | `<app>-<port>` | `jvmRoute` 系统属性，否则 UUID | **节点在集群里的唯一名字**，也是粘性会话的依据（见下） |
| `externalConnectorAddress` / `Port` | `node.host` = `192.168.65.254` | connector 的地址 | 告诉 httpd「回连我用这个地址/端口」。**在节点本机解析**，所以必须是 IP（§2.1） |
| `connectorAddress` / `Port` | 不设 | 自动挑 | 选**哪一个** Tomcat connector 来公布（多 connector 时），不是改公布的值 |

#### Balancer 与粘性会话（`balancer.*`）

这一组描述的是整个 balancer，而不是单个节点：**同一个 balancer 的所有节点应配置一致**，httpd 以第一个
注册的节点为准。

| 属性 → MCMP 参数 | 本项目 | 库默认 | httpd 默认 | 含义 |
|---|---|---|---|---|
| `balancer` → `Balancer` | `mycluster` | 不发 | `ManagerBalancerName`，否则 `mycluster` | 节点加入哪个 balancer；httpd 按名字把节点分组 |
| `stickySession` → `StickySession` | `true` | `true` | 开 | **粘性会话**：已有会话的请求总回到创建会话的那个节点 |
| `stickySessionForce` → `StickySessionForce` | `true` | `false` | 开 | 粘到的节点**不可用**时：`true` = 直接返回错误（503）；`false` = 转给别的节点（会话丢失） |
| `stickySessionRemove` → `StickySessionRemove` | `false` | `false` | 关 | 粘到的节点不可用而改投别处时，是否**删掉**请求里的会话 id，让新节点建新会话 |
| `workerTimeout` → `WaitWorker` | 0 | 不发 | 0 | 所有节点都忙时，等待空闲 worker 的秒数；0 = 不等，直接失败 |
| `maxAttempts` → `Maxattempts` | 1 | 不发 | 1 | 一个请求失败后最多尝试几个节点（故障转移次数） |
| 会话 cookie 名 / URL 参数名 | `JSESSIONID` / `jsessionid` | 取自 Tomcat | 同左 | 不是 listener 属性：取 Tomcat 的会话配置，不同才发 `StickySessionCookie/Path` |

**StickySession 是怎么工作的**：Tomcat 的 Engine 设置了 `jvmRoute` 后，生成的会话 id 形如
`JSESSIONID=<随机串>.<jvmRoute>`，例如 `abc123.creed-simple-metrics-8097`。httpd 读取 cookie（或 URL
里的 `;jsessionid=`）中 `.` 之后的部分，找到同名节点就直接发给它。这就是 `jvmRoute` 必须全集群唯一、
且必须与 Tomcat 实际的 `jvmRoute` 一致的原因。本模块是无状态 REST，自己不建会话；粘性只在请求带了会话
cookie 时才起作用（与 `payment-resource` 的 `stickyId` cookie 是两回事——那是本模块调用下游时
Spring Cloud LoadBalancer 的粘性）。

**实测**（2026-09-28，两个节点 8097 / 8098 注册在 `mycluster`，每种情况 6 次请求，数字取自 httpd 访问日志）：

| 请求 | 结果 | 说明 |
|---|---|---|
| 不带 cookie | 3 次 → 8097，3 次 → 8098 | 无会话，按负载均衡 |
| `Cookie: JSESSIONID=abc.creed-simple-metrics-8097` | 6 次全部 → 8097 | 粘性生效 |
| `Cookie: JSESSIONID=abc.creed-simple-metrics-8098` | 6 次全部 → 8098 | 粘性生效 |
| `…/health;jsessionid=abc.creed-simple-metrics-8098` | 3 次全部 → 8098 | URL 参数形式同样生效 |
| `Cookie: JSESSIONID=abc.no-such-node` | 3 / 3 均衡 | 路由名不存在：忽略，照常均衡 |
| 停掉 8098，继续带它的 cookie | **6 次全部 503** | `stickySessionForce=true`：8097 明明健康也不改投 |

最后一行就是 `stickySessionForce` 的取舍：`true` 保证「会话绝不悄悄换节点」，代价是粘住的节点挂掉时该会话
的请求全部失败，直到节点恢复，或它从表里被摘除（停机时的 `REMOVE-APP`，或 `ttl` 过期）。对无状态服务，
`false` 往往更合适：改投别的节点即可，配合 `stickySessionRemove=true` 让新节点重新建会话。

#### 节点连接参数（`node.*`，httpd → node 的连接池）

| 属性 → MCMP 参数 | 本项目 | httpd 默认 | 含义 |
|---|---|---|---|
| `loadBalancingGroup` → `Domain` | 不设 | 无 | 故障转移域：节点失败时优先转给**同一个域**里的节点（相当于 mod_jk 的 domain） |
| `flushPackets` → `flushpackets` | `true` | Off | httpd 是否把节点的响应**逐包立即刷给客户端**（流式/SSE 需要；否则会缓冲） |
| `flushWait` → `flushwait` | 10 ms | 10 ms | 开了 flush 时，刷出前等待更多数据的毫秒数 |
| `ping` → `ping` | 10 s | 10 s | httpd 检测到节点的连接是否可用时，等待节点回应的时间 |
| `smax` → `smax` | 不发 | 由 mod_proxy 计算 | httpd 到节点**保持的空闲连接软上限** |
| `ttl` → `ttl` | 60 s | 60 s | 超过 `smax` 的空闲连接多久后关闭 |
| `nodeTimeout` → `Timeout` | 0 | 0 | 等待节点返回数据的超时（秒）；0 = 不为该节点单独设置 |

#### 负载因子（Load）

httpd 按节点上报的 **Load（1..100，越大越空闲、分到越多请求）** 分配流量；`-1` 表示节点不可用，`0` 是待命。
节点每次 `STATUS` 上报一次，节奏见 §3.3。

| 属性 | 本项目 | 库默认 | 含义 |
|---|---|---|---|
| `loadMetricClass` | 不设 | `BusyConnectorsLoadMetric` | 用什么衡量「忙」：默认看 connector 的忙线程数 / 最大线程数 |
| `initialLoad` | 100 | 0 | 启动时预填的负载：100 = 一上来就按满额分流量；库默认 0 = 先少分，随实测慢慢爬升 |
| `loadDecayFactor` | 不设 | 2 | 历史样本的衰减系数：越大越看重最近的样本 |
| `loadHistory` | 不设 | 9 | 保留几个历史样本参与加权平均 |
| `loadMetricCapacity` | 不设 | 1 | 度量的「满载」值，用来把原始值换算成百分比 |

#### 停机（context 的下线过程）

| 属性 | 本项目 | 库默认 | 含义 |
|---|---|---|---|
| `sessionDrainingStrategy` | `DEFAULT` | `DEFAULT` | 停机时是否先等活跃会话结束：`DEFAULT` = context 不可分布（会话不复制）时才等；`ALWAYS` / `NEVER` |
| `stopContextTimeout` + `Unit` | 不设 | 10 秒 | 停 context 时等待处理中请求完成的最长时间，超时就强行 `STOP-APP` |

停机顺序（§6 实测）：`DISABLE-APP`（不再接新会话）→ 等待 → `STOP-APP` → `REMOVE-APP`。节点被 `kill -9`
或网络断开时这串命令发不出去，节点会在表里残留，直到 `ttl` 过期或 httpd 探测到它不可用。

---

## 5. 启动 banner：注册成功与否

库自己对成败**不给结论**（MCMP 流水是 DEBUG，被拒和不可达在日志里长得一样）。
`ModClusterListenerStatusReporter` 补上这一步：

1. `ApplicationReadyEvent` 后调 `ModClusterServiceMBean#getProxyInfo()`——**对每个代理发一次 MCMP
   `INFO`**，拿回它当前持有的节点清单；
2. `holdsNode()`：dump 里有没有 `Name: <JVMRoute>`——**这就是判定**；顺便数出
   `Status: ENABLED` 的 context 数量（决定流量到底进不进得来的那个数）；
3. 注册是异步的（发生在 Server 的 `AFTER_START`，开 advertise 时代理还可能晚几秒出现），所以做
   `startup-timeout`（默认 10s）的**有界轮询**，而不是拿半成品状态去打印；
4. **node 行取代理的答案，不取本地算的值**：对外地址是 mod_cluster 从 Tomcat connector 上挑的，不是
   `creed.mod-cluster.node.*`。实测本机就会不一致（本地算出 `192.168.5.9`，代理拿到 `127.0.0.1`）——
   打我们自己的猜测值会让 banner「看着对」而流量去了别处。

```
================== mod_cluster registration ==================
 node      : JVMRoute=creed-simple-metrics-8096  https://127.0.0.1:8096
 balancer  : mycluster  sticky=true (force=true, remove=false)
 contexts  : discovered by Tomcat  aliases=[localhost]
 manager   : http://<proxy>/  (MCMP, advertise=false)
 proxy 127.0.0.1:6666           REGISTERED
   detail  : Node: [1],Name: creed-simple-metrics-8096,...,Type: https,... | contexts enabled=1
 result    : 1/1 proxies registered => OK
==============================================================
```

**日志级别本身就是结论**：全部成功 = INFO，部分 = WARN，全部失败 = ERROR（便于只收 WARN 以上的日志
系统直接告警）。`creed.mod-cluster.enabled=false`（默认）时打印一行 INFO 说明「未启用」——静默会让人
分不清「没开」和「没这功能」。`fail-fast=true` 时，一个代理都没注册上就让启动失败（默认 false）。

---

## 6. 实测的完整生命周期

```
[stub] CONFIG / JVMRoute=creed-simple-metrics-8096&Balancer=mycluster&Host=127.0.0.1&Maxattempts=1&
                Port=8096&Timeout=0&Type=https&WaitWorker=0&flushpackets=On&flushwait=10&ping=10&ttl=60
[stub] ENABLE-APP / JVMRoute=...&Alias=localhost&Context=%2F     ← 当时是 ROOT；现在是 Context=%2Fsimple
[stub] STATUS / JVMRoute=...&Load=100                             ← 每 status-interval 一次
[stub] INFO /                                                     ← banner 的数据来源
[stub] DISABLE-APP → STOP-APP → REMOVE-APP → REMOVE-APP /*        ← 停机（含 session draining）
```

注意 `CONFIG` 是**最小集**：只发和 mod_cluster 默认值不同的参数（上面就没有 `StickySession*`，因为我们
的默认值与它一致）。抓包对比时别以为「少了就是没配」。

---

## 7. 本地验证

```bash
# 1. 起 httpd（HTTPS，见 §2.1）：
.support/httpd/issue-cert.sh && docker compose -f .support/httpd/docker-compose.yml up -d --build
# modcluster profile = enabled + proxies 127.0.0.1:6666 + https + node.host 192.168.65.254
mvn -pl creed-simple-metrics spring-boot:run -Dspring-boot.run.profiles=local,modcluster \
  -Dspring-boot.run.workingDirectory="$PWD"

# 2. 看启动 banner（§5）。

# 3. 从代理侧确认。浏览器：https://localhost:16666/mod_cluster_manager（不要客户端证书，仅本机；6665–6669 被浏览器当作不安全端口直接拒绝）。
#    命令行 INFO/DUMP 与输出示例：.support/httpd/README.md「查看已注册的实例」。
#    6666 是 mTLS，需要客户端证书：
P=.support/scripts/pki
curl -s --cacert $P/ca-chain.crt --cert $P/creed-gateway-partner-CLI.crt --key $P/creed-gateway-partner-CLI.key \
  https://127.0.0.1:6666/mod_cluster_manager | sed 's/<[^>]*>/ /g' | grep -E 'Node|Status'

# 4. 没有 httpd 时：tmp/mcmp-stub.py 是一个最小的假 mod_cluster_manager（应答 CONFIG/ENABLE-APP/
#    STATUS/INFO），足以把注册、心跳、停机摘除整条链路跑通并让 banner 变绿。
python3 tmp/mcmp-stub.py
```

单元测试（都不碰网络）：`ModClusterListenerConfigurationTest`（属性 → listener 映射、无法解析的代理被
跳过而不是炸上下文、`externalConnector*` vs `connector*`、`smax` 哨兵）、
`ModClusterListenerStatusReporterTest`（INFO → 结论、只持有别的节点 = 未注册、配置了但没应答的代理仍
要出现在 banner 上、advertise 发现的代理、INFO 查询抛异常不影响 banner、`fail-fast`）。

---

## 8. 注册不上时看什么

1. **banner 的 detail 行**：`no INFO response` = 代理压根没应答（端口/`EnableMCPMReceive`/防火墙）；
   `does not hold JVMRoute=...` = 代理活着但没有我们（多半 `ManagerBalancerName` 与 `balancer.name`
   不一致，或 `CONFIG` 被拒）。
2. **打开库自己的日志**：`logging.level.org.jboss.modcluster=DEBUG`，能看到每条 MCMP 报文与代理的回应。
3. **JMX**：`ModClusterListener` MBean 上有 `getProxyInfo()` / `getProxyConfiguration()` / `ping()` /
   `refresh()` / `reset()`，运行期排障比重启快。
4. **contexts enabled=0**：节点注册上了但 context 没 enable → 流量不会进来；检查 `excluded-contexts`
   与请求路径是不是以注册的 context（`/simple`）开头。
