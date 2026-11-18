# resilience4j 熔断器 + 舱壁 × LoadBalancer 健康检查：按 cluster 熔断、配置项的作用与风险

> 版本：Spring Boot 4.1.1 · Spring Cloud 2025.1.3（LoadBalancer 5.0.3、CircuitBreaker 5.0.3）· resilience4j **2.3.0**
> （由 Spring Cloud BOM 决定）。下文的数字都来自真实代码：要么是 resilience4j / Spring Cloud 的源码，要么是测试跑出来的结果：
> `CircuitBreakerScenarioTest`（虚拟时间，真实的健康检查 supplier + 真实的 `CircuitBreaker`）和
> `LbHealthApplicationTest`（整个应用在本机回环上跑，用的就是 `application.yml` 绑定出来的配置）。
>
> 2026-11-18 修订：配置从 `instances.backendA / backendB` 换成 `configs.default`；熔断器和舱壁改为**按 cluster** 划分；
> 新增 `DownstreamCircuitBreakerFailurePredicate`；新增 bulkhead 测试用的第二个 cluster。第 3 节的数字全部按新配置重跑过。
>
> 2026-11-19 修订：`bulkhead.instances.lb-health-bulkhead.max-wait-duration` 从 0 改成 **60s**。舱壁满时不再立刻拒绝，
> 而是让调用排队等最多 60 秒。§0、§1、§4.1、§4.4、§4.5 按新值重写，实测数据重跑过。
> 同日：`bulkhead.configs.default.max-concurrent-calls` 从 100 改成 **20**（R8 随之解除）；加上了 **actuator**（§1.1），
> `register-health-indicator` 和 `event-consumer-buffer-size` 因此生效。
> 同日：试过 resilience4j 注解的默认顺序 `CircuitBreaker( Bulkhead( call ) )`（舱壁在里面），实测问题后**改回
> `Bulkhead( CircuitBreaker( call ) )`**（舱壁在外面）。两种顺序的对比和实测数据在 §4.4。

## 0. 结论

问题：`interval: 60s`、`refetch-instances-interval: 180s`，0 秒健康检查成功，1 秒整个 cluster 宕机，后端仍在频繁调用下游。

1. **负载均衡这一侧，1 ~ 60 秒之间什么都不会变。** 0 秒那一轮的结果（10 个实例都健康）会一直被 `get()` 交出去，
   直到下一轮在 60 秒跑完。没有熔断器时，20 次/秒的流量在这 59 秒里有 **1180 次**全部打到已宕机的实例上。
2. **熔断器（COUNT_BASED 200，阈值 60%）**：窗口里是最近 200 次调用，宕机前全是成功。要等失败占到 60%，也就是
   **120 次失败**，熔断器才打开：20 次/秒时在 **6.95 秒**，5 次/秒时在 **24.80 秒**。窗口按次数算，跟速率无关。
3. **OPEN 只有 15 秒，比健康检查的 60 秒短**：第一次半开试探在 22 秒，这时负载均衡还拿着 0 秒那份旧列表，
   10 次试探全部打到宕机实例上。60 秒之前有 3 轮这样的试探，**下游一共挨了 150 次失败**（120 次是打开前的，
   30 次是试探）。没有熔断器时是 1180 次。
4. **只挂 1 个或 2 个实例（10 个里）**：失败率 10% / 20%，远低于 60%，**熔断器不会打开**（§3.4）。
   60 秒之前，下游分别挨了 118 次和 236 次失败，只能靠负载均衡把它们摘掉。熔断器按 cluster 统计，不按实例：
   要 6 个实例同时挂才会打开，而且一打开，连剩下 4 个健康实例的流量也一起拒绝。
5. **90 秒服务恢复**：负载均衡在 120 秒那一轮把实例补回来，熔断器在 **130.50 秒**的下一轮试探时放行
   （比负载均衡晚 10.5 秒）。晚多少取决于半开时机和健康检查轮次怎么对齐：新配置最多晚约 15.5 秒，
   旧的 60 秒 OPEN 最多晚约 60 秒（上次恰好只晚 4 秒）。
6. **每个 cluster 有自己的熔断器和舱壁**。`lb-health-target` 全部宕机、熔断器打开时，`lb-health-bulkhead`
   的熔断器仍是 CLOSED，照常调用（`LbHealthApplicationTest.aDownClusterOpensOnlyItsOwnBreaker`）。
7. **舱壁（bulkhead，`max-wait-duration: 60s`）**：`lb-health-bulkhead` 每次调用要 1 秒，每秒并发 40 次，持续 3 秒。
   舱壁一次只放 10 个进去，其余的**排队等**，而不是被拒绝。120 次最后全部成功，但每秒只能完成 10 次，
   所以要 13 秒才跑完，最后一批调用在舱壁前等了约 9 秒。同时到达下游的调用**从来没超过 10 个**。
   舱壁在熔断器外面，所以排队的时间**不算**进熔断器统计的调用耗时，被舱壁拒绝的调用也**不进**熔断器的窗口（§4.4）。
   代价是：过载持续下去时，排队的调用会占住线程，最多 60 秒（§4.5 R13）。
8. **配置里的 3 个风险最值得先处理**（详见 §4.5）：
   - 业务 `RestTemplate` 没有超时，下游挂住时 HALF_OPEN 会一直卡住，慢调用规则也不起作用；
   - 断路器打开前要攒的失败次数，在 30 次（窗口刚清空）到 120 次（窗口满了）之间浮动；
   - `record-failure-predicate` 返回 `false` 时，这次调用会被**记为成功**，而不是被忽略。

## 1. 这个模块里是怎么接的

| 文件 | 作用 |
|---|---|
| `downstream/DownstreamCaller` | 启动时为 `spring.cloud.discovery.client.simple.instances` 下的**每个 cluster** 各建一个 `CircuitBreaker` 和一个 `Bulkhead`（名字就是 cluster 名）。每次调用的结构是 `Bulkhead( CircuitBreaker( restTemplate.getForObject("http://<cluster>/api/lb-health/target/whoami") ) )`（舱壁在外，不是 resilience4j 注解的默认顺序） |
| `downstream/DownstreamCircuitBreakerFailurePredicate` | `record-failure-predicate`：决定哪些异常算 cluster 的失败（§4.3） |
| `downstream/LoadRun` | 持续调用一个 cluster，`rps` 次/秒，持续 `seconds` 秒，每秒一行。`concurrent=false`（默认）一次只发一个调用，`concurrent=true` 每个调用一个虚拟线程，不等上一个返回（测舱壁用） |
| `api/CircuitBreakerController` | `GET /cb/call?cluster=`、`GET /cb`（每个 cluster 的熔断器 + 舱壁的状态和实际生效的配置）、`POST /cb/{cluster}/reset`、`POST\|GET\|DELETE /cb/load` |
| `upstream/TargetConnectorsConfiguration` | 每个 cluster 里 `localhost` 实例的端口都开一个 Tomcat connector，所以本应用本身就是这 13 个实例 |
| `upstream/TargetController#whoami` | 业务接口跟随实例状态：`up=false` → 503，`delay=2s` → 晚 2 秒才回答 |

