# camel-http：按接口隔离连接池

> 2026-11-11。相关代码：`config/CamelHttpComponents.java`（统一工厂）、`config/NamedHttpComponentRegistrar.java`
> （按配置注册，源码说明见 §10）、`config/CamelConfig.java`（默认 `http` / `https` 组件）。测试：`CamelHttpComponentsTest`、
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

每个隔离单元（如 `catalogbulk`）注册一个独立的 `HttpComponent` bean，自带连接池；路由用
`catalogbulk:https://catalog-resource/api/catalog/bulk` 选择它。

- 优点：
  - **按接口**隔离：组件选择写在每个 `<to>` 上，同一集群的不同接口可以各用各的池。
  - 完全按 Camel 的设计使用：池挂在组件上，端点自动视为共享，端点停止不会关池，只有组件停止才关
    （与现有 `camelHttpConnectionManager` 的 `destroyMethod=""` 一致）。不需要子类改池、不需要包装类。
  - 路由里一眼看出走哪个池。
- 缺点（及处理）：

| 缺点 | 处理 |
|---|---|
| 组件名写成 `catalogbulk://...` 会因 `startsWith("https")` 失败而**悄悄走明文 HTTP** | 具名组件拒绝不带协议的 URI，启动 / 首次解析时直接报错（§5） |
| 组件级配置（route planner、Logbook、observation、lbAudit、超时）每个组件都要一份，容易漏 | 统一由 `CamelHttpComponents` 工厂生成，默认组件也用它 |
| 每加一个隔离单元要写 bean | `NamedHttpComponentRegistrar` 按 `creed.camel.http-components.<name>` 自动注册 |
| 组件名和 service-id 可能对不上（`catalogbulk:https://order-resource/...`） | 不拦：组件代表「一类调用」而不是集群，允许多个集群共用一个批量池；靠代码评审 |
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
  断言 `catalogbulk:https://...` 解析出 `https` 的 `httpUri`；升级 Camel 时这条测试会先报。
- 组件名只是冒号前那段：`catalogbulk:https://...` 的 bean 名是 `catalogbulk`，**不是** `catalogbulk:https`。
  Camel 取 URI 第一个 `:` 或 `?` 之前的部分作为 scheme，再按这个名字查 bean（`AbstractCamelContext.doGetEndpoint`），
  所以名字里带 `:` 的 bean 永远查不到。
- Camel 自己的组件名多用 `-`（`aws2-s3`、`spring-rabbitmq`），但同一组件的第二个实例常见写法是连写的小写
  （`activemq2`、`jms2`）。本模块取后者，原因见 §5。
- camel-spring-boot 的 `camel.component.<name>.*` 配置只作用于它自动配置的组件；自己注册的具名组件不读这些键，
  配置走本模块的 `creed.camel.http-components.<name>.*`。

## 5. 命名规则与 TLS 校验

- 组件名：**小写字母开头，只含小写字母和数字**（`[a-z][a-z0-9]*`，如 `catalogbulk`）；不能是 `http` / `https`
  （那是默认组件），不能和已有 bean 重名。违反时启动失败，消息指出是哪个配置键。
  同一个名字同时是 URI scheme、bean 名、配置键，还是派生 bean 名（`catalogbulkConnectionManager`）和指标标签
  （`camelHttpPool-catalogbulk`）的一部分，这个规则是几方要求的交集：

  | 写法 | 问题 |
  |---|---|
  | `catalog-bulk` | Camel、Spring 都能用，但 Sonar 的 bean 命名规则（`^[a-z][a-zA-Z0-9]*$`）不通过 |
  | `catalogBulk` | Sonar 通过，但 `named()` 要绑定 `creed.camel.http-components.catalogBulk`，Boot 只接受小写规范名，启动时抛 `InvalidConfigurationPropertyNameException`（Boot 4.1.1 实测）；环境变量覆盖也会丢掉大小写；URI scheme 的规范写法也是小写 |
  | `catalog.bulk` | 根本写不出来：Boot 把它读成名字 `catalog` 加一个嵌套键 `bulk` |
  | `catalog+bulk` | Boot 绑定 Map 键时会去掉 `+`，bean 名变成 `catalogbulk`，路由里写的 `catalog+bulk:` 就找不到组件 |
  | `catalogbulk` | 全部通过 |

  `CamelHttpComponentsTest.aComponentNameIsLowerCaseLettersAndDigitsOtherThanHttp` / `aCamelCaseNameCouldNotBeBoundAnyway`
  锁住这条规则；RestClient 一侧（`creed.partner.rest-clients.<name>`）用同一条规则。
