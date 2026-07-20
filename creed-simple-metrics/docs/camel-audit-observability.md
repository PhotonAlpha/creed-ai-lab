# Camel 审计与耗时观测:三层设计、拦截点选型与踩坑

`creed-simple-metrics` 对「所有 route / 下游 HTTPS 请求的审计与响应时间统计」采用**三层互补**的设计,
本文记录每层的机制选型(为什么是它、为什么不是别的)、实现要点,以及两个真实踩过的坑
(hc5 entity 一次性流、Logbook 静默)。

## 总览:三层各看什么

| 层次 | 机制 | 类 / 配置 | 输出 |
|---|---|---|---|
| REST 请求整体(入站一次) | `SynchronizationAdapter` + `RoutePolicyFactory` | `RestApiAuditSynchronization` / `RestApiAuditRoutePolicyFactory` | 业务日志一行 `CAMEL-AUDIT GET /camel/api/x status=200 in 27ms` |
| 每次发送(route 内每个 `<to>`、multicast/wireTap 分支、ProducerTemplate) | `EventNotifier`(`ExchangeSentEvent`) | `CamelSendTimingEventNotifier` | `METRICS` 具名 logger → `*-metrics.log` 单行 key=value |
| 每次 HTTP 网络往返(下游实例级) | hc5 `ExecChainHandler` ×3 | `LogbookHttpExecHandler`(完整审计)+ `ObservationExecChainHandler`(指标/trace)+ `CamelLoadBalancerAuditExecHandler`(LB 实例一行) | Logbook 审计块 + `httpcomponents.httpclient.request` timer + `LB resolved -> instance=...` |

三层的信息不重叠:整体耗时(第 1 层)⊃ 各分支耗时(第 2 层,URI 还是 `catalog-resource` 逻辑名)
⊃ 实例级往返(第 3 层,`localhost:18081` + 纯网络耗时)。排查「慢在哪」时从上往下钻。

## 拦截点选型:为什么是这三种

Camel 没有 RestClient 那样的 `ClientHttpRequestInterceptor` 概念,候选机制的取舍:

- **`EventNotifier`(选用,第 2 层)**:`ExchangeSentEvent` 覆盖一切发送(含 ProducerTemplate),
  自带 `getTimeTaken()`;声明成 `@Component` 即可——经典 `<camelContext>` 的
  `AbstractCamelContextFactoryBean.afterPropertiesSet()` 会自动发现注册表里的
  `EventNotifier`/`RoutePolicyFactory` bean(camel-core-xml 4.18.2 源码 374-380 / 479-487 行),
  **不需要手动挂**。跳过 `direct:`(JVM 内胶水,耗时已含在外层发送里)。
- **`SynchronizationAdapter`(选用,第 1 层)**:单个 exchange 的完成回调(`onComplete`/`onFailure`),
  异步路由下也等真正完成才触发。但它**没有全局注册入口**(必须逐 exchange 挂)、粒度是整个
  exchange(拆不出分支)——所以配一个 `RoutePolicyFactory` 在 `onExchangeBegin` 里自动挂,
  仅对 from-endpoint 是 `servlet:` 的 exchange 挂一次(exchange property 幂等去重,嵌套
  `direct:` 路由跳过)。
- **hc5 `ExecChainHandler`(选用,第 3 层)**:与 RestClient 拦截器真正同层的位置。HttpClient 在
  进 exec chain **之前**已咨询过 RoutePlanner,所以 `scope.route.getTargetHost()` 就是 LB 选中的
  实例——这是其它任何层都拿不到的信息(请求自身的 authority 还是逻辑服务名)。
  `addExecInterceptorLast` = 最内层、retry 之内,计时每次实际网络往返。
- **`LoadBalancerLifecycle`(未选)**:Spring Cloud LB 的回调 SPI(`onStart`/`onStartRequest`/
  `onComplete`),但它只被 `BlockingLoadBalancerClient.execute(...)` 触发——camel 侧的
  `LoadBalancerRoutePlanner` 只调 `choose()`,**不会触发任何 lifecycle 回调**。RestClient 链路可用
  (`spring.cloud.loadbalancer.stats.micrometer.enabled=true` 零代码出 `loadbalancer.requests.*`),
  camel 链路帮不上。
- **`interceptSendToEndpoint` / `InterceptStrategy`(未选)**:包装式拦截做计时绕,不如
  EventNotifier 一个事件搞定。

## 现成方案 vs 手写:分工结论