两个 cluster：

| cluster | 实例 | 负载均衡 | 熔断器 | 舱壁 |
|---|---|---|---|---|
| `lb-health-target` | 10 个（18100–18109） | 本模块的健康检查 supplier（`@LoadBalancerClient`） | `configs.default` | `configs.default`：**20** |
| `lb-health-bulkhead` | 3 个（18110–18112） | Spring 默认的负载均衡，**不做健康检查** | `configs.default` | `instances.lb-health-bulkhead`：**10**，`max-wait-duration: 60s`（排队） |

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 20
    instances:
      lb-health-bulkhead:            # 测舱壁用：故意设小
        max-concurrent-calls: 10
        max-wait-duration: 60s       # 舱壁满时排队等最多 60 秒，而不是立刻拒绝
  circuitbreaker:
    circuit-breaker-aspect-order: 1
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 200
        minimum-number-of-calls: 50
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
        permitted-number-of-calls-in-half-open-state: 10
        automatic-transition-from-open-to-half-open-enabled: true
        record-failure-predicate: com.creed.resource.lbhealth.downstream.DownstreamCircuitBreakerFailurePredicate
        event-consumer-buffer-size: 200
        register-health-indicator: true
        slow-call-rate-threshold: 100
        slow-call-duration-threshold: 60s
```

- **按 cluster 熔断是怎么来的**：resilience4j-spring-boot3 把 `configs.default` 设成 registry 的默认配置
  （`CircuitBreakerRegistry.of(configs)`）。`registry.circuitBreaker("lb-health-target")` 在没有
  `instances.lb-health-target` 时就用这份默认配置建一个熔断器。所以 cluster 不用一个个写进 `instances`，
  新增一个 cluster 也就自动多一个熔断器。想单独调某一个 cluster，就写 `instances.<cluster>`：没写的键继承
  `configs.default`。
- **熔断器在启动时就建好，不等第一次调用。** 这样 `GET /cb` 能一开始就列出全部 cluster，状态切换日志
  `[CB] <cluster> State transition from CLOSED to OPEN` 也在有流量之前挂上了。
- **不是 cluster 的名字一律 400，不会新建熔断器。** 否则 `registry.circuitBreaker(拼错的名字)` 会悄悄建出一个来。
- **直接用 resilience4j，不走 Spring Cloud 的 `CircuitBreakerFactory`**。后者默认会再套一层 `TimeLimiter`
  （1 秒超时、另起线程执行），会改变调用本身。
- **舱壁在熔断器外面**，和 resilience4j 注解的默认顺序相反。为什么这样选，见 §4.4。

### 1.1 actuator

在应用端口上，路径带 context path：`http://localhost:18099/api/lb-health/actuator/...`。

| 端点 | 内容 | 来源 |
|---|---|---|
| `/actuator/health` | 组件 `circuitBreakers`：每个 cluster 的熔断器状态、失败率、窗口调用数、被拒数、舱壁剩余名额 | **本模块的 `CircuitBreakersHealthIndicator`**（见下） |
| `/actuator/circuitbreakers`、`/circuitbreakers/{name}` | 每个熔断器的状态和指标 | resilience4j |
| `/actuator/circuitbreakerevents/{name}[/{type}]` | 最近的事件（成功、失败、忽略、被拒、状态切换），每个熔断器最多 `event-consumer-buffer-size` = 200 条 | resilience4j |
| `/actuator/bulkheads`、`/actuator/bulkheadevents/{name}` | 舱壁列表和事件（放行、拒绝、结束） | resilience4j |
| `/actuator/metrics/resilience4j.*` | `resilience4j.circuitbreaker.state / calls / failure.rate / slow.call.rate / not.permitted.calls / buffered.calls`、`resilience4j.bulkhead.available.concurrent.calls / max.allowed.concurrent.calls`，按 `name` 标签区分 cluster | resilience4j-micrometer |
| `/actuator/info`、`/actuator/metrics`、`/actuator/loggers` | Boot 自带 | Boot |

- **resilience4j 2.3.0 的端点、事件和指标在 Boot 4 上都能用；只有 health indicator 不行。**
  它实现的是 Boot 3 的 `org.springframework.boot.actuate.health.HealthIndicator`，Boot 4 把这个接口挪到了
  `org.springframework.boot.health.contributor`，那个自动配置什么都注册不了，health 里不会出现熔断器。
- **`CircuitBreakersHealthIndicator` 是替代品**，规则和原版一样：只列 `register-health-indicator: true` 的熔断器；
  熔断器 OPEN 时，只有该熔断器还配了 `allow-health-indicator-to-fail: true`，health 才变 DOWN。默认是 false，
  所以 OPEN 只显示在详情里，**本应用的 health 仍是 UP**（`LbHealthApplicationTest.theActuatorShowsEveryClustersBreakerAndAnOpenOneDoesNotTakeTheAppDown`，
  原因见 §4.5 R10）。
- 关掉了 Spring Cloud 的 discovery health indicator（`spring.cloud.discovery.client.health-indicator.enabled: false`）。
  它等一个 simple discovery 永远不会发的 `InstanceRegisteredEvent`，会一直显示 UNKNOWN "Discovery Client not initialized"。
- 18100–18112 这些目标实例端口也能访问到 `/actuator`，因为它们就是同一个应用。

## 2. resilience4j 在滑动窗口下的含义

### 2.1 状态机

```
            失败率/慢调用率 ≥ 阈值                    waitDurationInOpenState 到期
 CLOSED ─────────────────────────────▶ OPEN ───────────────────────────────────────────▶ HALF_OPEN
   ▲  (每记录一次调用就评估一次，                (拒绝一切调用，抛 CallNotPermittedException，    │
   │   前提：窗口里调用数 ≥ minimumNumberOfCalls)   不记录进任何窗口)                              │
   │                                                                                            │
   └──────────── permittedNumberOfCallsInHalfOpenState 次试探的失败率 < 阈值 ◀───────────────────┤
                                     失败率 ≥ 阈值 ─────────────────────────────▶ OPEN ◀────────┘
```

