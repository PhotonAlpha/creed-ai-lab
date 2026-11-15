# LoadBalancer 健康检查：实例全部健康，`get()` 却拿不到完整列表

适用于 Spring Cloud LoadBalancer 5.0.3（`spring-cloud-commons` 同版本）；4.2.1 / 4.3.2 / 5.0.1 的相关源码相同。
本模块和 `creed-gateway-partner` 用的是同一条链：discovery → `HealthCheckServiceInstanceListSupplier` → `withCaching()`，
两个模块都受影响。

## 现象

16 个实例，健康检查全部通过，负载均衡却经常只在其中几个（甚至 1 个）之间轮询。payment 的 sticky 路由会打
`[LB-STICKY] no alive instance with metadata.stickyId=... — falling back`，请求被转到另一个实例，而被钉住的实例是健康的。

## 原因：两个默认行为叠加

1. **`health-check.update-results-list` 默认 `true`：一轮检查是边查边发的。** `healthCheckFlux` 每轮从一个空列表开始，
   每有一个实例返回健康就把列表再发一次：`[1]`、`[1,2]`……`[1..16]`。外层 `replay(1)` 只保留最后一次发出的值，所以在这一轮
   的 16 个探活全部返回之前，`get()` 拿到的是 1～15 个。每隔 `interval` 重新开始一轮，每轮都会重现。
2. **`withCaching()` 把第一次发出的值存 35 秒。** `CachingServiceInstanceListSupplier` 在缓存未命中时取的是
   `delegate.get().take(1)`，也就是**第一个**值，然后保留到 TTL（默认 35 s）。缓存未命中恰好落在一轮的开头，接下来
   35 秒里负载均衡就只有 1 个或几个实例。

Spring 文档本身也建议：用了健康检查就不要再叠缓存（健康检查的 `replay(1)` 本身就是缓存）。

## 对 sticky（payment-resource）的影响

`StickyMetadataServiceInstanceListSupplier` 叠在缓存外面，过滤的就是上面那个列表。被钉住的实例不在部分列表里 →
匹配为空 → 按约定回退到全部存活实例（WARN）→ 请求落到另一个实例。payment 只有两个实例，缓存第一次拿到的往往
只有一个，于是 35 秒内钉在另一个实例上的请求全部被转走。这不是实例宕机，是列表不完整。

## 慢探活：`interval` 同时是探活超时，但阻塞写法下不生效

- `interval` 有两个用途：两轮之间的间隔（`repeatWhen(fixedBackoff(interval))`），以及单个探活的超时
  （`.timeout(interval, Mono.empty())`）。没有单独的探活超时配置。
- 周期 = 一轮耗时 + `interval`：`repeatWhen` 等一轮结束后才开始计时。
- **本模块的探活是阻塞的**（`loggingAliveFunction`，和 Spring 的 `withBlockingHealthChecks` 一样是 `Mono.defer` 里同步调
  `RestClient`）。阻塞发生在订阅时，timeout 的计时器在 `onSubscribe` 之后才启动，所以 **timeout 不生效**；
  `Flux.merge` 逐个订阅，**探活是串行的**，一轮耗时 = 所有探活之和；而且它占的是 Reactor 的 `parallel` 线程（按 CPU 核数）。
- 真正限制探活耗时的是 HTTP 超时：`creed.partner.health-check.http.*` 全是 2s。规则：**实例数 × HTTP 超时 < `interval`**，
  不要靠 Spring 的那个 timeout。

`update-results-list=false` 时（16 个实例，`interval` 60s，其中一个实例的探活要 61s；按 1:60 缩小实测）：

