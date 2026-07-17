# camel-http + Spring Cloud LoadBalancer：ServiceCall EIP 的替代方案

Camel 4 移除了 ServiceCall EIP（`staticServiceDiscovery` 随之废弃且无官方替代）。本模块的方案：
**路由里的端点 URI 保持逻辑服务名不变**（`https://catalog-resource/api/catalog/items`），在 Apache
HttpClient 5 的 `HttpRoutePlanner` 扩展点上接入 Spring Cloud LoadBalancer，在**建连那一刻**把服务名
解析成健康实例。路由零改造、camel-http 的全部端点能力（连接池 / mTLS / 流式响应 / cookie / 超时）原样保留。

```
<to uri="https://catalog-resource/api/catalog/items"/>        ← 路由不感知 LB
        │
        ▼
HttpProducer.executeMethod → httpClient.executeOpen(target=catalog-resource)
        │
        ▼  InternalHttpClient 在 exec chain 之前调用
HttpRoutePlanner.determineRoute("catalog-resource")            ← 本方案唯一的切入点
        │
        ├─ DiscoveryClient.getServices() 不含该 host → DefaultRoutePlanner 直连（真实域名不受影响）
        │
        └─ 是逻辑服务名 → LoadBalancerClient.choose("catalog-resource")
                              │（per-service 子上下文里的 supplier 链）
                              ▼
                    ServiceInstance(https://localhost:18081)
                              │
                              ▼
        HttpRoute(https, localhost, 18081, secure=true) → 按 route 取连接池 → mTLS 握手 → 发请求
```

落地代码：`LoadBalancerRoutePlanner`（planner 本体）、`CamelConfig`（http/https 组件 + mTLS 池 + 池指标）、
`camel-context.xml` 的 `fetch-catalog` / `fetch-order` 路由。弹性模式（捕获下游错误状态码进
`branchError`/`branchStatus`）的 bulk 拉取仍走 `RemoteClusterProcessor`。

---

## 三个核心接口：职责与实现类链路

### 1. `DiscoveryClient` —— “有哪些服务、每个服务有哪些实例”

```
org.springframework.cloud.client.discovery.DiscoveryClient          （接口）
  └─ SimpleDiscoveryClient                                          （本模块实现）
       └─ 数据源 SimpleDiscoveryProperties
            ← application.yml: spring.cloud.discovery.client.simple.instances.<serviceId>[N].uri
            → 每条记录变成 DefaultServiceInstance(host/port/secure 由 uri 推导, instanceId=null)
  └─ CompositeDiscoveryClient（Spring 注入的其实是它：按 Order 聚合所有 DiscoveryClient）
```

在本方案里 `DiscoveryClient` 只干一件事：`getServices()` 判定“target host 是不是逻辑服务名”。
是 → 走 LB；不是 → 原样直连。`SimpleDiscoveryClient` 是读内存 map，每请求调用无成本；换成
Eureka/Consul 时 `getServices()` 可能打远端，必须改成本地缓存的服务名集合。

### 2. `LoadBalancerClient` —— “这个服务此刻该连哪个实例”

```
org.springframework.cloud.client.loadbalancer.LoadBalancerClient    （接口, extends ServiceInstanceChooser）
  └─ BlockingLoadBalancerClient                                     （servlet 栈实现, 自动装配）
       │  choose(serviceId) = Mono.from(reactorLoadBalancer.choose()).block()
       ▼
     LoadBalancerClientFactory（NamedContextFactory：每个 serviceId 一个子 ApplicationContext）
       │  子上下文的配置类由 @LoadBalancerClients(defaultConfiguration=PartnerLoadBalancerConfiguration)
       │  指定；子上下文 Environment 携带自己的服务名（属性 loadbalancer.client.name，
       │  官方取法 LoadBalancerClientFactory.getName(env)）
       ▼
     RoundRobinLoadBalancer（默认 ReactorServiceInstanceLoadBalancer；AtomicInteger position 轮询）
       │  注入 ObjectProvider<ServiceInstanceListSupplier> —— 就是子上下文里我们那个 @Bean
       ▼
     ServiceInstanceListSupplier 装饰器链（PartnerLoadBalancerConfiguration 组装，外层在前）：
       CachingServiceInstanceListSupplier（.withCaching()，Caffeine，默认 TTL 35s）
         └─ LoggingHealthCheckServiceInstanceListSupplier
              （extends HealthCheckServiceInstanceListSupplier；后台定时探活
                /actuator/health，只发射存活实例的累积列表；探活状态与被吞异常打 [LB-HEALTH] 日志）
              └─ DiscoveryClientServiceInstanceListSupplier（.withBlockingDiscoveryClient()，
                   从 loadbalancer.client.name 得知自己服务名，再调 DiscoveryClient.getInstances()）
```