另有 `DISABLED`（永远放行、不统计）、`METRICS_ONLY`（只统计不熔断）和 `FORCED_OPEN`（永远拒绝），
这三种状态只能通过 API 手动切换。

### 2.2 一次调用如何进入窗口

resilience4j 2.3.0 `CircuitBreakerStateMachine.handleThrowable` 的判断顺序：

| 顺序 | 条件 | 窗口里记为 |
|---|---|---|
| 1 | 异常满足 `ignoreExceptions` / `ignore-exception-predicate` | **完全不计**：既不算成功也不算失败，许可被释放 |
| 2 | 异常满足 `recordExceptions` / `record-failure-predicate`（两者是"或"）。都没配时**所有异常都满足** | failure |
| 3 | 其他异常 | **success**（`recorded an exception as success`） |
| — | 正常返回（`recordResult` 判定为否，默认永远否） | success |
| — | 耗时 ≥ `slowCallDurationThreshold`。成功和失败都可能同时是慢调用 | 另算到 slow 里 |
| — | 状态是 OPEN，或 HALF_OPEN 的试探名额已经占满 | **不进窗口**，只累加 `numberOfNotPermittedCalls` |

- **第 3 行是本次配置最重要的一点**：自定义谓词一旦返回 `false`，这次调用就记为**成功**，而不是被忽略
  （`DownstreamCircuitBreakerFailurePredicateTest.falseIsRecordedAsASuccessOnlyIgnoreKeepsACallOutOfTheWindow`）。
- 记录发生在**调用结束时**。一次永远不结束的调用（没有超时、下游挂住）永远不会进窗口，见 §4.5 R1。

### 2.3 COUNT_BASED：最近 N 次调用

- `sliding-window-size: 200` 指的是 **200 次调用**。实现是一个长度为 N 的环形数组（`FixedSizeSlidingWindowMetrics`），
  新调用覆盖最老的那个，聚合值增量更新，评估是 O(1)。
- 与时间无关：一小时前的调用，只要后面还没有 200 次新调用把它挤出去，就仍在窗口里。
- 窗口满的时候（200 次全是成功）连续失败：第 120 次失败时窗口 = 80 成功 + 120 失败 = 60% → 打开。

### 2.4 TIME_BASED（本配置没用，作对比）

- `sliding-window-size` 改成表示 **N 秒**：N 个一秒的桶，按 epoch 秒切，到点整桶清零。
- 窗口里能放多少次调用取决于流量。`minimum-number-of-calls` 因此变成一个**最低流量**要求：
  例如 10 秒窗口、minimum 100，流量不到 10 次/秒时永远不会打开（旧文档 backendB 的结论）。

### 2.5 `minimumNumberOfCalls`：先攒够，再评估

- 窗口里调用数 < 这个值时，失败率返回 **-1**（`GET /cb` 里能看到），不评估，也不会打开。
- Spring 绑定用的是单独的 setter，**不会**把它截到窗口大小以内（`theMinimumIsAFloorNotAWindowAndOnlyTheFourArgBuilderCapsIt`）。
  本配置 50 < 200，没问题。
- 状态回到 CLOSED（或者 `reset`、应用重启）后，窗口是**新的、空的**。见 §4.2 的组合效应。

### 2.6 阈值与评估时机

- `failure-rate-threshold`：失败数 / 窗口调用数 × 100 **≥** 阈值就打开。源码用的是 `>=`，恰好 60% 也会打开。
- 只在 CLOSED 状态下、**每记录一次调用之后**评估一次。不会因为"时间过去了"而主动评估。

### 2.7 OPEN

- 持续 `wait-duration-in-open-state`（15 s）。期间所有调用立刻抛 `CallNotPermittedException`，
  不访问下游，也不进窗口。
- `automatic-transition-from-open-to-half-open-enabled: true`：到点由一个**真实时间**的调度线程切到 HALF_OPEN，
  不需要有调用。为 `false` 时，要等下一次调用到来、而且当前时间**严格晚于**截止时刻（`isAfter`）才切换。

### 2.8 HALF_OPEN

- 用一个**独立的 COUNT_BASED 窗口**，大小 = `permitted-number-of-calls-in-half-open-state`（10），minimum 也是 10。
  名额用完后，额外的调用被拒绝。
- 10 次都记录完才评估：失败率 ≥ 60%（≥ 6 次失败）→ OPEN，再等 15 秒；否则 → CLOSED，换一个空窗口。
- `max-wait-duration-in-half-open-state` 没有配置，默认 0，意思是**一直等**那 10 次试探结束。见 §4.5 R1。
- 试探调用也走负载均衡。负载均衡的列表要是还没更新，试探照样打到宕机实例上（§3.2）。

### 2.9 粒度：一个 cluster 一个熔断器

窗口统计的是这个 cluster 的**所有**调用，负载均衡把调用分到哪个实例，熔断器并不知道。一个实例挂掉、其他实例正常时，
失败率约等于 1 / 实例数。实例级别的隔离要靠负载均衡（健康检查、重试到下一个实例），不是熔断器。
cluster 之间是隔离的：每个 cluster 有自己的窗口、状态和舱壁。

## 3. 场景：interval 60 s、refetch 180 s，0 秒健康、1 秒宕机，持续调用（`CircuitBreakerScenarioTest`）

> 模拟用的就是 `configs.default` 的每一个值，只有 `automatic-transition` 例外：自动切换由一个**真实时间**的线程执行，
> 虚拟时间驱动不了它。持续有流量时，两者只差到下一次调用的间隔，20 次/秒时不超过 50 ms。
> 例如表里的 `22.00s OPEN -> HALF_OPEN`，自动切换的话应在 21.95 s。

### 3.1 负载均衡这一侧

| 时刻 | 发生的事 | `get()` 交出的列表 |
|---|---|---|
| 0 s | 一轮健康检查，10/10 存活 | 10 个 |
| 1 s | 整个 cluster 宕机 | **仍是 10 个**（上一轮的结果） |
| 60 s | 下一轮 | 0 个 |
| 120 s | refetch 和 interval 那一轮恰好重合 | 0 个（服务 90 秒恢复时：10 个） |

发现宕机的时间由 `interval` 决定，最长约 60 秒。refetch 只是重新读一遍 discovery。

### 3.2 全部宕机，20 次/秒

