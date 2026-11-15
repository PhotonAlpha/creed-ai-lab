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

### 本模块的阻塞探活：`true` 与 `false` 的时间线

场景是本模块现状：`loggingAliveFunction`（阻塞），16 个实例，正常探活约 50ms；i5 卡住，到 HTTP 超时 2s 被判 DOWN；
`interval` 60s，缓存 TTL 35s。两种设置下探活都在同一个 `parallel` 线程上**串行**，i5 的 2s 会挡住它后面的所有实例：
一轮约 15 × 50ms + 2s ≈ 2.75s，周期约 62.75s。（实测脚本把 `interval` 缩成 3s，时间线结构与 `interval` 无关。）

**`update-results-list = true`（默认）**

| 时间 | `get()` | 缓存 | 说明 |
|---|---|---|---|
| 0.05s | 1/16 | **1/16，保留 35s** | i1 返回就发出；缓存未命中时 `take(1)` 拿到的就是它 |
| 0.05 ~ 0.2s | 1 → 4 | 1/16 | i2 ~ i4 逐个加入 |
| **0.2 ~ 2.2s** | **一直 4/16** | 1/16 | i5 卡住，后面 11 个健康实例排队 |
| 2.2s | 4/16 | 1/16 | i5 超时，判 DOWN |
| 2.25 ~ 2.95s | 5 → 15 | 1/16 | 其余实例逐个加入 |
| 2.95 ~ 35s | 15/16 | **仍 1/16** | 缓存没过期，负载均衡只看到 1 个实例 |
| 35 ~ 62.95s | 15/16 | 15/16 | 缓存过期后重新取到完整列表 |
| **62.95s**（第二轮） | 15/16 → **1/16** | 15/16 | 新一轮从空列表开始，i1 一返回列表就缩成 1 个 |
| 63 ~ 65.7s | 1 → 4，**卡在 4 两秒**，再到 15 | 看时机 | 每个周期重复一次约 2.75s 的不完整窗口 |

缓存如果恰好在某个不完整窗口里过期并被重新读取，会再锁住一份不完整的列表 35s。

**`update-results-list = false`**

| 时间 | `get()` | 缓存 | 说明 |
|---|---|---|---|
| **0 ~ 2.95s** | **没有值，调用一直等** | 无 | 一轮没跑完就不发出；启动后这段时间的请求卡在负载均衡（实测 3.0s） |
| 2.2s | 仍在等 | 无 | i5 超时，判 DOWN |
| 2.95s | 15/16 | 15/16 | 一次性发出完整结果 |
| 2.95 ~ 62.95s | 15/16 | 15/16 | 稳定 |
| 62.95 ~ 65.7s（第二轮进行中） | **仍 15/16** | 15/16 | 新一轮跑完前一直沿用上一轮的结果 |
| 65.7s | 15/16（新结果） | 缓存过期后更新 | 实例状态变化要到一轮结束才生效，最多晚约 2.75s |

**对比**

| | `true` | `false` |
|---|---|---|
| 启动后第一个可用列表 | 0.05s，只有 1 个实例 | 2.95s，完整列表 |
| 启动期间的请求 | 立即处理，但全打到少数几个实例 | 要等约 3s |
| 每个周期 | 约 2.75s 只有 1 ~ 14 个实例，其中 2s 卡在 4 个 | 始终完整 |
| 叠加缓存 | 可能把 1 个实例的列表锁住 35s | 缓存到的总是完整列表 |
| sticky（payment） | 不完整窗口里钉住的实例可能不在列表中，请求被转走 | 只有实例真的 DOWN 才 fallback |
| 慢实例的影响 | 卡在它后面的健康实例晚 2s 才进列表 | 整个列表晚 2s 才发出 |

阻塞探活下 `true` 唯一的好处是启动时快约 3s；`false` 只是启动时多等一轮（实例数 × 单次耗时，最坏 16 × 2s = 32s）。
再给探活加 `subscribeOn(boundedElastic())` 让它们并发，一轮就从"所有探活之和"缩到"最慢的那一个"（约 2s）。

