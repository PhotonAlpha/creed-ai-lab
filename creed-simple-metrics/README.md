# creed-simple-metrics

Camel-on-Spring-Boot 演示模块（HTTPS 8096）。所有路由/REST/线程池都用**经典 `<camelContext>` Spring XML DSL**
写在 `src/main/resources/camel-context.xml`，经 `@ImportResource` 加载。下游 resource server 调用两种方式并存：

- `fetch-catalog` / `fetch-order` / `fetch-payment`：**camel-http 端点直连逻辑服务名**（`https://catalog-resource/...`），由
  `LoadBalancerRoutePlanner` 在 HttpClient 5 建连时经 Spring Cloud LoadBalancer 解析成健康实例——
  ServiceCall EIP 移除后的替代方案，设计原理与踩坑见
  **[docs/camel-http-loadbalancer.md](docs/camel-http-loadbalancer.md)**；
- bulk 弹性拉取（fulfillment 管道）：仍在 `RemoteClusterProcessor` 里用 `@LoadBalanced` RestClient 调用
  （捕获下游错误状态码进 `branchError`/`branchStatus`），并由 `LoadBalancerAuditInterceptor` 打印
  LB 选中的真实实例。

REST 走 camel-servlet，API 在 `https://localhost:8096/camel/api/*`（hello / time / echo / catalog /
order / payment / aggregate / aggregate-notify / **fulfillment**）。`/aggregate` 与 `/aggregate-notify`
的 multicast 现聚合三个下游集群：catalog（18081/18082）、order（18091/18092）、payment（18093/18094，
`creed-resource-payment` 的列表端点 `GET /api/payment`），均经 `payment-resource` 等逻辑服务名由
LoadBalancer 轮询健康实例。

**payment 集群支持 cookie 粘滞（sticky session）**：请求带 `Cookie: stickyId=<value>` 时，
`PaymentStickyProcessor`（`fetch-payment` 路由第一步）把值放进 `StickyContextHolder`（ThreadLocal，
camel-http 的 producer 与 `LoadBalancerRoutePlanner.choose()` 同线程执行所以可见），`payment-resource`
专属的 `PaymentStickyLoadBalancerConfiguration`（经 `@LoadBalancerClient(name="payment-resource")` 挂载，
其余服务仍走默认 `PartnerLoadBalancerConfiguration`，后者的 supplier 加了 `@ConditionalOnMissingBean`
避免子上下文 bean 冲突）在健康检查过的存活列表上按 `metadata.stickyId`（`application.yml` 注册表中
每实例声明）过滤：命中→只连该实例；无 cookie→正常轮询；无匹配或钉住的实例探活失败→WARN 并回退全量
存活列表（可用性优先于粘滞）。

`POST /api/fulfillment`（请求体可选 `{"failCatalog":bool,"failOrder":bool}`）是一个复杂编排示例：
1. **multicast** 并行拉取 catalog/order 的 **bulk** 大列表并聚合（`fulfillmentAggregateStrategy`）；
2. 按业务规则**过滤** order 列表——仅保留 status∈{NEW,PAID} 且 catalog 中存在该 item 且 `stock≥quantity`
   的订单（`FulfillmentFilterProcessor`）；
3. 再用 **multicast** 调内部 router（`direct:enrich-shipping` / `direct:enrich-risk`）**丰富**响应
   （`enrichmentAggregateStrategy`）；
4. 成功则 **wireTap** 触发 `direct:fulfillment-notification`（fire-and-forget，线程池 B）；
5. 任一下游返回**错误状态码**时（`RemoteClusterProcessor` 在 `resilient=true` 下捕获），经
   `<choice>` 走 `FailureResponseProcessor` 返回失败响应体并设置对应 HTTP 状态码。
   下游 bulk 端点支持 `?fail=true` 故障注入，可用请求体 `{"failOrder":true}` 触发该分支。

---

# `<camelContext>` Spring XML 在 camel-spring-boot 4.18 下的注意事项

把所有路由收进一个经典 `<camelContext xmlns="http://camel.apache.org/schema/spring">`（用 `@ImportResource`
加载）能跑通，但踩了一串坑，逐条记录如下。

## 依赖与加载

- 依赖用 `org.apache.camel:camel-spring-xml`（经典 Spring XML DSL），**不是** `camel-xml-io-dsl`
  （后者是现代 `<routes>`/`<rests>` IO DSL，靠 `camel.main.routes-include-pattern` 加载）。
- `<camelContext>` 外面要包一层 Spring `<beans>`，用 `@ImportResource("classpath:camel-context.xml")` 加载。

## 三个会直接报错的坑