```
t=6.95s   CLOSED -> OPEN        第 120 次失败：窗口 = 80 成功 + 120 失败 = 60%
t=22.00s  OPEN -> HALF_OPEN     6.95 + 15
t=22.45s  HALF_OPEN -> OPEN     10 次试探 → 旧列表里 10 个宕机实例 → 10 次真实失败
t=37.50s / 37.95s               同上
t=53.00s / 53.45s               同上
t=68.50s / 68.95s               列表已空：10 次试探在负载均衡里本地失败（noInstance）
…之后每 15.5 秒一轮

t=1..5     CLOSED lb=10 failed=20
t=6        OPEN   lb=10 failed=20
t=7..21    OPEN   lb=10 rejected=20
t=22       OPEN   lb=10 failed=10 rejected=10     ← 试探打到宕机实例
t=60..67   OPEN   lb=0  rejected=20
t=68       OPEN   lb=0  noInstance=10 rejected=10
打到下游的失败调用：150（120 + 3 × 10）；本地失败：90；被拒：3740
```

### 3.3 全部宕机，5 次/秒

24.80 秒打开，还是 120 次失败，只是攒得慢。半开的 10 次试探要 2 秒（40.00 → 41.80）。打到下游的失败调用：140。

### 3.4 只挂一部分实例：1 个、2 个（以及开始熔断的分界）

前提和上面一样：20 次/秒，1 秒时宕机，负载均衡用轮询，60 秒那一轮健康检查把宕机的实例摘掉。
区别是只有一部分实例宕机，其余照常回答。

**挂 1 个（10 个里的 i03）**

```
t=1..59    CLOSED lb=10 ok=18 failed=2     ← 每 10 次调用有 1 次轮到 i03
t=60..199  CLOSED lb=9  ok=20 failed=0     ← 60 秒那一轮把 i03 摘掉，之后全部成功
打到下游的失败调用：118（2 次/秒 × 59 秒）
```

- 窗口里的失败率是 **10%**：200 次里有 20 次失败，离 60% 的阈值很远，熔断器一直是 CLOSED。
- 在 60 秒那一轮之前，每秒有 2 个用户请求失败。熔断器帮不上忙，减少这 118 次失败只能靠负载均衡。

**挂 2 个（i03、i07）**

```
t=1..59    CLOSED lb=10 ok=16 failed=4     ← 每 10 次有 2 次轮到宕机实例
t=60..199  CLOSED lb=8  ok=20 failed=0     ← 两个都在 60 秒那一轮被摘掉
打到下游的失败调用：236（4 次/秒 × 59 秒）
```

- 窗口里的失败率是 **20%**：200 次里 40 次失败，仍只有阈值的三分之一，熔断器还是不开。
- 失败次数正好是挂 1 个时的 2 倍。熔断器不开的时候，失败数只跟宕机实例的比例和负载均衡发现得多快有关。
- 5 次/秒时也一样不开，只是失败数按比例减少：59 次（1 次/秒）。

**开始熔断的分界**（`theThreshold_fiveOfTenNeverOpens_sixOfTenOpensAndBlocksTheHealthyFourToo`）

| 宕机实例数（共 10 个） | 窗口里的失败率 | 熔断器 | 打到下游的失败调用（1 ~ 200 秒） |
|---|---|---|---|
| 1 | 10% | 不开 | 118 |
| 2 | 20% | 不开 | 236 |
| 5 | 50% | 不开 | 590 |
| **6** | **60%** | **10.95 秒打开** | 138 |
| 10（全部） | 100% | 6.95 秒打开 | 150 |

- 规律是：宕机实例数 ÷ 实例总数 ≥ `failure-rate-threshold` 时，熔断器才会打开。对 10 个实例、阈值 60% 来说，要 **6 个**同时宕机。
- **挂 6 个时，熔断器连健康的 4 个也一起挡掉了**：60 ~ 72 秒之间，负载均衡已经只剩 4 个健康实例
  （`lb=4`），但熔断器还在 OPEN，每秒 20 次全部被拒。直到 72.50 秒那一轮半开试探全部成功，熔断器才关上。
  这是"按 cluster 熔断"本身的代价：熔断器只知道整个 cluster 的失败率，不知道里面有几个实例是好的。
- 挂 6 个比挂 5 个下游挨的失败反而少（138 对 590），因为熔断器开了。

### 3.5 服务在 90 秒恢复

| | 负载均衡列表补回 | 第一次成功 | 原因 |
|---|---|---|---|
| 没有熔断器 | 120 s | 120 s | 只等健康检查 |
| `configs.default` | 120 s | **130.50 s** | 115.45 s 的试探碰上空列表，又打开了 15 秒 |
| 同上，但忽略 `No instances available` | 120 s | **120.00 s** | 试探碰上空列表不占名额，一直停在 HALF_OPEN |

第三行的代价：68.5 ~ 120 秒之间，HALF_OPEN 把每次调用都放给负载均衡，负载均衡在本地失败，共 1030 次。
这些调用没有发出去，所以很便宜，但它们不算"被拒"。注意这里必须用 **ignore**：如果让谓词对这个异常返回 `false`，
它会被记为**成功**，半开的 10 次试探就会在列表还空着的时候把熔断器关上。

### 3.6 汇总（全部宕机，1 ~ 200 秒）

| | 打开时刻 | 打到宕机实例的失败调用 | 负载均衡本地失败 | 被拒 |
|---|---|---|---|---|
| 无熔断器，20/s | — | 1180 | 2800 | 0 |
| `configs.default`，20/s | 6.95 s | **150** | 90 | 3740 |
| `configs.default`，5/s | 24.80 s | **140** | 80 | 775 |
| 旧 backendA（COUNT 100、50%、60 s），20/s | 3.45 s | 50 | 30 | 3900 |

新配置比旧的 backendA 打开得晚（要 120 次失败，旧的是 50 次），每 15 秒一轮的试探也多花了 30 次失败。
换来的是恢复时间有上限：负载均衡补回列表后，最多再等约 15.5 秒；旧配置最多要等约 60 秒。

## 4. `configs.default` 逐项说明、组合效应与风险

### 4.1 每个配置项