关键点：**`choose()` 只读健康检查流缓存的最新存活列表**，请求路径上不做同步探活，所以在 Camel
producer 线程里 block 是安全的。多个服务的“区分”靠子上下文隔离（详见踩坑 8）。

### 3. `HttpRoutePlanner` —— “这条连接物理上连到哪、要不要 TLS、走不走代理”

```
org.apache.hc.client5.http.routing.HttpRoutePlanner                 （HttpClient 5 SPI）
  └─ DefaultRoutePlanner（缺省实现；SystemDefaultRoutePlanner 额外处理 JVM 代理属性）
  └─ LoadBalancerRoutePlanner（本模块实现，非服务名 fallback 给 DefaultRoutePlanner）
```

调用时机：`InternalHttpClient` 在**整个 exec chain（重试/重定向/协议层）之前**调用
`determineRoute(target, context)`，返回的 `HttpRoute`（目标 host:port + secure 标志 + 代理链）同时是
**连接池的分池 key**——所以每个下游实例天然独立分池，`PoolingHttpClientConnectionManagerMetricsBinder`
的 per-route 指标也按实例区分。planner 返回与请求 URI authority 不同的 host:port 是协议允许的
（这正是反向代理/LB 的语义）：TLS 按**解析后的实例 host** 做 SNI 与证书校验（与 `@LoadBalanced`
RestClient 行为一致，匹配 Creed-CA 的 localhost 证书），而 `Host` 请求头保留逻辑服务名。

### 串起来：一次 `GET /camel/api/catalog` 的完整链路

```
camel-servlet 入站 → direct:catalog → direct:fetch-catalog
→ removeHeaders CamelHttp*                                   （踩坑 3）
→ HttpProducer.process：createMethod（URI=https://catalog-resource/...）→ executeMethod
→ CloseableHttpClient.executeOpen(target=catalog-resource:443)
→ InternalHttpClient.determineRoute
   → LoadBalancerRoutePlanner：
       discoveryClient.getServices().contains("catalog-resource") → true
       loadBalancerClient.choose("catalog-resource")
         → LoadBalancerClientFactory 子上下文 "catalog-resource"
         → RoundRobinLoadBalancer.choose
         → Caching → HealthCheck(存活列表) → Discovery 链取列表，position++ 轮询
         ← ServiceInstance(https://localhost:18081)
       ← HttpRoute(localhost:18081, secure=true)
→ 连接池按该 route 租连接（首个请求 mTLS 握手，creed-partner-client bundle）
→ 请求发出（Host: catalog-resource）→ ClassicHttpResponse 流式返回
→ <unmarshal json> → aggregateStrategy / REST JSON 绑定
```

---

## 为什么不是其它方案

| 备选 | 结论 |
|---|---|
| 覆写 `HttpProducer#executeMethod` 换成 RestClient | 否。需要 Producer/Endpoint/Component 三层子类 + 复制 150 行 `createEndpoint`；响应侧要伪造 `ClassicHttpResponse`；`executeOpen` 的“不关流、on-completion 再消费”语义与 `RestClient.exchange()` 回调即关流冲突；端点上所有 Apache 层配置静默失效 |
| 覆写 `executeMethod` 只换 `HttpHost` | 语义对，但同样绕不开三层子类，不如 planner 一个 lambda |
| `interceptSendToEndpoint` + `Exchange.HTTP_URI` 改写 | 可行（纯 Camel 层），但要声明进每个 RouteBuilder/XML，集中度差；保留为备选 |
| `@LoadBalanced` RestClient 包在 Processor 里（旧方案） | 可行但路由失去 camel-http 端点语义，JSON 绑定/错误处理都要手写；bulk 弹性路径仍用此方案 |

---

## 踩坑记录（均已验证）

1. **scheme 按 bean 名解析**：Camel 把 `https://...` 端点解析到 registry 里**名为 `https`** 的组件
   bean；只注册 `http` 的话,https 端点会走 factory **静默新建一个未配置的 HttpComponent**（没有
   planner、没有 mTLS 池）。修复：`@Bean(name = {"http", "https"})` 单实例双名——`HttpComponent`
   本身不持有 scheme，endpoint 的 secure 由 URI 推导，同一实例可服务两个 scheme。
