# 负载均衡 RestClient：批量配置与连接池指标命名

> 2026-11-15。相关代码：`lb/NamedRestClientRegistrar.java`（按配置注册）、`lb/PoolMetricNames.java`（命名规则）、
> `lb/LoadBalancedRestClientConfiguration.java`（默认的 `clusterPool` / `healthCheckPool`）、
> `lb/HttpPoolProperties.layered`（具名块叠在默认块之上）。测试：`NamedRestClientRegistrarTest`。
> camel-http 那一侧的同构实现见 `camel-http-per-endpoint-pools.md`。

## 1. 问题

`LoadBalancedRestClientConfiguration` 里每个连接池的 `metricsName`（Prometheus 的 `httpclient` 标签）都是手写的
字符串：`"loadBalancedPool"`、`"healthCheckPool"`。再加一个池就要再想一个名字、再写一遍，名字不统一，
dashboard 和 PromQL 也就对不上。

## 2. 命名规则：`PoolMetricNames`

| 池 | 声明位置 | `httpclient` 标签 |
|---|---|---|
| `clusterPool` | `creed.partner.http` | `loadBalancedPool` |
| `<name>Pool` | `creed.partner.rest-clients.<name>` | `loadBalancedPool-<name>` |
| `healthCheckPool` | `creed.partner.health-check.http` | `healthCheckPool` |
| `camelHttpConnectionManager` | `creed.camel.http` | `camelHttpPool` |
| `<name>ConnectionManager` | `creed.camel.http-components.<name>` | `camelHttpPool-<name>` |

- 每类池有一个**基础名**（`LOAD_BALANCED` / `HEALTH_CHECK` / `CAMEL_HTTP`）。默认池就用基础名
  （`PoolMetricNames.of(base)`），批量声明的池是 `<基础名>-<name>`（`PoolMetricNames.of(base, name)`）。
- 三个基础名和以前手写的完全相同，**现有 dashboard 和 PromQL 不用改**。
- 代码里不再出现指标名字符串。以后加新的一类池，在 `PoolMetricNames` 里加一个基础名即可。

## 3. 批量配置

```yaml
creed:
  partner:
    http:                       # 默认业务池 clusterPool，也是每个具名块的兜底
      max-total: 50
      max-per-route: 20
    rest-clients:
      catalog-bulk:             # 名字：小写字母开头，只含小写字母、数字、'-'
        max-total: 10
        response-timeout: 60s   # 只写和 creed.partner.http 不同的键
      order-report:
        max-per-route: 4
```

每个名字注册两个 bean（以 `catalog-bulk` 为例）：

| bean | 内容 | 生命周期 |
|---|---|---|
| `catalog-bulkPool` | `ManagedHttpClientPool`：mTLS 连接池、request factory、空闲清理；指标 `loadBalancedPool-catalog-bulk` | Spring `close()`；它本身是 `MeterBinder`，Boot 自动绑定 |
| `catalog-bulkRestClient` | 克隆 `@LoadBalanced` 的 `clusterRestClientBuilder`，用上面的池，末尾加 `LoadBalancerAuditInterceptor` | — |

使用：

```java
public BulkFetcher(@Qualifier("catalog-bulkRestClient") RestClient client) { … }
client.get().uri("https://catalog-resource/api/catalog/bulk").retrieve().body(JsonNode.class);
```

- 键与 `HttpPoolProperties` 相同。**未写的键取 `creed.partner.http`**，再没有才用 record 默认值。
- mTLS 用同一个 `creed.partner.client-bundle`。负载均衡、健康检查、sticky、`http.client.requests` 观测，
  与 `clusterRestClient` 完全相同。
- 健康检查池（`healthCheckPool`）只有一个，不做批量：它服务的是 LoadBalancer 本身，不属于某个业务调用。

## 4. 源码说明

### 4.1 `NamedRestClientRegistrar`

和 `NamedHttpComponentRegistrar` 同一个结构（`static @Bean` 的 `BeanDefinitionRegistryPostProcessor`，
原因见 `camel-http-per-endpoint-pools.md` §10.1）：

1. `names(environment)`：用 `Binder` 读 `creed.partner.rest-clients` 的键，名字不符合 `[a-z][a-z0-9-]*`
   就启动失败。这个名字既是 bean 名的一部分，也是指标标签的一部分。
2. `<name>Pool` 或 `<name>RestClient` 和已有 bean 重名，启动失败。
3. `HttpPoolProperties.layered(env, "creed.partner.rest-clients.<name>", "creed.partner.http")` 在注册时就绑好属性。
4. `<name>Pool` 的 supplier 调 `RestClientSuppliers.pool(..., PoolMetricNames.of(LOAD_BALANCED, name))`，
   和 `clusterPool` 是同一个模板。`destroyMethod="close"` 会停掉 evictor、关闭连接池。
5. `<name>RestClient` 的 supplier **克隆** `clusterRestClientBuilder`。LoadBalancer 的 post-processor 已经把
   负载均衡拦截器加在了这个 builder 上，克隆就带着它，所以它排在最外层。然后换成自己的 request factory，
   最后追加审计拦截器，让它在最内层、看到已解析的 `host:port`。

### 4.2 为什么 `clusterRestClient` 也改成了 `clone()`

`RestClient.Builder` 是可变对象。原来 `clusterRestClient` 直接在共享的 `clusterRestClientBuilder` 上
`requestInterceptor(audit)`，等于把审计拦截器**加进了共享 builder**。具名客户端克隆这个 builder 时，就会多带一个
审计拦截器，而且先后顺序取决于 bean 的创建顺序。两边都先 `clone()`，共享 builder 上始终只有负载均衡拦截器，
测试 `theClientsCloneTheLoadBalancedBuilderAndAuditInnermost` 锁定这一点。

### 4.3 `HttpPoolProperties.layered`

原来是 `CamelHttpComponents.named` 的私有实现，现在两个 registrar 共用：把"具名前缀"和"默认前缀"两组
`ConfigurationPropertySource` 按顺序叠在一起，再整体绑定一次。先找到的键生效，两边都没有的用 record 上的
`@DefaultValue`。

## 5. 验证

`NamedRestClientRegistrarTest`（`ApplicationContextRunner`，SSL bundle 不存在，退回非 mTLS 的池）：

- 两个名字各有 `<name>Pool` 和 `<name>RestClient`；指标的 `httpclient` 标签分别是
  `loadBalancedPool-catalog-bulk`、`loadBalancedPool-order-report`；具名键生效，没写的键取 `creed.partner.http`；
- 每个客户端的拦截器依次是 [负载均衡, 审计]；共享 builder 上仍然只有负载均衡拦截器；
- 名字不合法（`Catalog_Bulk`）时启动失败，消息里带配置键；
- 默认池的名字没有变（`loadBalancedPool` / `healthCheckPool`）。

没在运行中的应用上验证过：当前 `application.yml` 里没有声明任何 `rest-clients`，所以不会多出任何 bean。