| 配置项 | 值 | 作用 | 在本模块 |
|---|---|---|---|
| `sliding-window-type` | COUNT_BASED | 窗口按**调用次数**划分（还有一种是 TIME_BASED，按秒） | 生效 |
| `sliding-window-size` | 200 | 统计最近 200 次调用。20 次/秒时约等于最近 10 秒，1 次/秒时是最近 200 秒 | 生效 |
| `minimum-number-of-calls` | 50 | 窗口里不到 50 次时不评估（失败率 -1）。是下限，不是窗口大小 | 生效 |
| `failure-rate-threshold` | 60 | 失败率 **≥** 60% 就打开。同一个阈值也用来判定半开试探 | 生效 |
| `wait-duration-in-open-state` | 15s | OPEN 持续多久。期间立刻拒绝，不访问下游 | 生效 |
| `permitted-number-of-calls-in-half-open-state` | 10 | 半开时放行的试探次数。10 次都结束后才判定 | 生效 |
| `automatic-transition-from-open-to-half-open-enabled` | true | 到点由调度线程切到 HALF_OPEN，不需要有调用 | 生效。验证：`LbHealthApplicationTest.automaticTransitionMovesToHalfOpenWithoutAnyCall`（复制绑定出来的配置，只把 15 s 改成 300 ms，置为 OPEN 后不发任何调用，等它自己变成 HALF_OPEN） |
| `record-failure-predicate` | `DownstreamCircuitBreakerFailurePredicate` | 决定一个异常算失败还是**成功**（不是忽略，§2.2） | 生效 |
| `slow-call-rate-threshold` | 100 | 慢调用占比 ≥ 100% 时打开 | 生效，但实际不会触发（§4.2） |
| `slow-call-duration-threshold` | 60s | 耗时 ≥ 60 s 才算慢调用 | 生效，但实际不会触发 |
| `event-consumer-buffer-size` | 200 | 每个熔断器在内存里保留最近 200 条事件，供 actuator `/actuator/circuitbreakerevents` 读取 | 生效（§1.1）。只是事件的历史记录，不影响熔断 |
| `register-health-indicator` | true | 把熔断器状态加进 `/actuator/health` | 生效，但靠的是本模块的 `CircuitBreakersHealthIndicator`：resilience4j 自带的 indicator 编译时用的是 Boot 3 的 `org.springframework.boot.actuate.health`，在 Boot 4 上注册不了（§1.1）。OPEN 不会让 health 变 DOWN，除非再配 `allow-health-indicator-to-fail: true` |
| `circuit-breaker-aspect-order`（在 `circuitbreaker:` 下，不在 `configs` 里） | 1 | `@CircuitBreaker` 注解切面的顺序。默认是 `LOWEST_PRECEDENCE - 4`，设成 1 就是最外层 | **无效**：本模块不用注解，直接调 registry。如果改用注解，见 §4.4 |
| 舱壁 `max-concurrent-calls` | `configs.default`：**20**（`lb-health-bulkhead`：10） | 同一个 cluster 同时在途的调用上限（信号量）。20 是**并发数**，不是速率：调用耗时 100 ms 时约等于 200 次/秒的上限，耗时 1 s 时就只有 20 次/秒 | 生效 |
| 舱壁 `max-wait-duration` | `configs.default`：0；`lb-health-bulkhead`：**60s** | 舱壁满时，调用在**自己的线程上**最多等多久拿名额。0 = 立刻抛 `BulkheadFullException`；60s = 排队，60 秒还拿不到名额才抛。等待期间被中断也抛 | 生效（`aCallStillWithoutAPermitAfterMaxWaitDurationIsRejected`：等满时间后被拒绝） |
| 舱壁 `fair-call-handling-enabled` | 默认 true | 信号量是公平的：排队的调用按到达顺序拿名额，先来先进 | 生效 |

没写、所以取默认值的几项：`max-wait-duration-in-half-open-state` = 0（一直等）、`ignore-exceptions` = 无、
`record-exceptions` = 无（由谓词决定）、`writable-stack-trace-enabled` = true。

### 4.2 组合起来的效果

**① 打开前要攒多少次失败：30 到 120 次之间。**
`minimum-number-of-calls` × `failure-rate-threshold` 和 `sliding-window-size` × `failure-rate-threshold` 共同决定。

| 窗口状态 | 第一次评估 | 打开前需要的失败次数 |
|---|---|---|
| 刚启动 / 刚 `reset` / 刚从 HALF_OPEN 回到 CLOSED（窗口为空） | 第 50 次调用 | **30**：50 次里有 30 次失败就够了。如果一上来就全部失败，前 49 次不评估，第 50 次失败时打开 |
| 窗口里 200 次都是成功 | 每次都评估 | **120**（200 × 60%） |

所以同一个故障，熔断器的反应速度会差 4 倍，取决于故障之前窗口里有什么。`LbHealthApplicationTest` 里，
空窗口加上全部失败，在第 50 次失败时打开；`CircuitBreakerScenarioTest` 里，满窗口要 120 次。

**② 窗口只按次数算，所以"多快打开"和流量成反比。**
20 次/秒要 6 秒，5 次/秒要 24 秒，1 次/秒要 2 分钟。而且流量低时，窗口里留着的是很久以前的调用。

**③ 15 秒的 OPEN 比 60 秒的健康检查短。**
- 每次半开的 10 次试探，在负载均衡下一轮之前用的都是旧列表，会真实打到宕机实例上：60 秒前共 3 轮，30 次失败（§3.2）。
- 好处是恢复快：负载均衡补回列表后，最多 15.5 秒就放行（§3.5）。
- 两者叠加的效果：熔断器决定"在负载均衡发现之前挨多少次失败"，健康检查决定"多久才发现"。

**④ 半开判定和 CLOSED 用同一个 60% 阈值，但样本只有 10 个。**
10 次里 6 次失败 → OPEN；5 次失败 → **CLOSED**。一个一半实例坏掉的 cluster，半开时可能被判为健康，
然后在新的空窗口里，前 50 次调用失败 30 次以上又打开。结果是在 OPEN 和 CLOSED 之间来回振荡。

**⑤ 自动切换加上流量低：HALF_OPEN 可能一直等下去。**
`automatic-transition: true` 让熔断器准时进入 HALF_OPEN。但离开 HALF_OPEN 需要 10 次试探都结束，而
`max-wait-duration-in-half-open-state` = 0 表示一直等。没有流量时熔断器会一直停在 HALF_OPEN，这本身不是问题；
但如果试探调用挂住了，就有问题（§4.5 R1）。

**⑥ 慢调用规则实际上关掉了。**
`slow-call-rate-threshold: 100` 要求窗口里**每一次**调用都 ≥ 60 秒，200 次全部才行。而且业务 `RestTemplate`
没有超时，挂住的调用根本不会结束，也就不会被记录。慢调用这一项对挂住的下游没有任何保护作用。
真正起作用的是舱壁（§4.4）和超时（§4.5 R1）。