| 需求 | 用现成 | 手写保留 |
|---|---|---|
| HTTP 计时/指标 | Micrometer `ObservationExecChainHandler`(见下节) | — |
| 完整审计(headers/cookies/body/打码) | Zalando Logbook(见下节) | — |
| LB 解析后实例的**日志**可见性 | —(Logbook/Observation 拿不到 `scope.route`) | `CamelLoadBalancerAuditExecHandler` 一行日志 |
| route/exchange 级计时落**日志文件** | camel-micrometer 只出指标不出日志 | `CamelSendTimingEventNotifier` + `RestApiAuditSynchronization` |
| 调试用零代码抓包 | `org.apache.hc.client5.http.wire` / `.headers` DEBUG | — |

## ObservationExecChainHandler(Micrometer hc5 instrumentation)

`CamelConfig.httpComponent` 的 configurer 上(与 `PoolingHttpClientConnectionManagerMetricsBinder`
同包 `io.micrometer.core.instrument.binder.httpcomponents.hc5`):

```java
builder.addExecInterceptorAfter(ChainElement.RETRY.name(), "micrometer",
        new ObservationExecChainHandler(observationRegistry))   // Micrometer 文档建议的位置
```

- 产出 `httpcomponents.httpclient.request` timer——camel-http 链路的 `http.client.requests` 对应物;
- **`target.host`/`target.port` tag 就是 LB 解析后的实例**(exec chain 在 route planning 之后跑),
  开箱即得 per-instance 延迟/错误率/QPS;
- 顺带激活 **trace 传播**:camel-http 下游请求自动带 `traceparent` 头(此前只有 RestClient 链路有);
- 已知局限:`uri` tag 是 `UNKNOWN`(hc5 层没有 URI 模板概念,默认约定防基数爆炸;要按路径分就自定义
  `ApacheHttpClientObservationConvention`,本模块 `target.port` 已够区分集群,未配);
- 这个 handler 本身**不打开任何 Observation/Brave scope**(反编译确认:只 `Observation.start()` →
  `chain.proceed()` → `.stop()`),曾经怀疑过它导致 `correlationTraceId`(local baggage,见
  `MyMDCScopeDecorator`)在 camel-http 调用里丢失,A/B 实测排除——真正的根因是当时还在用的
  `camel-observation-starter`,已经移除;ProducerTemplate `asyncRequestBody*` 断链的完整分析
  (context-propagating executor)见
  [camel-producertemplate-context-propagation.md](camel-producertemplate-context-propagation.md),
  camel-observation-starter 那次排查与移除的完整过程见
  [camel-observation-baggage-loss.md](camel-observation-baggage-loss.md)。

## Zalando Logbook:完整审计的落点

替代了手写的入站 `AuditLoggingFilter` 和 hc5 审计块,两个方向共用一份 `logbook.*` 配置:

- **依赖**:`logbook-spring-boot-starter`(自动注册 servlet `LogbookFilter`,入站)+
  `logbook-httpclient5`(`LogbookHttpExecHandler`,挂在 `CamelConfig` 与
  `CookieRelayRestClientConfiguration` 两个 hc5 builder 上,`addExecInterceptorFirst` 按官方文档);
- **配置**(`application.yml`):`format.style: http`(可读块;可换 json/curl/splunk)、
  `obfuscate.headers: [Authorization, Proxy-Authorization]`(打码,输出 `authorization: XXX`)、
  `write.max-body-size: 4096`、`predicate.exclude: /actuator/**`;
- 旧 `AuditLoggingFilter` 保留在 `creed.audit.legacy-filter.enabled=true` 开关后面(默认关,
  避免同一流量双份审计);
- 请求/响应块用 **correlation id** 关联(`Incoming Request: 96162d71...` ↔
  `Outgoing Response: 96162d71...`),响应块带 `Duration: N ms`。

### 坑 1:不开 TRACE 就完全静默

Logbook 往 `org.zalando.logbook.Logbook` logger 写 **TRACE** 级——logback 不开这行,整套审计
**无声失效**(不报错):

```xml
<logger name="org.zalando.logbook" level="TRACE"/>
```

### 坑 2:predicate 的 include 会误杀出站

Logbook 实例是入站/出站**共用**的:若写 `predicate.include: path=/camel/**`,出站请求
(path 是 `/api/catalog/items`)会被整个排除。所以用 **exclude**(`/actuator/**`)而不是 include。

### 坑 3:jackson-databind 的「7 vulnerabilities」不用管

mvnrepository 上 Logbook 页面标的漏洞属于它**声明时的旧版 jackson**(optional 依赖)。本项目里
jackson 由 Spring Boot dependencyManagement 统一锁版(当前 2.21.2,晚于全部相关 CVE 修复),
`dependency:tree` 里 Logbook 名下根本没有 jackson 分支——旧版从未落到 classpath。加 `<exclusions>`
只是删一条本来就没生效的边,应用自身(spring-web/camel-jackson)照样需要 jackson,无需处理。

### 生产环境用法(已实施)

