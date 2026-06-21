拆分后的结构（PartnerClientConfiguration）



|  池 | 用途  | RestClient  | 指标 tag httpclient=  | 默认 max-total  |
|---|---|---|---|---|
|  aggregateHttpConnectionManager |  业务聚合调用（带 audit） |  partnerRestClient | creed-partner-aggregate  | 50  |
|  healthCheckHttpConnectionManager |  LB 健康检查     | healthCheckRestClient  |  creed-partner-health |  10  |


- 业务和健康检查流量完全隔离：健康检查的连接不再占用/污染业务池的统计。
- 重复逻辑抽成了私有 helper（connectionManager(...)、requestFactory(...)），两个池共用，避免复制粘贴。
- 每个池用 Micrometer 内置的 PoolingHttpClientConnectionManagerMetricsBinder 暴露指标 httpcomponents.httpclient.pool.*（total.max / total.connections[state=available|leased] / total.pending / route.max.default），池名作为 httpclient tag 值区分两个池。
- 只有聚合工厂用 BufferingClientHttpRequestFactory（audit 需要重复读 body）；健康检查工厂不需要 buffer。

可调参数

- 聚合：creed.partner.http.*（max-total=50、max-per-route=20、connect=5s、socket=10s、connection-request=3s、response=10s）
- 健康检查：creed.partner.health-check.http.*（max-total=10、max-per-route=5、connect=2s、socket=2s、connection-request=2s、response=2s）—— 健康检查给了更短超时、更小池。

实测

- 启动正常，无 NoUniqueBean 冲突（用 @Qualifier 按名注入两套工厂/池）。
- 跑 3 次聚合 + 等一个健康检查周期后查 actuator：
    - httpcomponents.httpclient.pool.total.max 的 httpclient tag 同时有 creed-partner-aggregate 和 creed-partner-health；
    - aggregate max=50、health max=10（确认两套独立配置生效）；
    - 两池的 total.connections（state=available）都 >0（连接各自复用）。

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

## 多个 interceptor 的实战建议

| 想要的行为 | 放哪 |
|---|---|
| 看**解析前**的逻辑 URL、加鉴权头、改 service 级 header | LB 之前（在 builder bean 里加） |
| 看**解析后**真实实例、计时、状态码、重试 | LB 之后（在 build 那步加） |
| 完全自定义顺序 | `requestInterceptors(list -> ...)` 手动插位 |

> 注意区分：`@Order` 对 **`LoadBalancerRequestTransformer`** 是有效的（它有 `DEFAULT_ORDER`，框架会排序），但对 **RestClient 的 `ClientHttpRequestInterceptor`** 无效。两者别混。