**⑦ 谓词和 `ignore` / `record-exceptions` 的关系。**
- 判断顺序是 ignore → record → 其余都算成功。谓词只能回答"失败"或"成功"，没有"不算"这个选项。
- 同一份配置里同时写了 `record-exceptions`，就和谓词取"或"。
- 坑：在 `instances.<cluster>` 里写 `record-exceptions` 时，Spring 绑定会调用 `builder.recordException(null)`，
  **把从 `configs.default` 继承来的谓词清掉**（`CommonCircuitBreakerConfigurationProperties.buildConfig`）。
  `ignore-exceptions` 也一样，会清掉 `ignore-exception-predicate`。

**⑧ 舱壁和熔断器。** 舱壁在熔断器外面：舱壁的拒绝和排队都不进熔断器的统计，见 §4.4。

### 4.3 `DownstreamCircuitBreakerFailurePredicate`

| 异常 | 返回 | 窗口里记为 | 理由 |
|---|---|---|---|
| `ResourceAccessException`（拒绝连接、连接重置、读超时） | true | 失败 | cluster 不可达 |
| `HttpServerErrorException`（所有 5xx） | true | 失败 | cluster 出错 |
| 408 Request Timeout、429 Too Many Requests | true | 失败 | 虽然是 4xx，但说明 cluster 太忙或太慢 |
| 其他 `HttpClientErrorException`（400 / 401 / 403 / 404 / 409 …） | false | **成功** | cluster 回答了，错在请求。否则一个调用方的错误请求会替所有人打开熔断器 |
| `IllegalStateException("No instances available …")` | true | 失败 | cluster 没有健康实例。不能返回 false，否则空列表会被当成成功（§3.5） |
| `BulkheadFullException` | false | 不会走到这里 | 是我们自己拒绝的，没有发出请求。舱壁在熔断器外面，这个异常到不了谓词。这个分支是防止顺序被改成舱壁在里面：那时 false 会**记为成功**，必须改用 `ignore-exceptions`（§4.4） |
| `CallNotPermittedException` | false | 不会走到这里 | 熔断器自己的拒绝，不会再交给自己的谓词 |
| 其他任何异常 | true | 失败 | 和 resilience4j 不配置谓词时的默认一致 |

谓词由 resilience4j 用无参构造函数实例化，不是 Spring bean，所以不能注入东西，也不应该有状态。
类名写错时，启动直接失败。测试：`DownstreamCircuitBreakerFailurePredicateTest`（10 个），以及
`LbHealthApplicationTest.everyClusterGetsItsOwnBreakerAndBulkheadFromConfigsDefault`（检查绑定出来的配置确实用了这个谓词）。

### 4.4 舱壁（bulkhead）与嵌套顺序

**本模块的结构：`Bulkhead( CircuitBreaker( call ) )`**，舱壁在外面。一次调用依次经过：

1. **舱壁**：有名额就进去；没有就等，最多 `max-wait-duration`（`lb-health-target` 0，`lb-health-bulkhead` 60 s），
   等不到抛 `BulkheadFullException`。**熔断器完全不知道这一步。**
2. **熔断器**：OPEN（或 HALF_OPEN 的试探名额已满）→ 立刻抛 `CallNotPermittedException`，舱壁名额随即释放
   （只占了几微秒）。熔断器从这里开始计时。
3. **下游调用**，熔断器按结果和**从第 2 步算起的耗时**记录，然后舱壁释放名额。

**两种顺序的对比**（2026-11-19 两种都实测过）

resilience4j 注解的默认顺序是 `Retry( CircuitBreaker( RateLimiter( TimeLimiter( Bulkhead( call ) ) ) ) )`：Bulkhead 的切面顺序是
`LOWEST_PRECEDENCE`，在最里层；`circuit-breaker-aspect-order: 1` 把熔断器放到最外层，所以用注解时也是舱壁在里面。

| | **舱壁在外**（本模块） | 舱壁在里（注解的默认顺序） |
|---|---|---|
| 舱壁拒绝 | 到不了熔断器，既不算成功也不算失败。实测（`withTheBulkheadOutsideTheBreakerARejectionIsNotInTheWindow`）：`lb-health-target`（舱壁 20、不等待）每个实例延迟 400 ms，100 次/秒并发 1 秒 → 56 次成功、44 次被舱壁拒绝，熔断器窗口里**只有那 56 次** | `BulkheadFullException` 穿过熔断器，谓词返回 false = **记为成功**。同一个实测：窗口里 **100 次全是成功**。舱壁越忙，真实的失败率被稀释得越厉害：一半调用真实失败、一半被舱壁拒绝时，窗口里只有 50%，低于 60% 的阈值，熔断器不开 |
| 排队时间 | 不算进调用耗时。实测：排队约 9 秒的调用，熔断器记的是约 1 秒 | 算进调用耗时。实测：同样的调用记为约 10 秒 |
| 60 秒的等待 + 60 秒的慢调用阈值 | 互不影响 | 持续过载时每个调用都等满 60 秒，窗口里全是慢调用，慢调用率 100%，熔断器会因为**我们自己的排队**而打开（推算，没有实测） |
| 熔断器 OPEN 时 | 调用先拿舱壁名额，几微秒后被熔断器拒绝并释放 | 不碰舱壁 |
| HALF_OPEN 的试探调用 | 先在舱壁排队，拿到名额后才去占熔断器的试探名额 | 先占熔断器的试探名额，再去舱壁排队，最多 60 秒 |
| 熔断器看到的耗时 | 下游调用本身的耗时 | 用户实际等的时间（含排队） |
| 以后改用 `@CircuitBreaker` / `@Bulkhead` 注解 | 行为会变：要么调切面顺序，要么接受右边这一列 | 行为不变 |

**为什么选舱壁在外**：舱壁是我们自己的限流，它的拒绝和排队都不说明下游健康与否，不该进入衡量下游健康的熔断器窗口。
右边一列的第一行是实打实的正确性问题，第三行会让熔断器误开。

**如果将来必须用注解的顺序**，至少要做两件事：

```yaml
resilience4j.circuitbreaker.configs.default:
  ignore-exceptions:
    - io.github.resilience4j.bulkhead.BulkheadFullException   # 舱壁拒绝既不算成功也不算失败
```

再把舱壁的 `max-wait-duration` 降到明显低于 `slow-call-duration-threshold`。即便如此，排队时间仍会算进调用耗时：
`ignore-exceptions` 忽略的只是结果，时间在调用结束时照样记录。