| | 非阻塞探活 | 阻塞探活（本模块写法，HTTP 超时 ≥ 61s） | 阻塞探活（本模块现状，HTTP 超时 2s） |
|---|---|---|---|
| 探活执行 | 16 个并发 | 串行 | 串行 |
| `timeout(interval)` | 生效，60s 截断 | 不生效 | 不生效，HTTP 2s 兜底 |
| 慢实例的判定 | DOWN | UP | DOWN（WARN `[LB-HEALTH] ... probe failed`） |
| 周期（一轮 + `interval`） | 约 120s | ≥ 121s | 约 62s |
| 启动后第一次拿到列表 | 第 60s | 第 61s 以后 | 约第 2s |
| 拿到列表前 `get()` | 一直等，请求卡在负载均衡 | 同左，更久 | 同左，约 2s |
| 一轮进行中 `get()` | 上一轮的完整结果 | 同左 | 同左 |
| 每次拿到的列表 | 15/16，稳定 | 16/16（慢实例也收流量） | 15/16，稳定 |
| 实例宕机到被摘除最长 | 约 180s | 约 182s+ | 约 64s |

`false` 只解决"部分列表"；代价是首次和每次更新都要等最慢的那个探活，所以探活必须有远小于 `interval` 的硬超时。

## 按 cluster 设置，以及"不继承"的坑

可以只给某个 cluster 设：健康检查在构造时用 `LoadBalancerClientFactory.getProperties(serviceId)` 读自己的配置，
`spring.cloud.loadbalancer.clients.<service-id>.*` 优先。

**但 `clients.<id>` 不继承全局配置。** `getProperties` 的源码注释说 "specifics are overlayed on top of defaults"，
实际 `LoadBalancerClientsProperties` 只是一个 `Map<String, LoadBalancerProperties>`，每个 client 绑定成一个**全新的**
`LoadBalancerProperties`：没写的项是**库的默认值**，不是全局值。实测只写 `update-results-list: false`，该 cluster 的
`interval` 从全局的 1m 变成了 25s。`initial-delay`、`path`、retry、hint、`sticky-session.*` 一样。

所以：**一个 cluster 一旦出现在 `clients` 下，它需要的每一项全局 `spring.cloud.loadbalancer.*` 都要重复写一遍。**

```yaml
spring:
  cloud:
    loadbalancer:
      health-check:
        interval: 1m                  # 全局；其他 cluster 照旧（update-results-list 默认 true）
      clients:
        payment-resource:
          health-check:
            interval: 1m              # 必须重复，否则 25s
            update-results-list: false
```

和 sticky 的关系：本模块的 sticky（cookie 名、metadata key）是代码里的常量，不读 `LoadBalancerProperties`，所以这个坑
**不会**直接改变它的判断。但如果改用 Spring 自带的 sticky（`sticky-session.instance-cookie-name`，默认 `sc-lb-instance-id`），
或者依赖 retry / hint 配置，给这个 cluster 写一个 `clients` 块就会把它们悄悄重置成默认值。

## 修复方向

1. `update-results-list: false`（全局，或按 cluster 并重复全局配置）——每轮只发一次完整列表。
2. 去掉健康检查外面的 `.withCaching()`（`PartnerLoadBalancerConfiguration.healthCheckedSupplier`）。缓存是代码里的，
   不能按 cluster 配置关掉；设了 `false` 的 cluster 缓存到的是完整列表，只是最多旧 35 秒。
3. 探活加 `.subscribeOn(Schedulers.boundedElastic())`：探活恢复并发、timeout 生效、不再占 `parallel` 线程。

## 测试

- `HealthCheckPartialListTest`：用本模块的 `LoggingHealthCheckServiceInstanceListSupplier` + Spring 的缓存层复现，
  日志前缀 `[LB-PARTIAL]`。默认配置下缓存第一次拿到 1/16、之后一直是 1/16，健康检查每轮从 1 开始重新累加；
  `false` 时每次 16/16；sticky 请求钉在健康的 p2 上却被转到 p1。**它锁住的是现在的错误行为**，修复后要改成断言完整列表。
- `PerClusterHealthCheckPropertiesTest`：`clients.<id>` 不继承全局配置（`interval` 变成 25s），以及重复写全局配置后只影响
  那一个 cluster。Spring Cloud 升级后如果真的加上了继承，第一个用例会失败提醒。
