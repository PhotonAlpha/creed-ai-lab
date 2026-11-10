# camel-http：按接口隔离连接池

> 2026-11-11。相关代码：`config/CamelHttpComponents.java`（统一工厂）、`config/NamedHttpComponentRegistrar.java`
> （按配置注册）、`config/CamelConfig.java`（默认 `http` / `https` 组件）。测试：`CamelHttpComponentsTest`、
> `NamedHttpComponentRegistrarTest`。源码依据：camel-http **4.23.0**、httpclient5 **5.6.4**。

## 1. 问题

所有 camel-http 端点（`<to uri="https://catalog-resource/...">`）共用一个组件 `http` / `https`，也就共用一个
连接池 `camelHttpPool`。同一个集群的慢接口（批量导出、报表）和快接口（实时查询）抢同一批连接：慢接口把池占满时，
实时接口在 `connection-request-timeout` 上排队失败（[connection-pool-tuning.md](connection-pool-tuning.md) §1 的现象）。

目标：**同一个集群的不同接口可以用不同的连接池**，例如 `catalog-resource` 的 `/bulk` 和 `/items` 分开。

## 2. 前提：camel-http 的连接池挂在哪里

| 事实 | 源码位置（camel-http 4.23.0） |
|---|---|
| 组件只负责创建端点；端点在**第一次调用**时才用自己的连接池建 HttpClient | `HttpEndpoint.getHttpClient()` / `createHttpClient()` |
| 端点的连接池默认取组件的 `clientConnectionManager` | `HttpComponent.createConnectionManager()` |
| 只有**组件的那个池**才被当作共享池（`setConnectionManagerShared(true)`） | `HttpEndpoint.createHttpClient()`：`getComponent().getClientConnectionManager() == getClientConnectionManager()` |
| 端点停止时，**不是组件的池就关掉** | `HttpEndpoint.doStop()`：`!= clientConnectionManager` → `close()` |
| 组件停止时关掉组件的池 | `HttpComponent.doStop()` |
| 是否走 TLS 由 `uri.startsWith("https")` 决定 | `HttpHelper.isSecureConnection`（camel-http-base） |
| URI 剩余部分写明的协议优先 | `HttpComponent.createEndpoint()`：`remaining.startsWith("http:") \|\| remaining.startsWith("https:")` |

最后两条决定了组件命名的写法（§5）；中间两条决定了「换池」的做法有没有坑（§3）。

## 3. 三种做法对比

### 3.1 同一个 `https` 组件，按 URI 里的 service-id 选池

覆写 `HttpComponent.createEndpoint`，按 `endpoint.getHttpUri().getHost()`（即 service-id）把端点的池换掉。

- 优点：URI 一处不改；池跟着 service-id 走，组件名和主机名不可能写错；新增集群只加配置。
- 缺点：
  - 只能**按集群**隔离，同一集群的批量接口和实时接口仍然共用一个池——**不满足本需求**。
  - 换上去的池不是组件的池，§2 第 3、4 行生效：任何一个端点停止（路由移除、`toD` 端点被缓存淘汰）都会把兄弟端点
    还在用的池关掉。必须包一层 `close()` 为空的委托，并在 configurer 里 `setConnectionManagerShared(true)`。
  - 依赖 `HttpEndpoint` 关池的内部逻辑，升级 Camel 时要回归。

### 3.2 端点参数 `clientConnectionManager=#bean`

- 优点：不写代码。
- 缺点：每个 URI 都要改；§2 的关池问题同 3.1，而且没有地方加包装。

### 3.3 每个隔离单元一个组件 bean（**采用**）

每个隔离单元（如 `catalog-bulk`）注册一个独立的 `HttpComponent` bean，自带连接池；路由用
`catalog-bulk:https://catalog-resource/api/catalog/bulk` 选择它。

- 优点：
  - **按接口**隔离：组件选择写在每个 `<to>` 上，同一集群的不同接口可以各用各的池。
  - 完全按 Camel 的设计使用：池挂在组件上，端点自动视为共享，端点停止不会关池，只有组件停止才关
    （与现有 `camelHttpConnectionManager` 的 `destroyMethod=""` 一致）。不需要子类改池、不需要包装类。
  - 路由里一眼看出走哪个池。
- 缺点（及处理）：