此外，`circuit-breaker-aspect-order: 1` 还把熔断器放到了 Retry（`LOWEST_PRECEDENCE - 5`）的**外面**：重试 3 次只算 1 次调用，
熔断器看到的失败比下游实际挨的少（本模块没有 Retry）。

**实测**（`LbHealthApplicationTest.aSlowClusterQueuesInItsBulkheadForUpToMaxWaitDurationWithoutTouchingTheBreaker`）：
`lb-health-bulkhead` 的 3 个实例都延迟 1 秒，并发调用 40 次/秒，持续 3 秒，`max-wait-duration: 60s`。
`waiting` 是已经发起、还在舱壁前排队的调用数；`inFlight` 是已经进了舱壁、正在调用下游的数量。

```
[LOAD] t=1s  ok=0  bulkheadFull=0 waiting=30 inFlight=10   ← 发了 40 个，进去 10 个，30 个排队
[LOAD] t=2s  ok=10 bulkheadFull=0 waiting=60 inFlight=10   ← 每秒只能完成 10 个，队伍每秒长 30
[LOAD] t=3s  ok=10 bulkheadFull=0 waiting=90 inFlight=10   ← 停止发送，此时 90 个在排队
[LOAD] t=4s  ok=10 bulkheadFull=0 waiting=80 inFlight=10   ← 之后每秒消化 10 个
…
[LOAD] t=12s ok=10 bulkheadFull=0 waiting=0  inFlight=10
[LOAD] t=13s ok=10 bulkheadFull=0 waiting=0  inFlight=0    ← 120 个全部成功
```

- 120 次全部成功，没有一次被舱壁拒绝。如果 `max-wait-duration` 还是 0：30 次成功、90 次被立刻拒绝，3 秒内就结束（上一版的实测）。
- 同时在途的调用峰值仍是 10。舱壁限的是**并发数**，不管等多久都不会超。
- 熔断器窗口里 120 次，全部成功，慢调用 0 次。舱壁在熔断器外面，所以等的那 9 秒**不算**进调用耗时：每次记录的都约 1 秒。
  反过来，熔断器也看不出"用户其实等了 10 秒"，这个要从 `[LOAD]` 的 `waiting` 或舱壁的事件里看。
- `lb-health-target` 的熔断器和舱壁什么都没看到（窗口 0、峰值 0）。

**`max-wait-duration` 从 0 改成 60s，舱壁的作用变了**

| | 0（立刻拒绝） | 60s（排队） |
|---|---|---|
| 舱壁满时 | 立刻抛 `BulkheadFullException`，调用方马上知道 | 在调用方的线程上等，最多 60 秒 |
| 短暂的突发 | 超出的部分被拒绝 | 被排队吸收，最后都成功（上面的实测） |
| 持续过载 | 超出的部分被拒绝，线程马上释放 | 队伍一直变长（见下），最后既慢又被拒绝 |
| 用户看到的耗时 | 调用本身的耗时 | 排队时间 + 调用耗时 |
| 占用的线程 | 最多 `max-concurrent-calls` 个 | `max-concurrent-calls` + 所有排队中的调用 |

**持续过载时会怎样**（按实测的速率推算）：每秒来 40 个，只能完成 10 个，队伍每秒长 30 个。

- t 秒到达的调用，前面约有 30·t 个在排队，要等约 3·t 秒。
- 第 20 秒到达的调用要等 60 秒，这时已有约 600 个调用在排队，每个都占着一个线程。
- 从这以后，到达的调用等满 60 秒，然后被拒绝。舱壁仍然在拒绝，只是每次拒绝之前先让调用方白等 60 秒。

### 4.5 风险