- 端点必须写协议：`catalogbulk:https://catalog-resource/...`（或 `catalogbulk:http://...`）。
  写成 `catalogbulk://catalog-resource/...` 时，`ExplicitProtocolHttpComponent` 在创建端点时抛
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
      catalogbulk:              # 组件名；只写和默认池不同的键
        max-total: 10
        max-per-route: 5
        response-timeout: 60s
```

```xml
<!-- 批量：独立的小池 + 长超时 -->
<to uri="catalogbulk:https://catalog-resource/api/catalog/bulk?skipRequestHeaders=true&amp;skipResponseHeaders=true"/>
<!-- 实时：仍走默认池 -->
<to uri="https://catalog-resource/api/catalog/items?skipRequestHeaders=true&amp;skipResponseHeaders=true"/>
```

- 键与 `HttpPoolProperties` 相同（池大小、四个超时、TTL、validate、max-idle、eviction-interval）。
  **未写的键继承 `creed.camel.http.*`**，再没有才用 record 默认值（`CamelHttpComponents.named`）。
- route planner（Spring Cloud LoadBalancer）、粘性路由、Logbook、`http.client.request` 观测、lbAudit 与默认组件完全相同。
- `ProducerTemplate` 同样适用：`producerTemplate.send("catalogbulk:https://catalog-resource/...", ...)`。

## 7. 每个具名组件注册的 bean

以 `catalogbulk` 为例：

| bean | 作用 | 生命周期 |
|---|---|---|
| `catalogbulk` | `HttpComponent`（`ExplicitProtocolHttpComponent`），由工厂生成 | Camel 管 |
| `catalogbulkConnectionManager` | 该组件的 mTLS 连接池（同一个 `creed.partner.client-bundle`） | `destroyMethod=""`，由组件 `doStop()` 关闭 |
| `catalogbulkPoolEvictor` | 空闲 / 过期连接清理（`setConnectionManagerShared(true)` 时 HttpClient 不会自己清理） | `start` / `shutdown` |
| `catalogbulkPoolMetrics` | 连接池指标，名字 `camelHttpPool-catalogbulk`（`PoolMetricNames` 按规则生成） | Boot 自动绑定 |

注册方式与 creed-gateway-partner 的 `ClusterClientBeanRegistrar` 相同：`static @Bean` 的
`BeanDefinitionRegistryPostProcessor`，配置直接从 `Environment` 用 `Binder` 读，协作 bean 在实例化时从
`BeanFactory` 取。

## 8. 验证

- `CamelHttpComponentsTest`（真实 `DefaultCamelContext`）：
  - `catalogbulk:https://...` 用 `catalogbulk` 的池且是 https，`https://...` 用默认池；
  - `catalogbulk://...` 被拒绝，`catalogbulk:http://...` 可用；
  - 两个端点共用 `catalogbulk` 的池，停掉一个后池仍可租借，Camel 停止后池关闭；
  - 配置继承：具名块 > `creed.camel.http` > record 默认值；
  - 非法名字（大写、下划线、数字开头、`http`、`https`）被拒绝。
- `NamedHttpComponentRegistrarTest`（`ApplicationContextRunner` + `SpringCamelContext`）：每个名字注册齐四个 bean、
  evictor 已启动；Camel 通过 Spring registry 按 bean 名解析到这个组件和它的池；不配置时什么都不注册。