> 完整的落地清单、每个 `creed.logbook.*` 开关、请求/响应内容类型过滤的**方向差异**、以及
> 逐条验证方法,见专门的 [logbook-production-tuning.md](logbook-production-tuning.md)。本节保留
> 三层审计设计视角下与本层相关的要点。

最初这里只是一段建议;下面几点已经落到代码里(`application.yml` `logbook.*`/`creed.logbook.*` +
`web/LogbookAuditConfiguration.java` + `web/ContentAwareBodyStrategy.java` + `logback-spring.xml`),
不再是"建议"。一句话原则不变:**默认零缓冲成本,错误才带 body,格式/落盘与业务日志分离**。

```yaml
logbook:
  predicate:
    exclude:
      - path: /actuator/**              # 健康检查/抓取路径一并排除
  format:
    style: splunk                        # 单行 field=value;本项目选 splunk 不是 json(两者都比
                                          # http 多行块在日志系统里好切分,按目标平台选一个就行)
  obfuscate:
    headers: [Authorization, Proxy-Authorization]
  write:
    max-body-size: 4096                  # 只截断"写出"的内容,见下面 body 策略的说明
creed:
  logbook:
    skip-paths: []                       # 按需加 Ant 模式,高流量/无意义路径整个跳过审计
    body-on-error:
      enabled: false                     # 生产建 true;测试/本地留 false 便于调试
      minimum-status: 500
```

- **body 策略,真正的零缓冲**:内置的 `strategy: body-only-if-status-at-least`(连同
  `status-at-least`/`without-body`)全部只重写 `Strategy.write(...)`——那时两个 body 早经默认
  `process()` 完整缓冲进内存了,`minimum-status` 只是决定要不要把已经缓冲好的内容写出去,
  **省的是磁盘/网络 I/O,不是内存**。`ContentAwareBodyStrategy` 改在 `process(HttpRequest,
  HttpResponse)` 里判断——这个钩子按 `Strategy` 契约在 response body 读取**之前**触发,
  而 hc5 classic(`LogbookHttpExecHandler` 用的这条集成)在这一步 status line/headers 已经到手、
  entity 还没消费,`response.getStatus()`/`getContentType()` 都可靠——所以正常响应的 body 真的
  一次都不缓冲(请求体仍按默认策略缓冲,通常很小,错误时留着有用)。`creed.logbook.body-on-error.
  enabled` 默认 `false`(本地/测试留全量 body 方便调试),生产打开。
- **请求 vs 响应两侧的内容类型过滤**:`creed.logbook.allowed-content-types`(默认 `application/json`)
  同时管两个方向,但生效点/效果不同——
  - **请求侧**在 `LogbookAuditConfiguration#requestCondition`(替换 autoconfigure 默认的 `$ -> true`,
    按 bean **名字** `requestCondition` 匹配)里,处于 `Logbook.condition()` 顶层:非白名单且有 body
    的请求**整条审计**(请求+响应)都不记,连 body 缓冲入口都不碰;
  - **响应侧**只能在 `ContentAwareBodyStrategy.process(request, response)` 里判断(`condition` 拿不到
    响应),非白名单响应**只丢 body、留元数据行**(请求那时已写出,丢不掉)。这个方向差异是 Logbook
    生命周期决定的,详见 [logbook-production-tuning.md](logbook-production-tuning.md)。

  另外 `creed.logbook.skip-paths`(Ant 模式路径黑名单)也在 `requestCondition` 里叠加。Logbook 自身的
  `logbook.predicate.include/exclude` 只支持 path/method 两个字段(反编译 `LogbookProperties.LogbookPredicate`
  确认过),没有内容类型维度——这正是要另开 `creed.logbook.*` 而不是复用 `logbook.predicate.*` 的原因;
  两者叠加生效,不是替代。
- **body 级脱敏要写代码**(属性配置管不到 JSON 字段),注册 `BodyFilter` bean 即被
  autoconfigure 合并。payment 域这条**不是可选项**:PAN/CVV 进日志即 PCI-DSS 违规——
  要么字段级打码,要么用 predicate 把支付路径排除出 body 记录(尚未实施,仍是建议):

  ```java
  @Bean
  BodyFilter bodyFilter() {
      return JsonBodyFilters.replaceJsonStringProperty(
              Set.of("password", "cardNumber", "cvv"), "***");
  }
  ```

- **运行时开关 = 日志级别**(坑 1 的另一面):`writer.isActive()` 为 false 时缓冲/格式化整条
  链路短路跳过,零开销。生产可常态把 `org.zalando.logbook.Logbook` 关到 INFO,排障时通过
  actuator loggers 端点热开 TRACE,无需改配置重启。
- **出站范围**:只给业务 hc5 client 挂 `LogbookHttpExecHandler`;health-check 独立池**不挂**,
  否则每轮探活都刷审计日志(业务/探活双池隔离正好方便这一点)。