| 报错 | 原因 | 修复 |
|---|---|---|
| `no declaration can be found for element 'camelContext'` | `<beans>` 的 `xsi:schemaLocation` 没声明 camel schema | 补 `http://camel.apache.org/schema/spring .../camel-spring.xsd` |
| `Attribute 'strategyRef' is not allowed ...` | **Camel 4 统一了路由模型**，spring XML 也用现代属性名 | `strategyRef`→`aggregationStrategy`、`executorServiceRef`→`executorService`、`<roundRobin/>`→`<roundRobinLoadBalancer/>` |
| `Cannot find RoutesBuilderLoader ... file extension: xml` | boot 的文件路由收集器仍按默认 `classpath:camel/*` 扫 xml，而 `camel-xml-io-dsl` 已移除（常扫到 `target/classes/camel/` 的**残留**文件） | `camel.main.routes-collector-enabled: false`（+ `mvn clean` 清残留） |

> 你给的片段若是 `strategyRef`/`executorServiceRef`/`<roundRobin/>`，那是 **Camel 3.x 写法**；Camel 4
> 里 `<camelContext>` 经典 DSL 和现代 IO DSL **共用同一套模型**，属性名是现代的。

## 与 camel-spring-boot 共存

- **循环依赖**：内联 `<threadPool>` bean 会和 boot 的 health-check registry 自动配置成环
  （`threadPool → camelContext → HealthCheckRegistry → threadPool`）→ 设 `spring.main.allow-circular-references: true`。
- **单上下文**：没写 `id` 的 `<camelContext>` 会被自动命名 `camel-1`，camel-spring-boot 通过
  `@ConditionalOnMissingBean(CamelContext.class)` **直接接管它**——不是双上下文。

## `inlineRoutes` 坑

camel-spring-boot 默认 REST `inlineRoutes=true`，会把每个 `<to uri="direct:x"/>` 的 REST 路由和同名
`direct:x` 路由**合并**，并**吃掉独立的 `direct:x` 消费者**。任何**复用** `direct:x` 的地方（比如 multicast
聚合）会因此报 `DirectConsumerNotAvailableException`。

修复：`<restConfiguration inlineRoutes="false">`。

## 必备配置速查（application.yml）

```yaml
spring:
  main:
    allow-circular-references: true     # 打破 <threadPool> 与 health registry 的环
camel:
  main:
    routes-collector-enabled: false     # 路由全在内联 <camelContext>，关掉文件收集器
  servlet:
    mapping:
      context-path: /camel/*            # camel-servlet 挂载点
```

REST 传输 / JSON 绑定 / inlineRoutes 都在 XML 的 `<restConfiguration>` 里配，不在 `camel.rest.*`。

# camel-observation 把 `<log>` 变成指标 → 基数爆炸

## 现象

`/actuator/prometheus` 里出现一堆名字超长、值恒为 `1.0` 的指标，例如：

```
fulfillment_notification_fulfillment_notification___totalOrders_500__fulfillable_62__filteredOut_438__catalogItems_200__on_thread_Camel__camel_1__thread__9___notify_poolB_total{component="camel-direct"} 1.0
fulfillment_notification_fulfillment_notification___totalOrders_500__fulfillable_71__filteredOut_429__...thread__10___notify_poolB_total{component="camel-direct"} 1.0
fulfillment_fulfillment_downstream_failure_status_500_total{component="camel-direct"} 10.0
```

旁边还有一组**正常**的 route 级 timer（这些是对的，保留）：

```
fulfillment_seconds_count{component="camel-direct",error="none"} 16        # fulfillment route 跑了 16 次
fulfillment_seconds_sum{component="camel-direct",error="none"} 0.386
fulfillment_active_seconds_count 0                                          # LongTaskTimer：当前在途数
fulfillment_notification_seconds_count{...,error="none"} 6
```

## 根因

`pom.xml` 引入了 **`camel-observation-starter`**：它把**每个 route 和每个 processor 节点**都包成一个 Micrometer `Observation`，再落进 Prometheus registry。

生成的**指标名 = `<routeId>` + 该节点的 label**。而 `<log>` 节点的 label 就是它的**消息文本**。看 `camel-context.xml`：

```xml
<!-- :229 —— 消息里插了每个请求都不同的动态值 -->
<log message="fulfillment notification: ${body[summary]} on thread=${threadName}"/>
<!-- :168 -->
<log message="fulfillment downstream failure status=${header.downstreamStatus}"/>
```

`${body[summary]}` = `{totalOrders=500, fulfillable=62, filteredOut=438, ...}`，`fulfillable/filteredOut` **每个请求都变**，再加上 `${threadName}`（`thread #9/#10/#12`…）。**每一个不同的消息字符串 = 一条全新的 time series**，值永远是 1。

## 为什么危险