| 缺点 | 处理 |
|---|---|
| 组件名写成 `catalog-bulk://...` 会因 `startsWith("https")` 失败而**悄悄走明文 HTTP** | 具名组件拒绝不带协议的 URI，启动 / 首次解析时直接报错（§5） |
| 组件级配置（route planner、Logbook、observation、lbAudit、超时）每个组件都要一份，容易漏 | 统一由 `CamelHttpComponents` 工厂生成，默认组件也用它 |
| 每加一个隔离单元要写 bean | `NamedHttpComponentRegistrar` 按 `creed.camel.http-components.<name>` 自动注册 |
| 组件名和 service-id 可能对不上（`catalog-bulk:https://order-resource/...`） | 不拦：组件代表「一类调用」而不是集群，允许多个集群共用一个批量池；靠代码评审 |
| 用了新组件的 URI 要改 | 只改需要隔离的那几个接口，其余仍是 `https://...` |

### 3.4 小结

| | 3.1 按 service-id 选池 | 3.2 端点参数 | 3.3 多个组件 bean |
|---|---|---|---|
| 隔离粒度 | 集群 | 接口 | 接口 |
| 改 URI | 不改 | 每个都改 | 只改要隔离的 |
| 端点停止关池的坑 | 有，需包装 | 有，无处包装 | 无 |
| 依赖 Camel 内部行为 | 是 | 是 | 否（只用公开扩展点） |
| 组件名 / 主机名写错 | 不可能 | 可能 | 可能 |
| TLS 判断的坑 | 无 | 无 | 有，已加校验 |
| 新增 | 只加配置 | 改 URI | 只加配置 + 改要隔离的 URI |

只需要按集群隔离时 3.1 更省事；本需求是按接口隔离，所以选 3.3。两者也可以组合（默认按集群，少数接口再用具名组件），
目前没有这个需要，没有实现 3.1。

## 4. 社区做法

- **同一组件注册多个实例、用不同名字区分**是 Camel 的常规做法：最常见的例子是两个 JMS / ActiveMQ 组件
  `activemq` 和 `activemq2` 各连一个 broker，路由用 `activemq2:queue:...` 选择；《Apache Camel Developer's
  Cookbook》也把「同一组件以不同 id 实例化多次」列为标准用法。Camel 在 registry 里按 bean 名查找组件，这也是本模块
  `@Bean(name = {"http", "https"})` 能生效的原因。
- **`<自定义名>:https://...` 这种写法 camel-http 官方文档没有写**，是 `HttpComponent.createEndpoint` 源码里的
  行为（URI 剩余部分写明的协议优先）。因此用测试锁住：`CamelHttpComponentsTest.anEndpointRidesItsComponentsPoolOverHttps`
  断言 `catalog-bulk:https://...` 解析出 `https` 的 `httpUri`；升级 Camel 时这条测试会先报。
- Spring 侧：bean 名允许 `-`（`catalog-bulk`），Camel 组件名里带 `-` 也很常见（`aws2-s3`、`spring-rabbitmq`）。
  注意 bean 名**不是** `catalog:https`——`:https://...` 是 URI 的一部分，组件（bean）名只是冒号前那段。
- camel-spring-boot 的 `camel.component.<name>.*` 配置只作用于它自动配置的组件；自己注册的具名组件不读这些键，
  配置走本模块的 `creed.camel.http-components.<name>.*`。

## 5. 命名规则与 TLS 校验

- 组件名 = URI scheme：小写字母开头，只含小写字母、数字、`+ - .`；不能是 `http` / `https`（那是默认组件），不能和已有
  bean 重名。违反时启动失败，消息指出是哪个配置键。
- 端点必须写协议：`catalog-bulk:https://catalog-resource/...`（或 `catalog-bulk:http://...`）。
  写成 `catalog-bulk://catalog-resource/...` 时，`ExplicitProtocolHttpComponent` 在创建端点时抛
  `IllegalArgumentException`（Camel 包成 `ResolveEndpointFailedException`），而不是让它以明文 HTTP 发给 mTLS 下游。

## 6. 配置与用法

```yaml
creed:
  camel:
    http:                       # 默认组件 http / https 的池，也是具名组件的兜底
      max-total: 50
      max-per-route: 20
      response-timeout: 10s
    http-components:
      catalog-bulk:             # 组件名；只写和默认池不同的键
        max-total: 10
        max-per-route: 5
        response-timeout: 60s
```