- **输出通道,已接 AsyncAppender**:`logback-spring.xml` 给 `org.zalando.logbook` 单配了
  `LOGBOOK_FILE`(独立文件 + 独立滚动策略,同 `*-metrics.log` 套路)+ `ASYNC_LOGBOOK` 包一层
  `AsyncAppender`(`additivity=false`,不回流业务文件),控制台输出也走同一个 async appender。
  `neverBlock=true` + `discardingThreshold=0`:前者保证队列打满时丢日志而不是阻塞
  `LogbookHttpExecHandler` 所在的 hc5 调用线程(这段 I/O 本来是同步算进下游调用耗时的);后者关掉
  "队列超 80% 自动丢 TRACE/DEBUG/INFO" 的默认行为——Logbook 全部走 TRACE,默认阈值会把审计日志
  整体误伤,只有 `neverBlock` 兜底的整队列丢弃才是我们想要的降级方式。pattern 保留
  `%X{traceId}`:Logbook 自身 correlation id 配对 request/response 两行,traceId 负责跨服务串联。
- **审计"是谁"**:纯报文缺主体信息;注册 `AttributeExtractor`(如
  `JwtFirstMatchingClaimExtractor` 提取 JWT `sub`/`client_id`)让审计行直接回答
  "哪个客户端在何时调了什么"。

## 坑:hc5 entity 是一次性流,且 `getContent()` 不可信

(此坑发生在手写完整审计块时期,教训通用——任何要在 exec chain 里读 body 的代码都会遇到。)

hc5 的 `HttpEntity` 和 servlet stream 一样是一次性的:在 exec chain 里读了 body,真正的消费方
(网络写出 / Camel / RestClient)就拿到空流。直觉做法是「非 repeatable 就包 `BufferedHttpEntity`」,
**两次翻车**:

1. `BufferedHttpEntity` 靠 `entity.getContent()` 缓冲,而 Spring RestClient 的请求 entity
   (`HttpComponentsClientHttpRequest.BodyEntity`)是**只写 entity**,`getContent()` 直接抛
   `UnsupportedOperationException`(camel-http 的 entity 恰好支持,所以只在 RestClient 链路炸);
2. 想按 `isRepeatable()` 分支也不行:`BodyEntity` 报 `isRepeatable()=true` 但 `getContent()`
   照抛——**repeatable 不代表可读流**。

正确姿势:**统一走 `writeTo(OutputStream)`**(所有 entity 实现都支持),抽干进 `byte[]` 后回写
`ByteArrayEntity`(保留 contentType/encoding/chunked),请求侧在 `chain.proceed` **之前**做
(发送会消费 entity),响应侧在之后做。Logbook 内部处理了同类问题,这也是「审计选现成库」的论据之一。

## 输出示例(一次 cookie-relay 请求的三层视角)

```
# 入站(Logbook servlet)
Incoming Request: 96162d7185b454ec
POST https://localhost:8097/camel/api/cookie-relay?cluster=order HTTP/1.1
authorization: XXX

# 出站(Logbook hc5)——buggy Cookie 头原样可见
Outgoing Request: a48d5abb86b7bf4d
POST https://localhost:18091/api/order/echo HTTP/1.1
Cookie: JSESSIONID=...; WSASID="...";$Path="/";$Domain="api.github.com"
{"probe":"cookie-relay"}
Incoming Response: a48d5abb86b7bf4d
Duration: 9 ms
Set-Cookie: JSESSIONID=...; Path=/; HttpOnly, WSASID=...; Max-Age=3600; HttpOnly

# LB 实例一行(手写 ExecChainHandler)
LB resolved -> instance=localhost:18091 POST /api/order/echo status=200 in 9ms

# 发送级计时(EventNotifier → *-metrics.log)
camel-send endpoint=https://order-resource/api/order/... status=200 timeMs=12 failed=false fromRoute=cookieRelay ...

# 请求整体(SynchronizationAdapter)
CAMEL-AUDIT POST /camel/api/cookie-relay?cluster=order status=200 in 174ms exchangeId=... route=cookieRelay
```

## 实现备忘

- `RestApiAuditRoutePolicyFactory` 在挂载时(`onExchangeBegin`)就捕获 method/path:`fetch-*` 路由
  的 `<removeHeaders pattern="CamelHttp*"/>` 会把入站 HTTP 头清掉,完成时已拿不到;
- `RestApiAuditSynchronization` 完成时的 `CamelHttpResponseCode` 是**最后一个** producer 留在
  message 上的(通常是下游状态码),不必然等于 servlet 响应码——缺失时按成功/失败回退 200/500;
- `spring-boot:run` 必须 `-Dspring-boot.run.workingDirectory=<repo根>`,否则 SSL bundle 的相对
  路径 `.support/scripts/pki/...` 解析不到(默认工作目录是模块目录)。
