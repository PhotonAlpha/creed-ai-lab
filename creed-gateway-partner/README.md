# 按 cluster 动态注册 HTTP 客户端（PartnerClientConfiguration + ClusterClientBeanRegistrar）

## 为什么不写静态 @Bean

每个下游 cluster 都需要两套隔离的 Apache HttpClient 5 连接池（业务聚合 + LB 健康检查），各自带
**池 / 请求工厂 / RestClient / 池指标** 四个 bean —— 也就是每个 cluster **8 个 bean**。如果用静态
`@Bean` 方法，加一个 cluster 就要手写 8 个方法，N 个 cluster 后配置类爆炸、且全是复制粘贴。

改为：用 **Map 形式的 `@ConfigurationProperties`**（`creed.partner.clusters.<name>`）描述所有 cluster，
由一个 `BeanDefinitionRegistryPostProcessor` **动态注册**这些 bean。加 cluster = 改 YAML，零 Java 改动。

## 配置结构（PartnerClusterProperties，record + @DefaultValue）

```yaml
creed:
  partner:
    clusters:
      catalog:                       # cluster 名 = 指标后缀 + /api/partner/{name} 路径
        service-id: catalog-resource # LB service-id（须匹配 discovery.client.simple.instances）
        client-bundle: creed-partner-client   # mTLS SSL bundle（默认 creed-partner-client）
        path: /api/catalog/items     # 业务 URL = https://<service-id><path>
        http:                        # 业务池：大、超时宽
          max-total: 50
          max-per-route: 20
          connect-timeout: 5s
          socket-timeout: 10s
          connection-request-timeout: 3s
          response-timeout: 10s
        health-check:
          path: /api/catalog/ping    # 探测路径（覆盖 spring.cloud.loadbalancer.health-check.path）
          http: { max-total: 10, max-per-route: 5, connect-timeout: 2s, socket-timeout: 2s,
                  connection-request-timeout: 2s, response-timeout: 2s }   # 健康池：小、超时紧
      order:
        service-id: order-resource
        path: /api/order/items
        http: { ... }
        health-check: { path: /api/order/ping, http: { ... } }
```

## 动态注册机制（ClusterClientBeanRegistrar）

`ClusterClientBeanRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware`，
在 `PartnerClientConfiguration` 里以 **`static @Bean`** 暴露（static 保证它早于普通 bean 实例化）。

- **绑定**：BDRPP 阶段 `@ConfigurationProperties` bean 还没就绪，所以直接
  `Binder.get(environment).bind("creed.partner.clusters", Bindable.mapOf(String, ClusterSpec))` 从
  `Environment` 绑 Map。
- **注册**：对每个 cluster 注册 8 个 `BeanDefinition`，每个用 `instanceSupplier` 在**实例化时**从
  `BeanFactory` 懒解析协作者（SSL bundle、池、`@LoadBalanced` builder、audit 拦截器）—— 那时上下文正在
  refresh，这些 bean 和 `@LoadBalanced` 后置处理器都已就绪。

每个 cluster 注册出的 8 个**真实命名 bean**（以 `catalog` 为例）：

| bean 名 | 类型 | 说明 |
|---|---|---|
| `catalogHttpConnectionManager` | PoolingHttpClientConnectionManager | 业务池，`destroyMethod="close"` |
| `catalogClientHttpRequestFactory` | BufferingClientHttpRequestFactory | buffered（audit 需重复读 body） |
| `catalogRestClient` | RestClient | **clone `@LoadBalanced` builder** + append audit |
| `catalogPoolMetrics` | MeterBinder | 业务池指标，tag `creed-partner-catalog-aggregate` |
| `catalogHealthCheckHttpConnectionManager` | PoolingHttpClientConnectionManager | 健康池，`destroyMethod="close"` |
| `catalogHealthCheckClientHttpRequestFactory` | HttpComponentsClientHttpRequestFactory | 非 buffered |
| `catalogHealthCheckRestClient` | RestClient | 健康检查专用 |
| `catalogHealthCheckPoolMetrics` | MeterBinder | 健康池指标，tag `creed-partner-catalog-health` |

## 几个关键点

- **业务 client 复用 `@LoadBalanced`**：`PartnerClientConfiguration` 只留一个 `@LoadBalanced
  RestClient.Builder partnerRestClientBuilder()` 模板；每个 `<name>RestClient` 用 `builder.clone()` 拿到
  带 LB 拦截器的副本，再 append audit 拦截器 —— 拦截器顺序与原来一致（见下文）。
- **指标自动绑定**：`MeterBinder` 是 Spring 管理的 bean，Spring Boot 会自动 `bindTo` MeterRegistry，无需手动。
  四个池的 `httpclient` tag 区分：`creed-partner-{catalog,order}-{aggregate,health}`。
- **生命周期单一 owner**：池 bean 带 `destroyMethod="close"`，配合 `RestClientSuppliers` 里
  `setConnectionManagerShared(true)`，由 Spring 关池、client 不再关 —— 避免双重关闭。
- **健康检查路径来自 cluster 配置**：`PartnerLoadBalancerConfiguration` 按 service-id 找到 cluster，取其
  `health-check.path` 与 `<name>HealthCheckRestClient` bean；故 yaml 里
  `spring.cloud.loadbalancer.health-check.path.*` 已删除，只留 interval/initial-delay。