- 整个应用实际运行（2026-11-11）：catalog、order 用 `primary` 启动，simple-metrics 通过启动参数声明 `catalogbulk`
  （`max-total=4`、`max-per-route=2`、`response-timeout=60s`），临时把 `fetch-catalog` 改成 `catalogbulk:https://...`
  （验证后已改回）：
  - `GET /simple/camel/api/catalog` 三次都是 200；lbAudit 日志 `pool[route 1/2 …; total 1/4 …]`，正是 `catalogbulk` 的上限；
  - Prometheus：`httpcomponents_httpclient_pool_total_max{httpclient="camelHttpPool-catalogbulk"} 4`，它有 1 条连接，
    默认 `camelHttpPool` 为 0；`http_client_request_seconds_count{net_peer_name="catalog-resource"}` 照常计数；
  - `POST /fulfillment` 的批量拉取日志来自 `LoadBalancerAuditInterceptor`（RestClient），两个 camel-http 池都没有新连接；
  - SIGTERM 关闭时两个池各由自己的组件关闭（两行 `Shutting down ClientConnectionManager`），没有异常。

## 9. 未做的事

- 现有路由**一个都没改**：`camel-context.xml` 里的 camel-http 调用都是实时 / 事务调用；批量拉取
  （`fetch-catalog-bulk` / `fetch-order-bulk`）走的是 `RemoteClusterProcessor` 的 `@LoadBalanced` RestClient，
  不经过 camel-http。要隔离哪个接口，配置一个名字并把那个 `<to>` 改成 `<name>:https://...` 即可。
- 没有按 service-id 自动选池（3.1）。

## 10. 源码说明：`NamedHttpComponentRegistrar`

文件：`src/main/java/com/creed/simple/config/NamedHttpComponentRegistrar.java`（约 120 行），由
`CamelConfig.namedHttpComponentRegistrar()` 注册。和它配套的是 `CamelHttpComponents`（名字校验、属性绑定、组件工厂）。
RestClient 那一侧的同构实现是 `lb/NamedRestClientRegistrar`，见 `docs/lb-rest-client-named-pools.md`。

### 10.1 为什么是 `BeanDefinitionRegistryPostProcessor`

bean 的个数和名字取决于配置（`creed.camel.http-components` 下有几个键就注册几组），没法写成固定的 `@Bean` 方法。
`BeanDefinitionRegistryPostProcessor` 在**所有 bean 实例化之前**运行，能往 registry 里加 bean 定义。之后这些 bean
和手写的 `@Bean` 没有区别：能被注入、被 Camel 的 Spring registry 按名字查到、被 Boot 的 `MeterBinder` 收集。

两个由此带来的约束：

- **`static @Bean`**（`CamelConfig` 里）。post-processor 必须很早实例化；如果是非静态方法，Spring 得先创建
  `CamelConfig` 实例，它的 `@Value` / 注入还没就绪，启动日志会出现 "is not eligible for getting processed by all
  BeanPostProcessors"。
- **不能注入任何 bean。** 它运行时其他 bean 都还没创建，所以只实现 `EnvironmentAware`（读配置）和
  `BeanFactoryAware`（之后取 bean），协作对象一律放进 supplier，**到 bean 被实例化时才取**（10.3）。

### 10.2 `postProcessBeanDefinitionRegistry`：读名字、校验、注册

```java
for (String name : CamelHttpComponents.names(environment)) {        // ① 名字
    if (registry.containsBeanDefinition(name)) throw …;               // ② 防重名
    register(registry, name);                                         // ③ 注册四个 bean
}
```

1. `CamelHttpComponents.names` 用 `Binder` 把 `creed.camel.http-components` 绑成 `Map<String, Object>`，只取键。
   此时 `@ConfigurationProperties` 还没生效，只能直接读 `Environment`。每个名字经 `requireValidName` 校验：
   必须是 `[a-z][a-z0-9]*`（原因见 §5），不能是 `http` / `https`；不合法就启动失败，消息里带配置键。
2. 名字就是组件的 bean 名（Camel 按 scheme 找组件 = 按 bean 名找），所以撞上已有 bean 直接失败，而不是悄悄覆盖。
3. `postProcessBeanFactory` 什么也不做，所有注册都在上一步完成。

### 10.3 `register`：四个 bean 和它们的依赖