2. **连接池属主**：`HttpComponent.doStop()` 会 close 传进来的 connection manager（HttpComponent
   4.18.2 `doStop`）。池 bean 必须 `@Bean(destroyMethod = "")` 禁掉 Spring 推断的 close，把关闭权交给
   Camel；endpoint 侧因 manager 来自 component 会自动 `setConnectionManagerShared(true)`，不会提前关。
3. **servlet 入站控制头覆盖端点 URI**：camel-servlet 消费者会在 Exchange 留下
   `CamelHttpUri=/camel/api/catalog`、`CamelHttpPath` 等控制头，而 `HttpHelper.createURL` **无条件**用
   它们覆盖 producer 端点 URI（不受 `skipControlHeaders` 控制），报
   `Invalid url: /camel/api/catalog`。修复：`<removeHeaders pattern="CamelHttp*"/>` 后再 `<to>`。
   **不要**用报错提示的 `bridgeEndpoint=true`——那是代理转发语义，会把入站 path 追加到端点 URI 上。
4. **`HttpRoute` 的 secure 标志**：`new HttpRoute(host)` 默认 `secure=false`，https 流量会走明文导致
   握手失败。必须 `new HttpRoute(host, null, "https".equalsIgnoreCase(scheme))`（`DefaultRoutePlanner`
   内部也是这么做的）。
5. **SSL bundle 缺失 ≠ 降级到 http**：降级模式是池上不装自定义 TLS strategy，回退 JVM 默认
   SSLContext——仍走 TLS，只是没有客户端证书、不信任 Creed-CA，运行时握手失败。每个实例的实际协议由
   注册表 `instance.isSecure()`（即 yml 里 uri 的 scheme）决定，与 bundle 是否存在无关。
6. **首批请求粘在单实例（最长 35s）**：`withCaching()` 的 Caffeine 缓存（默认 TTL 35s）缓存的是健康
   检查流的**第一次发射**——首个请求触发订阅时,往往只有最先完成探活的实例在列表里。TTL 过期后取到
   全量存活列表，轮询恢复正常。是已知行为不是 bug；介意就调小
   `spring.cloud.loadbalancer.cache.ttl` 或加 `health-check.initial-delay` 预热。
7. **`choose()` 不触发 `LoadBalancerLifecycle`**：`onStart/onComplete` 回调（Micrometer 的
   `loadbalancer.requests` 统计靠它）只有 `execute()` 会走，而 `execute()` 要包裹整个调用，塞不进
   planner。本场景放弃该指标；HTTP 层指标由池 binder（`camelHttpPool`）与 Camel observation 覆盖。
8. **多服务/多 supplier 的区分**：靠 `LoadBalancerClientFactory` 的 per-serviceId 子上下文，不靠 bean
   名。同一配置类在每个子上下文各实例化一份，内部可用
   `LoadBalancerClientFactory.getName(env)` 取服务名分支；结构性差异用
   `@LoadBalancerClient(name=..., configuration=...)`，参数差异用
   `spring.cloud.loadbalancer.clients.<id>.*`。**禁止**在同一配置类声明两个
   `ServiceInstanceListSupplier`（子上下文内 `NoUniqueBeanDefinitionException`）。
9. **故障转移语义**：HttpClient 内置 retry 在**同一条 route** 上重试，不会换实例；要换实例需 Camel
   redelivery（`onException().maximumRedeliveries(n)`），重投会重新进 planner 选新实例。实例故障的
   常态剔除由健康检查完成（探活失败 → 列表移除 → `choose()` 不再返回；全挂时 planner 抛
   `HttpException: No alive instances`）。
10. **本地验证的端口占用**：Artifactory 容器占 8081/8082,资源服务本地实例已整体迁移到
    18081/18082（catalog）、18091/18092（order）与 18093/18094（payment）,`application.yml` 的
    simple 注册表同步更新。

---

## 自定义 ServiceInstanceListSupplier：`get()` / `get(Request)` 的原理与注意点

payment-resource 的 cookie 粘滞（`StickyMetadataServiceInstanceListSupplier`，经
`@LoadBalancerClient(name="payment-resource", configuration=PaymentStickyLoadBalancerConfiguration.class)`
只挂在该服务上）是一个覆写这两个方法的实例。写自定义 supplier 前先弄清它们在框架里的角色：

### 原理

- **接口契约**：`get()` 是抽象方法；`get(Request)` 是 default 方法，默认转调 `get()`（丢弃 request）。
  `RoundRobinLoadBalancer.choose(request)` 真正调用的是 **`get(Request)`** —— 它才是官方的
  "按请求上下文选实例"扩展点，框架自带的 `RequestBasedStickySessionServiceInstanceListSupplier` /
  `HintBasedServiceInstanceListSupplier` 都覆写它。