```xml
<!-- 批量：独立的小池 + 长超时 -->
<to uri="catalog-bulk:https://catalog-resource/api/catalog/bulk?skipRequestHeaders=true&amp;skipResponseHeaders=true"/>
<!-- 实时：仍走默认池 -->
<to uri="https://catalog-resource/api/catalog/items?skipRequestHeaders=true&amp;skipResponseHeaders=true"/>
```

- 键与 `HttpPoolProperties` 相同（池大小、四个超时、TTL、validate、max-idle、eviction-interval）。
  **未写的键继承 `creed.camel.http.*`**，再没有才用 record 默认值（`CamelHttpComponents.named`）。
- route planner（Spring Cloud LoadBalancer）、粘性路由、Logbook、`http.client.request` 观测、lbAudit 与默认组件完全相同。
- `ProducerTemplate` 同样适用：`producerTemplate.send("catalog-bulk:https://catalog-resource/...", ...)`。

## 7. 每个具名组件注册的 bean

以 `catalog-bulk` 为例：

| bean | 作用 | 生命周期 |
|---|---|---|
| `catalog-bulk` | `HttpComponent`（`ExplicitProtocolHttpComponent`），由工厂生成 | Camel 管 |
| `catalog-bulkConnectionManager` | 该组件的 mTLS 连接池（同一个 `creed.partner.client-bundle`） | `destroyMethod=""`，由组件 `doStop()` 关闭 |
| `catalog-bulkPoolEvictor` | 空闲 / 过期连接清理（`setConnectionManagerShared(true)` 时 HttpClient 不会自己清理） | `start` / `shutdown` |
| `catalog-bulkPoolMetrics` | 连接池指标，名字 `camelHttpPool-catalog-bulk` | Boot 自动绑定 |

注册方式与 creed-gateway-partner 的 `ClusterClientBeanRegistrar` 相同：`static @Bean` 的
`BeanDefinitionRegistryPostProcessor`，配置直接从 `Environment` 用 `Binder` 读，协作 bean 在实例化时从
`BeanFactory` 取。

## 8. 验证

- `CamelHttpComponentsTest`（真实 `DefaultCamelContext`）：
  - `catalog-bulk:https://...` 用 `catalog-bulk` 的池且是 https，`https://...` 用默认池；
  - `catalog-bulk://...` 被拒绝，`catalog-bulk:http://...` 可用；
  - 两个端点共用 `catalog-bulk` 的池，停掉一个后池仍可租借，Camel 停止后池关闭；
  - 配置继承：具名块 > `creed.camel.http` > record 默认值；
  - 非法名字（大写、下划线、数字开头、`http`、`https`）被拒绝。
- `NamedHttpComponentRegistrarTest`（`ApplicationContextRunner` + `SpringCamelContext`）：每个名字注册齐四个 bean、
  evictor 已启动；Camel 通过 Spring registry 按 bean 名解析到这个组件和它的池；不配置时什么都不注册。

- 整个应用实际运行（2026-11-11）：catalog、order 用 `primary` 启动，simple-metrics 通过启动参数声明 `catalog-bulk`
  （`max-total=4`、`max-per-route=2`、`response-timeout=60s`），临时把 `fetch-catalog` 改成 `catalog-bulk:https://...`
  （验证后已改回）：
  - `GET /simple/camel/api/catalog` 三次都是 200；lbAudit 日志 `pool[route 1/2 …; total 1/4 …]`，正是 `catalog-bulk` 的上限；
  - Prometheus：`httpcomponents_httpclient_pool_total_max{httpclient="camelHttpPool-catalog-bulk"} 4`，它有 1 条连接，
    默认 `camelHttpPool` 为 0；`http_client_request_seconds_count{net_peer_name="catalog-resource"}` 照常计数；
  - `POST /fulfillment` 的批量拉取日志来自 `LoadBalancerAuditInterceptor`（RestClient），两个 camel-http 池都没有新连接；
  - SIGTERM 关闭时两个池各由自己的组件关闭（两行 `Shutting down ClientConnectionManager`），没有异常。

## 9. 未做的事

- 现有路由**一个都没改**：`camel-context.xml` 里的 camel-http 调用都是实时 / 事务调用；批量拉取
  （`fetch-catalog-bulk` / `fetch-order-bulk`）走的是 `RemoteClusterProcessor` 的 `@LoadBalanced` RestClient，
  不经过 camel-http。要隔离哪个接口，配置一个名字并把那个 `<to>` 改成 `<name>:https://...` 即可。
- 没有按 service-id 自动选池（3.1）。
