# creed-simple-metrics

Camel-on-Spring-Boot 演示模块（HTTPS 8096）。所有路由/REST/线程池都用**经典 `<camelContext>` Spring XML DSL**
写在 `src/main/resources/camel-context.xml`，经 `@ImportResource` 加载。下游 resource server 调用走
**Spring Cloud LoadBalancer**（在 `RemoteClusterProcessor` 里用 `@LoadBalanced` RestClient 调用），并由
`LoadBalancerAuditInterceptor` 打印 LB 选中的真实实例。

REST 走 camel-servlet，API 在 `https://localhost:8096/camel/api/*`（hello / time / echo / catalog /
order / aggregate / aggregate-notify / **fulfillment**）。

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