```java
HttpPoolProperties props = CamelHttpComponents.named(environment, name);   // 具名块 > creed.camel.http > 默认值
String pool = name + "ConnectionManager";

define(pool,                 PoolingHttpClientConnectionManager, () -> connectionManagerFrom(bundle, props), null, "");
define(name + "PoolEvictor", IdleConnectionEvictor,             () -> evictorFor(bean(pool), props), "start", "shutdown");
define(name + "PoolMetrics", MeterBinder,                       () -> new ConnectionPoolMetrics(bean(pool),
                                                                       PoolMetricNames.of(CAMEL_HTTP, name)), null, null);
define(name,                 HttpComponent,                     () -> createNamed(name, bean(pool), props, shared), null, null);
```

- **属性在注册时就绑好**（`props` 是普通值），一个键写错，启动时就失败，而不是等到第一次调用。
  `named()` 现在调用 `HttpPoolProperties.layered(env, 具名前缀, 默认前缀)`：两组 `ConfigurationPropertySource`
  按顺序叠在一起，先找到的键生效，所以具名块只需写和默认池不同的键。
- **协作 bean 在 supplier 里取**（`beanFactory.getBean(SslBundles.class)`、`LoadBalancerRoutePlanner`、
  `ObservationRegistry`、`Logbook`），因为注册时它们还不存在。bean 之间用名字串起来（`bean(pool)`），
  Spring 按依赖顺序创建：组件、evictor、指标都会先把同名的池建出来。
- **连接池 `destroyMethod=""`**：池交给组件后，`HttpComponent.doStop()` 会关闭它。如果 Spring 也去关，
  销毁顺序不定，可能先关掉还在用的池。空字符串的意思是"不要推断 close 方法"。默认组件的池也是这样。
- **evictor `start` / `shutdown`**：所有池都 `setConnectionManagerShared(true)`，HttpClient 自带的空闲清理
  对共享池无效，所以单独起一个 `IdleConnectionEvictor`。池先被组件关掉也没关系，清理一个已关闭的池什么也不做。
- **指标名不是手写的**：`PoolMetricNames.of(CAMEL_HTTP, name)` = `camelHttpPool-<name>`，默认池是
  `PoolMetricNames.of(CAMEL_HTTP)` = `camelHttpPool`。所有池（camel、负载均衡 RestClient、健康检查）的命名规则
  都在 `PoolMetricNames` 一处。
- **`Shared`**：route planner、observation、Logbook、`creed.audit.pool-stats.*` 这些是所有组件共用的设置，
  打包传给 `CamelHttpComponents.createNamed`，它和默认组件走同一个 `configure()`，所以每个具名组件都有完全相同的
  exec chain（Logbook → observation → lbAudit）、route planner 和超时。
  pool-stats 两个值用 `Binder` 而不是 `Environment.getProperty` 读，因为 `"1s"` 要靠 Boot 的转换才能变成 `Duration`。

### 10.4 `define`：没有反射的 bean 定义

`BeanDefinitionBuilder.genericBeanDefinition(type, supplier)` 用 supplier 创建实例，不走构造器反射，
类型信息也是准的（`getBeanNamesForType(MeterBinder.class)` 能找到 `…PoolMetrics`）。`initMethod` / `destroyMethod`
直接写在定义上，效果等同于 `@Bean(initMethod=…, destroyMethod=…)`。

### 10.5 `createNamed` 与 `ExplicitProtocolHttpComponent`

具名组件是 `HttpComponent` 的子类，只重写 `createEndpoint`：URI 剩余部分必须以 `https:` / `http:` 开头。
camel-http 判断走不走 TLS，只看 `uri.startsWith("https")`（`HttpHelper.isSecureConnection`），所以
`catalogbulk://catalog-resource/...` 会以明文发给 mTLS 下游。这里在创建端点时直接拒绝。

### 10.6 加一个具名组件时会发生什么

1. 在 yml 里加 `creed.camel.http-components.<name>` 块，只写不同的键。
2. 启动：名字校验、属性绑定、四个 bean 注册。日志里能看到池和 evictor 创建；Prometheus 出现
   `httpclient="camelHttpPool-<name>"` 的序列。
3. 把要隔离的 `<to>` 改成 `<name>:https://<service-id>/...`。不改路由，新组件就不会被用到，只是多一个空池。

