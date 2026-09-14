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
    EnableMCPMReceive                  # ← 没有这一行，CONFIG 会被拒
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
条目**跳过并 ERROR 一行**，再交给 `setProxies(...)`。

---

## 4. 配置映射（`creed.mod-cluster.*` → mod_cluster）

| 属性 | 落到哪里 | 备注 |
|---|---|---|
| `proxies` | `setProxies(...)` | 见 §3.4；scheme 会被剥掉 |
| `manager-scheme: https` | `setSsl(true)` | MCMP 自身走 TLS，与节点的 `Type` 无关 |
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

- **contexts**——注册的是 Tomcat **实际部署的 context**，也就是**ROOT `/`，不是 `/camel`**
  （`/camel/*` 只是 `CamelHttpTransportServlet` 的 servlet mapping）。要排除某个用
  `excluded-contexts`。httpd 侧的路由要按 ROOT 来配。
- **sticky session 的 cookie 名 / path**——mod_cluster 直接读 Tomcat 自己的 session cookie 配置，
  `balancer.*` 里只有策略开关。

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
[stub] ENABLE-APP / JVMRoute=...&Alias=localhost&Context=%2F     ← ROOT context，不是 /camel
[stub] STATUS / JVMRoute=...&Load=100                             ← 每 status-interval 一次
[stub] INFO /                                                     ← banner 的数据来源
[stub] DISABLE-APP → STOP-APP → REMOVE-APP → REMOVE-APP /*        ← 停机（含 session draining）
```

注意 `CONFIG` 是**最小集**：只发和 mod_cluster 默认值不同的参数（上面就没有 `StickySession*`，因为我们
的默认值与它一致）。抓包对比时别以为「少了就是没配」。

---

## 7. 本地验证

```bash
# 1. 起 httpd（含 §2 的 VirtualHost），然后：
CREED_MODCLUSTER_ENABLED=true CREED_MODCLUSTER_PROXIES=127.0.0.1:6666 \
  mvn -pl creed-simple-metrics spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

# 2. 看启动 banner（§5）。

# 3. 从代理侧确认（人读状态页）：
curl -s http://127.0.0.1:6666/mod_cluster_manager | grep -A3 'Node creed-simple-metrics'

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
   与 httpd 侧路由是不是按 ROOT `/` 配的。