#### 去掉缓存之后

链路变成 discovery → 健康检查 →（payment 再叠 sticky）。上面的 `get()` 列本来就是直接读健康检查的结果，不经过缓存，
所以数据不用重测。去掉缓存没有额外开销：健康检查在 `afterPropertiesSet()` 时就建立了常驻订阅，`get()` 只是读
`replay(1)` 里的最新值，不会多发探活，也不会重新查 discovery。

**`update-results-list = true`，无缓存**

| 时间 | `get()` | 说明 |
|---|---|---|
| 0.05s | 1/16 | i1 返回就发出 |
| 0.05 ~ 0.2s | 1 → 4 | i2 ~ i4 逐个加入 |
| **0.2 ~ 2.2s** | **一直 4/16** | i5 卡住，后面 11 个健康实例排队 |
| 2.2s | 4/16 | i5 超时，判 DOWN |
| 2.25 ~ 2.95s | 5 → 15 | 其余实例逐个加入 |
| 2.95 ~ 62.95s | 15/16 | 稳定；有缓存时 35s 前一直停在 1/16，现在没有了 |
| **62.95s**（第二轮） | 15/16 → **1/16** | 新一轮从空列表开始，i1 一返回列表就缩成 1 个 |
| 63 ~ 65.7s | 1 → 4，**卡在 4 两秒**，再到 15 | 每个周期重复一次约 2.75s 的不完整窗口 |

**`update-results-list = false`，无缓存**

| 时间 | `get()` | 说明 |
|---|---|---|
| **0 ~ 2.95s** | **没有值，调用一直等** | 启动后这段时间的请求卡在负载均衡 |
| 2.2s | 仍在等 | i5 超时，判 DOWN |
| 2.95s | 15/16 | 一次性发出完整结果 |
| 2.95 ~ 62.95s | 15/16 | 稳定 |
| 62.95 ~ 65.7s（第二轮进行中） | **仍 15/16** | 新一轮跑完前沿用上一轮的结果 |
| 65.7s | 15/16（新结果） | 状态变化在一轮结束时就生效，不用等缓存过期 |

**四种组合对比**

| | `true` + 缓存 | `true`，无缓存 | `false` + 缓存 | `false`，无缓存 |
|---|---|---|---|---|
| 启动后第一个可用列表 | 0.05s，1 个实例 | 0.05s，1 个实例 | 2.95s，完整 | 2.95s，完整 |
| 启动后 3 ~ 35s | **仍 1/16**（缓存锁住） | 15/16 | 15/16 | 15/16 |
| 每个周期的不完整窗口 | 约 2.75s，可能被缓存放大到 35s | **约 2.75s**（1 ~ 14 个，其中 2s 卡在 4 个） | 无 | 无 |
| 实例状态变化多久生效 | 一轮结束 + 最多 35s | 逐个生效 | 一轮结束 + 最多 35s | **一轮结束（≤ 2.75s）** |
| sticky（payment） | 最长 35s 被转走 | 每个周期约 2.75s 可能被转走 | 只有实例真的 DOWN 才 fallback | 只有实例真的 DOWN 才 fallback |

- 只去掉缓存：消除 35s 锁定和状态变化的延迟，但 `true` 每个周期仍有约 2.75s 的不完整窗口，sticky 请求仍可能被转走。
- `false` + 去掉缓存最干净：除了启动时等一轮，`get()` 始终是完整列表，状态变化一轮结束就生效。
- 再加 `subscribeOn(boundedElastic())`，一轮缩到最慢的那一个（约 2s），启动等待和生效延迟都跟着缩短。

实测脚本：`tmp/lb-src/BlockingTimelineDemo.java`（不在仓库里；结构同 `HealthCheckPartialListTest`，探活换成
`Mono.defer` 里 `Thread.sleep`）。

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