- **消费端全 config-driven**：`PartnerAggregateController` 注入 `PartnerClusterProperties` +
  `Map<String, RestClient>`（按 bean 名查 `<name>RestClient`）；`/aggregate` 遍历所有 cluster，
  `/{cluster}` 按名透传，未知 → 404。

## 加一个 cluster

只在 YAML 加一段 `creed.partner.clusters.<name>`（并同步
`spring.cloud.discovery.client.simple.instances.<service-id>` 的实例地址）。它会自动：注册 8 个 bean、
出现在 `/aggregate`、可由 `/api/partner/<name>` 访问、带独立池 + 指标 + 健康检查。

## 实测（catalog 8081 + order 8091 在跑，secondary 8082/8092 故意 down）

- `/actuator/beans` 里 `catalog*` / `order*` 各 8 个真实 bean，类型正确。
- `/aggregate` 返回两 cluster；`/catalog`、`/order` → 200；未知 cluster → 404。
- `[LB-HEALTH]` 探测路径取自 cluster 配置（`/api/catalog/ping`、`/api/order/ping`）；8081/8091 `alive=true`，
  8082/8092 `probe failed` 异常被打印（不再被 `catch (Exception ignored)` 吞）。
- `[LB-AUDIT][summary]` 显示解析后的 `localhost:8081/8091` —— 证明 `clone()` 保留了 LB 拦截器、audit 在最内层。
- 池指标 `httpclient` tag 同时有 `creed-partner-{catalog,order}-{aggregate,health}` 四个。

# LoadBalancerAuditInterceptor 顺序控制

## 核心规则：靠"添加顺序"，不是 @Order

RestClient / RestTemplate 的 `ClientHttpRequestInterceptor` **按它们在 List 里的顺序执行，不会按 `@Order`/`Ordered` 排序**（`DefaultRestClientBuilder` 没有任何 OrderComparator/sort 逻辑）。所以给 interceptor 加 `@Order` 是**无效**的。

执行模型是"洋葱"嵌套——List 里**第一个 = 最外层**：

```
list = [A, B, C]
进入： A → B → C → 实际 HTTP 请求
返回： C → B → A
```

每个 interceptor 调 `execution.execute(...)` 才进入下一个。

## 两种控制方式

1. 按调用顺序追加（最常用）：

```java
RestClient.builder()
    .requestInterceptor(a)   // 最外层
    .requestInterceptor(b)
    .requestInterceptor(c)   // 最内层（最贴近真实请求）
    .build();
```

2. 精确插位 / 重排：`requestInterceptors(Consumer<List>)`：

```java
.requestInterceptors(list -> {
    list.add(0, audit);     // 插到最前 → 最外层
    list.add(metrics);      // 追加到最后 → 最内层
    // 也可以 list.sort(...) 自己按 Ordered 排
})
```

（RestTemplate 等价物是 `setInterceptors(List)` / `getInterceptors()`，同样是 List 顺序。）

## 关键坑：@LoadBalanced 的时机

`@LoadBalanced` 的后置处理器（`LoadBalancerRestClientBuilderBeanPostProcessor`）会在你的 `@Bean` 方法返回之后，**把 LB 拦截器 append 到 List 末尾**。所以"在哪里加"决定了你相对 LB 拦截器是内还是外：

```java
// ① 在 @LoadBalanced 的 builder bean 里加 → 排在 LB 之前 → 比 LB 更外层
@Bean @LoadBalanced
RestClient.Builder b(...) {
    return RestClient.builder().requestInterceptor(x);   // [x] → 后置处理器 → [x, LB]
}

// ② 拿到已被后置处理的 builder 再加 → 排在 LB 之后 → 比 LB 更内层
@Bean
RestClient partnerRestClient(@LoadBalanced RestClient.Builder b, LoadBalancerAuditInterceptor audit) {
    return b.requestInterceptor(audit).build();          // [x, LB, audit]
}
```

链路：`x（外）→ LB（解析 service→实例）→ audit（内，看到 https://localhost:8091）→ 真实请求`

这正是 `LoadBalancerAuditInterceptor` 能打印**解析后 host:port** 的原因——它必须排在 LB 之后（最内层）。如果放进 ①，它只会看到 `https://order-resource`（解析前）。

> 现状：动态注册后 `<name>RestClient` 不再是上面 ② 那样的静态 `@Bean`，而是 `partnerRestClientBuilder.clone().requestFactory(...).requestInterceptor(audit).build()`。`clone()` 拿到的是**已被后置处理**的 builder（已含 LB 拦截器），所以 append audit 仍是 `[LB, audit]`，顺序与 ② 完全等价。

## 多个 interceptor 的实战建议

| 想要的行为 | 放哪 |
|---|---|
| 看**解析前**的逻辑 URL、加鉴权头、改 service 级 header | LB 之前（在 builder bean 里加） |
| 看**解析后**真实实例、计时、状态码、重试 | LB 之后（在 build 那步加） |
| 完全自定义顺序 | `requestInterceptors(list -> ...)` 手动插位 |

> 注意区分：`@Order` 对 **`LoadBalancerRequestTransformer`** 是有效的（它有 `DEFAULT_ORDER`，框架会排序），但对 **RestClient 的 `ClientHttpRequestInterceptor`** 无效。两者别混。