- **无界增长**：动态数字 + 线程名随请求无限组合 → 时间序列数量爆炸 → Prometheus 内存暴涨、查询变慢、registry 被垃圾塞满。
- **本质是日志，不是指标**：有用信息全在名字里、值恒为 1，没法聚合查询。典型的「把日志当指标」反模式。

> 对比：`fulfillment_seconds`、`fulfillment_notification_seconds` 这类 **route 级 timer 是好的** —— 名字固定，只有 `error`/`component` 这种低基数 label。要干掉的只是名字里带动态内容的那些。

## 修复方向（从轻到重）

1. **改用 SLF4J 打这种动态日志**：把 `<log>` 换成一个小 `<process>`/bean 里 `log.info(...)`。它不再是被 observation 包裹的「节点」，就不会变指标，日志照常输出。（最推荐——动态内容本来就该进日志而非指标。）
2. **让 camel-observation 只观测 route 级、不观测每个 processor 节点**（或排除 `log` EIP），从根上避免节点 label 进指标名。
3. 给 `<log>` 设静态 `id` —— 但 observation 对 `<log>` 取的是 message 而非 id，单独设 id 不一定够，优先用 1 或 2。

## 一句话

`<log>` 的消息别塞 `${body...}`/`${threadName}` 这种逐请求变化的值——在 camel-observation 下，节点 label 会变成指标名，动态消息 = 基数爆炸。

# Camel 线程池可观测（executor_* 指标）

## 现象

Camel 的 `<threadPool>`（`aggregatePoolA` / `notificationPoolB`）在 `/actuator/prometheus` 里**没有任何 `executor_*` 指标**，dashboard 的 “Application Thread Pools (Executors)” 面板对它们是空的；而 Spring 自己的 `myTask` / `taskScheduler` 却有。

## 根因

`executor_*` 来自 Micrometer 的 `ExecutorServiceMetrics` 绑定器，Spring Boot actuator 只会**自动**给容器里的 `ThreadPoolTaskExecutor` bean 绑定它。Camel 的 `<threadPool>` 是 Camel `ExecutorServiceManager` 内部建的普通 `ThreadPoolExecutor`、**不是 Spring bean**，没人给它调 `ExecutorServiceMetrics.monitor(...)` → 没指标。

## 解决：把池声明成「被监控的 Spring `ExecutorService` bean」，Camel 按 id 引用

`<multicast>/<wireTap>` 上的 `executorService="aggregatePoolA"` 本来就是**按 id 从 registry 查**，所以只要把同名 id 换成包过监控的 Spring bean，引用一行都不用改。见 `web/CamelThreadPoolConfiguration.java`：

```java
@Bean(destroyMethod = "shutdown")
ExecutorService aggregatePoolA(MeterRegistry registry) {
    ThreadPoolExecutor exec = new ThreadPoolExecutor(
            8, 8, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(200),
            new CustomizableThreadFactory("agg-poolA-"));   // 保留原线程名
    return ExecutorServiceMetrics.monitor(registry, exec, "aggregatePoolA"); // name 作为 tag
}
```

并在 `camel-context.xml` **删掉两个 `<threadPool>`**（池大小/队列照搬到 bean 里）。`monitor()` 返回的 `TimedExecutorService` 仍是 `ExecutorService`，Camel 直接能用。

## 结果

`executor_active_threads` / `pool_size_threads` / `pool_core_threads` / `pool_max_threads` / `queued_tasks` / `queue_remaining_tasks` / `completed_tasks_total`（+ `executor_seconds_*`）全部出现，tag `name="aggregatePoolA"|"notificationPoolB"`，正好落进现有 dashboard 的 Executors 面板（`group by (name)` 现在是 `myTask, taskScheduler, aggregatePoolA, notificationPoolB`）。

> 备选：`camel-micrometer` 的 `InstrumentedThreadPoolFactory` 能一次性观测**所有** Camel 池，但指标名是 `camel.thread.pool.*` 那套、和 `executor_*` 面板对不上，还要加依赖。要和现有面板对齐就用上面的做法。

# Camel REST API 计入 http_server_requests_seconds_bucket

## 现象

1. `/actuator/prometheus` **完全没有 `http_server_requests_seconds_bucket`**（连 actuator 端点都没有）。
2. Camel REST（`/camel/api/*`）虽然被计数了，但 uri 是 **`UNKNOWN`**，所有端点挤成一条。

## 根因

- **没桶**：`http.server.requests` 默认不出直方图，只有 `_count/_sum/_max`。
- **UNKNOWN**：Camel REST 由 `CamelHttpTransportServlet`（`/camel/api/*`）处理，**不走** Spring MVC 的 DispatcherServlet。Spring 的 `ServerHttpObservationFilter` 是 `/*` 的 servlet filter，所以请求其实被观测了，但没有匹配的 MVC path pattern → 默认约定把 uri 打成 `UNKNOWN`。