| # | 风险 | 原因 | 建议 |
|---|---|---|---|
| **R1** | **下游挂住时没有任何保护会生效，HALF_OPEN 可能永久卡住** | 业务 `RestTemplate` 没有连接/读取超时，挂住的调用永远不结束：不进窗口（§2.2），慢调用规则也触发不了（§4.2 ⑥）。如果半开的 10 次试探挂住，又因为 `max-wait-duration-in-half-open-state` = 0 一直等，熔断器就停在 HALF_OPEN，**拒绝之后的所有调用**。每个挂住的调用还占着一个 Tomcat 线程 | 给业务 `RestTemplate` 设读取超时（例如 3 ~ 5 s）；设 `max-wait-duration-in-half-open-state`（例如 30 s）；把 `slow-call-duration-threshold` 降到接近超时、`slow-call-rate-threshold` 降到 50 ~ 80 |
| **R2** | 打开前要攒的失败次数从 30 到 120 不等 | §4.2 ①：取决于窗口是空的还是满的。重启或发布之后窗口是空的，前 50 次调用里 30 次失败就打开（例如预热期的超时）；稳定运行时却要 120 次 | 可以接受时不用改。想统一，就把 `minimum-number-of-calls` 设成接近 `sliding-window-size` |
| **R3** | 谓词返回 false = 成功 | §2.2：4xx 被记为成功会稀释失败率。一个高频 404 的调用方能让熔断器在 cluster 真坏了的时候晚开甚至不开 | 想让 4xx "不算"而不是"算成功"，就用 `ignore-exception-predicate` |
| **R4** | 低流量下反应慢 | §4.2 ②：1 次/秒时要 2 分钟才能攒够 120 次失败 | 低流量的 cluster 单独用 `instances.<cluster>` 把窗口调小，或者改用 TIME_BASED + 合适的 minimum |
| **R5** | 半开试探打到旧列表 | §4.2 ③：15 s < 健康检查的 60 s，每轮试探都是 10 次真实失败 | 把健康检查 `interval` 调到 ≤ 15 s；或者接受（60 秒内多 30 次失败） |
| **R6** | 部分故障时振荡 | §4.2 ④：10 次试探里 5 次失败也会关上，新窗口里很快又打开 | 增加 `permitted-number-of-calls-in-half-open-state`；或者降低 `failure-rate-threshold` |
| **R7** | 只挂一部分实例时熔断器不开；挂多了又连健康实例一起挡掉 | §3.4：挂 1 个 = 10%、2 个 = 20%，都不开；要 6 个以上同时挂才会达到 60%，开了之后 4 个健康实例也拿不到流量 | 这是负载均衡的事：缩短健康检查 `interval`，或打开 Spring Cloud LoadBalancer 的重试 |
| **R8** | 舱壁上限加起来超过 Tomcat 线程数（**已按 20 缓解**） | 原来每个 cluster 100，两个 cluster 就是 200，正好等于 Tomcat 默认的 `max-threads`：下游都挂住时舱壁还没满，Tomcat 就没有线程了。现在 `configs.default` 是 20、`lb-health-bulkhead` 是 10，合计 30，远小于 200，在途调用耗不光 Tomcat。还要注意三点：① 这个上限会随 cluster 数量累加，每加一个 cluster 就多 20；② 排队的调用不受这个上限约束（`max-wait-duration: 60s`，见 R13）；③ 20 对正常流量是否够用：耗时 1 s 的调用只能撑 20 次/秒，超出就被拒绝或排队 | 保持"所有 cluster 的上限之和 + 可能排队的数量"明显小于 `server.tomcat.threads.max`；按 `并发 ≈ 速率 × 耗时` 估算每个 cluster 需要多少 |
| **R9** | ~~以为有健康检查、事件缓冲，其实没有~~（**已解决**：加了 actuator 和 `CircuitBreakersHealthIndicator`，§1.1） | resilience4j 以后出了支持 Boot 4 的版本，它自带的 indicator 会再注册一个 `circuitBreakers` 组件，和本模块的同名 | 升级 resilience4j 时检查一下，删掉本模块的这个类 |
| **R10** | health indicator 现在生效了（§1.1），别让它把本应用拖下线 | resilience4j 的 `allow-health-indicator-to-fail: true` 会让下游熔断把**本应用**的 health 变成 DOWN，上游的负载均衡就把本应用摘掉，故障向上传递 | 保持 `allow-health-indicator-to-fail` 为默认的 false |
| **R11** | `instances.<cluster>` 覆盖时意外丢掉谓词 | §4.2 ⑦：写 `record-exceptions` 会清掉继承来的谓词 | 覆盖时不要写 `record-exceptions`，要写就把谓词也一起写上 |
| **R12** | 改用注解后，舱壁会跑到熔断器里面 | §4.4：注解的默认顺序是舱壁在里。实测那样会把舱壁拒绝记为成功（44 次拒绝全部记为成功），排队时间也算进调用耗时；60 秒的等待在持续过载时可能让熔断器因为慢调用而打开 | 保持手动嵌套 `Bulkhead( CircuitBreaker( call ) )`；必须用注解时，加 `ignore-exceptions: BulkheadFullException`，并把舱壁的 `max-wait-duration` 降到明显低于 `slow-call-duration-threshold` |
| **R13** | **`max-wait-duration: 60s` 让舱壁从"限流"变成"排队"，过载时会把本应用的线程耗光** | 排队的调用在**调用方自己的线程**上等（`Semaphore.tryAcquire(60s)`）。线程上限是 `max-concurrent-calls` + 排队数，不再是 10。按 §4.4 的速率（每秒来 40、完成 10），排队数每秒加 30：如果请求来自 Tomcat 的平台线程（默认 200 个），约 6 秒后 Tomcat 的线程就全卡在 `lb-health-bulkhead` 的舱壁前，**其他 cluster、其他接口也一起不响应**。舱壁本来是用来隔离的，这样一来反而把慢下游的影响扩散到整个应用。另外，60 秒比大多数调用方的超时都长：调用方已经超时放弃了，这里还在排队，排到之后照样去调下游，做的是没人要的工作 | 等待时间要比调用方的超时短得多（例如 0 ~ 1 s，或 p99 耗时的 1 ~ 2 倍）；排队的调用数要算进 Tomcat 线程预算；需要真正的队列时改用 `ThreadPoolBulkhead`（队列长度有上限，`queue-capacity`） |

## 5. 自己跑一遍

模拟（几毫秒，结果固定）：

```bash
mvn -pl creed-resource/creed-resource-lb-health test -Dtest=CircuitBreakerScenarioTest -Dscenario.print=true
```

`-Dscenario.print` 打印每个场景的状态切换和按秒合并的时间线，就是 §3 里的内容。
挂 1 个 / 2 个 / 5 个 / 6 个实例的场景：`oneOfTenDown_…`、`twoOfTenDown_…`、`theThreshold_…`。

真实应用：

```bash
mvn -pl creed-resource/creed-resource-lb-health spring-boot:run -Dspring-boot.run.workingDirectory="$PWD" \
  -Dspring-boot.run.jvmArguments="-DCREED_LB_HC_INTERVAL=60s -DCREED_LB_HC_REFETCH_INTERVAL=180s \
  -DCREED_LB_HC_INITIAL_DELAY=0 -DCREED_LB_HC_UPDATE_RESULTS_LIST=false -DCREED_LB_DEBUG=true"

B=localhost:18099/api/lb-health
curl -s $B/cb                                                            # 每个 cluster：熔断器 + 舱壁的状态与实际配置
curl -s $B/actuator/health | jq .components.circuitBreakers              # health 里的熔断器（OPEN 时整体仍 UP）
curl -s $B/actuator/circuitbreakerevents/lb-health-target/STATE_TRANSITION
curl -s "$B/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:lb-health-target&tag=state:open"

# 熔断：lb-health-target 全部宕机
curl -s -X POST "$B/cb/load?cluster=lb-health-target&rps=20&seconds=150"
for p in $(seq 18100 18109); do curl -s -X PUT "$B/target/instances/$p?up=false" >/dev/null; done
curl -s $B/cb/load                                                       # 每秒一行
curl -s -X DELETE $B/target/instances                                   # 全部恢复
curl -s -X POST $B/cb/lb-health-target/reset

# 舱壁：lb-health-bulkhead 变慢，并发调用
for p in 18110 18111 18112; do curl -s -X PUT "$B/target/instances/$p?delay=2s" >/dev/null; done
curl -s -X POST "$B/cb/load?cluster=lb-health-bulkhead&rps=40&seconds=10&concurrent=true"
curl -s $B/cb/load                                                       # waiting（排队）/ inFlight / bulkheadFull
curl -s $B/cb | jq '.[] | {cluster, bulkhead}'                           # peakInFlight 不超过 10
curl -s "$B/cb/call?cluster=lb-health-bulkhead"                          # 舱壁满时先排队，60 秒后才 429 {"rejected":"bulkhead"}
```

日志：`[LOAD] t=… cluster=… bulkheadFull=… waiting=… inFlight=…` 每秒一行，发送结束后继续每秒一行，直到排队和在途的调用都结束（最多 120 秒）；`[CB] <cluster> State transition from …` 记录每次切换；
启动时每个 cluster 有一行 `[CB] cluster=… breaker: … | bulkhead: …`，列出实际生效的配置。