- **Request 里有没有东西取决于调用入口**：
  - `@LoadBalanced` RestTemplate/RestClient 路径（`LoadBalancerInterceptor` →
    `BlockingLoadBalancerClient.execute(serviceId, lbRequest)`）：框架把出站请求包成 `RequestData`
    放进 `RequestDataContext`，`request.getContext()` 能读到 URL/headers；
  - 本模块 `LoadBalancerRoutePlanner` 路径：直接 `choose(serviceId)`，内部构造
    `DefaultRequest<DefaultRequestContext>`，**context 里只有 hint，没有任何请求数据**。
    这就是粘滞值改走 `StickyContextHolder`（ThreadLocal）、两个方法共用同一实现的原因。

### 注意点（按重要性排序）

1. **两个方法成对覆写、语义一致。** 链上的包装层有的调 `get()`、有的调 `get(request)`；
   只覆写其一，另一条路径会经 default 方法/delegate 绕过你的逻辑。
2. **组装期 vs 发射期——ThreadLocal 只在前者可靠。** `get()` 方法体在 `choose()` 的调用线程上
   同步执行（组装期）；返回的 Flux 里的 `map`/`filter` lambda 在发射期执行，发射线程不保证是
   调用线程（health-check supplier 从自己的 Scheduler 发射、cache 回放可能换线程）。所以必须
   **在方法体里把请求态捕获成不可变局部变量**再进算子链（见
   `filteredByCurrentThreadStickyId()`）；写进 lambda 就是"平时能跑、并发才炸"的 bug。
   并发隔离由 `StickySelectionThroughLoadBalancerTest` 的双线程用例锁定。
3. **get() 里不做重活、不阻塞。** 每次 `choose()` 都会调它，只应在 delegate 的 Flux 上叠轻量算子；
   IO/探活放链的内层并被 caching 包住。
4. **链上位置决定正确性。** 请求相关的过滤必须在 `withCaching()` **之外**（每请求执行，缓存里
   永远是全量存活列表）；放反了缓存会存住某个请求的过滤视图，污染后续请求直到 TTL 过期。
   `PaymentStickyLoadBalancerConfiguration` 因此复用 `healthCheckedSupplier(...)`（discovery →
   探活 → caching）后在**最外层**叠粘滞过滤。
5. **想清楚空列表语义。** 过滤后为空,`choose()` 返回 `EmptyResponse`，本模块的 planner 会抛
   "No alive instances"。是"钉不住就失败"还是"回退全量"要显式决策——本实现选回退 + WARN
   （可用性优先于粘滞），框架的 `ZonePreferenceServiceInstanceListSupplier` 同策略。
6. **supplier 是每服务单例，别放请求态字段。** 它活在对应服务的 LB 子上下文里被所有请求共享；
   请求态只能经 Request 参数或线程上下文传入。另外 default 配置与 per-client 配置会同时注册进
   子上下文：default 侧的 supplier bean 必须 `@ConditionalOnMissingBean(ServiceInstanceListSupplier.class)`
   （见 `PartnerLoadBalancerConfiguration`），否则两个 supplier bean 冲突。

---

## 配置项与观测

| 配置 | 默认 | 说明 |
|---|---|---|
| `creed.camel.http.max-total` / `max-per-route` | 50 / 20 | camel-http 池容量（per-route = per-实例） |
| `creed.camel.http.connect-timeout` / `socket-timeout` | 5s / 10s | 建连 / 读超时（ConnectionConfig） |
| `creed.camel.http.connection-request-timeout` / `response-timeout` | 3s / 10s | 租池 / 响应超时（组件级） |
| `creed.partner.client-bundle` | creed-partner-server | 出站 mTLS bundle（与 RestClient 共用） |
| `spring.cloud.loadbalancer.health-check.interval` | 5m（本模块） | 探活周期 |

- 选点日志：`logging.level.com.creed.simple.lb=DEBUG` → `[LB-ROUTE] catalog-resource -> https://localhost:18081`；
  探活日志 `[LB-HEALTH] ...status=200 OK, alive=true`（探活失败连同被框架吞掉的异常一起打 WARN）。
- 池指标：`httpcomponents_httpclient_pool_*{httpclient="camelHttpPool"}`（actuator/prometheus）。
- 快速验证：起 catalog 18081/18082、order 18091/18092、payment 18093/18094 后
  `curl -sk https://localhost:8096/camel/api/aggregate`，连续调用观察 `[LB-ROUTE]` 轮询;停掉一个实例,
  下个探活周期后流量只走存活实例。