## 解决（两部分）

**① 开直方图并限定范围（`application.yml`）**

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram: { http.server.requests: true }
      minimum-expected-value: { http.server.requests: 1ms }   # 限定桶范围，避免桶数过多
      maximum-expected-value: { http.server.requests: 10s }
```

**② 给 Camel 路径一个真实 uri（`web/CamelRestObservationConvention.java`）**

继承 `DefaultServerRequestObservationConvention`，对 `/camel/` 开头、且**非 4xx/5xx** 的请求把 uri 从 `UNKNOWN` 换成真实路径：

```java
@Component
public class CamelRestObservationConvention extends DefaultServerRequestObservationConvention {
    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues kv = super.getLowCardinalityKeyValues(context);
        HttpServletRequest req = context.getCarrier();
        HttpServletResponse resp = context.getResponse();
        String path = (req != null) ? req.getRequestURI() : null;
        int status = (resp != null) ? resp.getStatus() : 0;
        if (path == null || !path.startsWith("/camel/") || status >= 400) return kv;
        // 去掉默认的 uri tag，换成真实路径
        KeyValues out = KeyValues.empty();
        for (KeyValue k : kv) if (!"uri".equals(k.getKey())) out = out.and(k);
        return out.and(KeyValue.of("uri", path));
    }
}
```

> Camel REST 路径是静态的（无路径参数），且只对成功响应替换 → 基数安全；乱打 404 仍是 `UNKNOWN`/`NOT_FOUND`，不会爆。提供一个 `ServerRequestObservationConvention` bean 即可替换默认约定。

## 结果

- uri 变成 `/camel/api/fulfillment`、`/camel/api/aggregate`、`/camel/api/hello`，`UNKNOWN` 消失。
- `http_server_requests_seconds_bucket` 出现，含每个 Camel 端点的桶 → `histogram_quantile(0.95/0.99, …)` 可用。
- dashboard 的 **Request Latency** 面板已从 avg+max **恢复成 p95/p99**（spring-boot-statistics.json，面板 502）。

> 两套指标互补：Spring 的 `http_server_requests_seconds`（按 HTTP 端点/uri）与 camel-observation 的 route timer `fulfillment_seconds`（按路由），别混用。



@LoadBalanced RestClient.Builder 是一个 bean。Spring Cloud 注册了一个 BeanPostProcessor，在这个 builder bean 的 postProcessAfterInitialization 阶段就给它 .requestInterceptor(LB拦截器)。

时间线：

1. 创建 clusterRestClientBuilder bean
2. BeanPostProcessor 介入 → builder 里已经有 [LB]      ← 注入“前”就发生了
3. 你拿到这个 builder，注入到 clusterRestClient
4. 你 .requestInterceptor(audit) 追加         → builder 变成 [LB, audit]
5. .build()  

RestClient 执行顺序：list 里 index 0 = 最外层。所以 [LB, audit] → LB 先把 https://service-id 改写成 host:port，audit 在它里面跑 → 看到解析后的实例。

关键点：builder 是个可变、保序、可继续 append 的对象，而且 LB 在你拿到它之前就已经加好了，所以你 append 的永远在 LB 之后（更内层）。

RestTemplate 为什么做不到

RestTemplate 的 @LoadBalanced 不走 BeanPostProcessor，而是走一个 SmartInitializingSingleton（LoadBalancerAutoConfiguration）：它在                                                                     
所有单例都创建完之后（afterSingletonsInstantiated）才回过头来，对每个 @LoadBalanced RestTemplate 执行：

List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());                                                                                                             
list.add(loadBalancerInterceptor);   // 追加到「末尾」                                                                                                                                                 
restTemplate.setInterceptors(list);

时间线正好反过来：

1. 你的 @Bean 方法里 setInterceptors([audit])   → [audit]
2. ……所有 bean 创建完……
3. SmartInitializingSingleton 把 LB 追加到末尾    → [audit, LB]   ← 注入“后”才发生，且加在最后
4. 你没有任何“在第 3 步之后再追加”的钩子

再叠加 RestTemplate 的执行语义：list 末尾 = 最内层（最贴近真正发请求）。于是 [audit, LB]：
- LB 在最内层改写 URI；
- audit 在外层，先于 LB 执行，拿到的还是没解析的 https://service-id。

也就是说：用 @LoadBalanced 的 RestTemplate，你自己的拦截器永远在 LB 外层，且没有“LB 加完之后我再加”的注入点 —— 这正是 RestClient 那个两段式技巧无法复制的根本原因。