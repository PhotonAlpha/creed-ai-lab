# resilience4j 熔断器 × LoadBalancer 健康检查：滑动窗口语义与"服务刚通过健康检查就宕机"

> 版本：Spring Boot 4.1.1 · Spring Cloud 2025.1.3（LoadBalancer 5.0.3、CircuitBreaker 5.0.3）· resilience4j **2.3.0**
> （由 Spring Cloud BOM 决定）。下文所有数字都来自真实代码：要么是 resilience4j / Spring Cloud 的源码，要么是
> `CircuitBreakerScenarioTest`（虚拟时间，真实的健康检查 supplier + 真实的 `CircuitBreaker`）跑出来的结果。

## 0. 结论

问题：`interval: 60s`、`refetch-instances-interval: 180s`，0 秒健康检查成功，1 秒服务宕机，后端仍在频繁调用下游。

1. **负载均衡这一侧，1 ~ 60 秒之间什么都不会变。** 0 秒那一轮的结果（10 个实例全部健康）会一直被 `get()`
   交出去，直到下一轮在 60 秒跑完。`refetch-instances-interval: 180s` 对"发现宕机"没有任何帮助：refetch 只是重新读
   discovery（实例还在注册表里），轮次由 `interval` 驱动。没有熔断器时，20 次/秒的流量在这 59 秒里有
   **1180 次**全部打到已宕机的实例上；60 秒那一轮之后列表变空，此后每次调用都在负载均衡器里直接失败
   （`No instances available`），不再发往下游。
2. **backendA（COUNT_BASED，100）**：窗口里是最近 100 次调用。宕机后失败一次挤掉一次成功，到 **50 次失败**时失败率
   = 50% ≥ 阈值 → **OPEN**。20 次/秒时是 **3.45 秒**；5 次/秒时是 10.8 秒——只看次数，不看速率。之后 60 秒内所有调用
   直接被拒（`CallNotPermittedException`），下游只挨了 50 次。
3. **backendB（TIME_BASED，10）**：窗口是**最近 10 秒**（10 个一秒的桶），不是 10 次。20 次/秒时在 **5.95 秒**打开
   （那一刻窗口里 100 成功 + 100 失败）。但 `minimumNumberOfCalls` 仍是默认的 **100**，10 秒窗口里要攒够 100 次调用，
   **流量低于 10 次/秒时 backendB 永远不会打开**：5 次/秒的场景里它一直 CLOSED，下游挨了 295 次失败调用。
4. **只挂 1 个实例（10 个里）**：失败率只有 10%，**两个熔断器都不会打开**——熔断器按 backend（整个服务）统计，
   不按实例。60 秒那一轮把它摘掉之前，每秒 2 次失败，共 118 次。
5. **服务 90 秒恢复**：负载均衡列表在 120 秒那一轮才补回；熔断器还要等自己的下一次试探：backendA 124.00 秒、
   backendB 126.15 秒才恢复放行。没有熔断器时是 120 秒。

## 1. 这个模块里是怎么接的

| 文件 | 作用 |
|---|---|
| `downstream/DownstreamCaller` | `registry.find(backend)` 取熔断器，`executeSupplier(() -> loadBalancedRestTemplate.getForObject("http://lb-health-target/.../whoami"))` |
| `downstream/LoadRun` | "后端频繁调用下游"：`rps` 次/秒、持续 `seconds` 秒，按秒统计 `ok / failed / noInstance / rejected`，每秒一行 `[LOAD]` |
| `api/CircuitBreakerController` | `GET /cb/call?backend=`、`GET /cb`（状态 + 实际生效的配置）、`POST /cb/{backend}/reset`、`POST|GET|DELETE /cb/load` |
| `upstream/TargetController#whoami` | 业务接口现在和 `/health` 一样跟随实例状态：`up=false` → 503（以前只有健康检查会失败，熔断器看不到任何失败） |

配置就是题目给的那段，原样放在 `application.yml`：

```yaml
resilience4j.circuitbreaker:
  instances:
    backendA:
      registerHealthIndicator: true
      slidingWindowSize: 100
    backendB:
      registerHealthIndicator: true
      slidingWindowSize: 10
      permittedNumberOfCallsInHalfOpenState: 3
      slidingWindowType: TIME_BASED
```

- 依赖是 `spring-cloud-starter-circuitbreaker-resilience4j`（版本跟 Spring Cloud BOM 走），它带进
  `resilience4j-spring-boot3`，由后者把 `resilience4j.circuitbreaker.instances.*` 绑定成 `CircuitBreakerRegistry`。
  在 Boot 4 上这条绑定是可用的，`LbHealthApplicationTest.theCircuitBreakersRunWithTheConfiguredWindowsAndTheDefaultsAroundThem`
  钉住了绑定结果。
- **直接用 resilience4j 的 `CircuitBreaker`，不走 Spring Cloud 的 `CircuitBreakerFactory`**：后者默认再套一层
  `TimeLimiter`（1 秒超时、另起线程执行），会改变调用本身，实验就不只是在测熔断窗口了。
- **熔断器从 registry 里"找"，不"建"**：`registry.circuitBreaker("拼错的名字")` 会悄悄用默认配置建一个新的。
- `registerHealthIndicator: true` **在这里不起作用**：模块没有 actuator；而且 resilience4j-spring-boot3 2.3.0 的
  `CircuitBreakersHealthIndicator` 编译时引用的是 Boot 3 的 `org.springframework.boot.actuate.health.HealthIndicator`，
  Boot 4 把它挪到了 `org.springframework.boot.health.contributor`，对应的自动配置不会生效。

## 2. resilience4j 在滑动窗口下的含义

### 2.1 状态机

```
            失败率/慢调用率 ≥ 阈值                    waitDurationInOpenState 过后的第一次调用
 CLOSED ─────────────────────────────▶ OPEN ───────────────────────────────────────────▶ HALF_OPEN
   ▲  (每记录一次调用就评估一次，                (拒绝一切调用，抛 CallNotPermittedException，    │
   │   前提：窗口里调用数 ≥ minimumNumberOfCalls)   不记录进任何窗口)                              │
   │                                                                                            │
   └──────────── permittedNumberOfCallsInHalfOpenState 次试探的失败率 < 阈值 ◀───────────────────┤
                                     失败率 ≥ 阈值 ─────────────────────────────▶ OPEN ◀────────┘
```

另有 `DISABLED`（永远放行、不统计）、`METRICS_ONLY`（只统计不熔断）、`FORCED_OPEN`（永远拒绝），只能通过 API 手动切换。

### 2.2 一次调用如何进入窗口

| 结果 | 条件（默认值） | 窗口里记为 |
|---|---|---|
| 成功 | 正常返回，且 `recordResult` 判定为否（默认永远否） | success |
| 失败 | 抛出的异常满足 `recordExceptions` / `recordException` 谓词（**默认：所有异常**） | failure |
| 忽略 | 异常满足 `ignoreExceptions`（默认：无） | **完全不计**（既不算成功也不算失败，许可被释放） |
| 慢调用 | 耗时 ≥ `slowCallDurationThreshold`（默认 60 s），成功和失败都可能同时是慢调用 | slow（另算一个比率） |
| 被拒 | 状态是 OPEN，或 HALF_OPEN 的试探名额已占满 | **不进窗口**，只累加 `numberOfNotPermittedCalls` |

- 记录发生在**调用结束时**（`onSuccess` / `onError`），用的是结束那一刻的时钟。TIME_BASED 下，一次 3 秒的慢调用落在
  它结束的那一秒的桶里。
- 本模块里，负载均衡列表为空时 `RestTemplate` 抛 `IllegalStateException("No instances available …")`，
  按默认规则**算失败**——尽管请求根本没有发出去。如果不想让它计入，把它放进 `ignoreExceptions`。

### 2.3 COUNT_BASED：最近 N 次调用

- `slidingWindowSize: 100` = **100 次调用**。实现是一个长度为 N 的环形数组（`FixedSizeSlidingWindowMetrics`），
  新调用覆盖最老的那个，聚合值（总数、失败数、慢调用数、总耗时）增量更新，评估是 O(1)。
- 与时间无关：一小时前的调用，只要后面没有 100 次新调用把它挤出去，就还在窗口里。
- 例：窗口里 100 次全成功，此后连续失败：第 50 次失败时窗口 = 50 成功 + 50 失败 = 50% → 打开。

### 2.4 TIME_BASED：最近 N 秒

- `slidingWindowSize: 10` = **10 秒**，不是 10 次。实现是 N 个"部分聚合"桶（`SlidingTimeWindowMetrics`），
  每个桶对应一个 **epoch 秒**（`clock.instant().getEpochSecond()`）。窗口 = 当前这一秒的桶 + 前 N-1 秒的桶。
- 时间前进时，过期的桶被清零复用；中间如果有几秒没有调用，跳过的桶也会被清零。
- 边界是按整秒切的，不是精确的"最近 10.000 秒"：12:00:05.999 记录的调用，在 12:00:15.000 那一刻就整体移出窗口了。
- 窗口里能放多少次调用取决于**流量**：20 次/秒 × 10 秒 = 200 次；5 次/秒 = 50 次。

### 2.5 `minimumNumberOfCalls`：先攒够，再评估

- 默认 **100**。窗口里调用数 < 这个值时，失败率返回 **-1**（`GET /cb` 里能看到），不评估、不会打开。
- COUNT_BASED：只有用 `slidingWindow(size, minimum, type, strategy)` 这个四参数的 builder 方法时，才会被截到
  `min(minimum, size)`。**Spring Boot 绑定用的是单独的 setter**（`slidingWindowSize(…)`、`minimumNumberOfCalls(…)`），
  不会截断。所以 COUNT_BASED + `slidingWindowSize: 10` + 不写 `minimumNumberOfCalls` = 窗口最多 10 次，却要求 100 次，
  **永远不会打开**（`CircuitBreakerScenarioTest.theMirroredConfigsKeepTheDefaultMinimumOf100Calls` 钉住了这一点）。
- TIME_BASED：从不截断。要求变成"**最近 N 秒里至少 minimum 次调用**"，等价于一个**最低流量**：
  backendB = 100 次 / 10 秒 = **10 次/秒**。低于这个流量，失败率永远是 -1。
- 状态回到 CLOSED 时窗口是**新的、空的**，要重新攒够 minimum 次才会再评估。

### 2.6 阈值与评估时机

- `failureRateThreshold`（默认 50）：失败数 / 窗口调用数 × 100 **≥** 阈值即打开（源码是 `>=`，恰好 50% 也会打开）。
- `slowCallRateThreshold`（默认 100）+ `slowCallDurationThreshold`（默认 60 s）：慢调用率同理；默认配置下等于关闭。
- 只在 CLOSED 状态下、**每记录一次调用后**评估一次；不会因为"时间过去了"而主动评估。

### 2.7 OPEN

- 持续 `waitDurationInOpenState`（默认 **60 s**），期间所有调用立刻抛 `CallNotPermittedException`，不访问下游、
  不进窗口。
- `automaticTransitionFromOpenToHalfOpenEnabled` 默认 **false**：60 秒到了不会自己切换，而是**下一次调用**到来时、
  且当前时间**严格晚于**截止时刻（`isAfter`）才切到 HALF_OPEN。所以 OPEN 实际持续 60 秒 + 到下一次调用的间隔：
  backendB 在 66.10 秒重新打开，截止时刻 126.10 秒，126.00 / 126.05 / 126.10 这三次调用仍被拒，126.15 秒才切换。

### 2.8 HALF_OPEN

- 用一个**独立的 COUNT_BASED 窗口**，大小 = `permittedNumberOfCallsInHalfOpenState`（默认 10，backendB 是 3），
  minimum 也等于它。名额用完后，额外的调用被拒。
- 名额全部记录完才评估：失败率 ≥ 阈值 → OPEN（重新等 60 秒）；否则 → CLOSED（空窗口重新开始）。
- `maxWaitDurationInHalfOpenState`（默认 0）= 一直等那几次试探结束；设成正数则超时后回到 OPEN。
- 试探调用和普通调用一样会走负载均衡：如果此时负载均衡列表是空的，试探在本地就失败，熔断器会再开 60 秒。

### 2.9 粒度：每个熔断器 = 一个 backend

窗口统计的是这个名字下的**所有**调用，负载均衡器把调用分到哪个实例它并不知道。一个实例挂掉、其他实例正常时，
失败率 ≈ 1 / 实例数，远低于 50%。实例级的隔离要靠负载均衡器（健康检查、重试到下一个实例），不是熔断器。

### 2.10 两个 backend 实际生效的配置

| | backendA | backendB |
|---|---|---|
| `slidingWindowType` | COUNT_BASED（默认） | TIME_BASED |
| `slidingWindowSize` | 100 次调用 | 10 秒 |
| `minimumNumberOfCalls` | 100（默认） | **100（默认，不截断）→ 至少 10 次/秒** |
| `failureRateThreshold` | 50%（默认） | 50%（默认） |
| `waitDurationInOpenState` | 60 s（默认） | 60 s（默认） |
| `permittedNumberOfCallsInHalfOpenState` | 10（默认） | 3 |
| `automaticTransitionFromOpenToHalfOpenEnabled` | false（默认） | false（默认） |
| `recordExceptions` / `ignoreExceptions` | 所有异常 / 无 | 所有异常 / 无 |
| `registerHealthIndicator` | 无效（见 §1） | 无效 |

## 3. 场景：interval 60 s、refetch 180 s，0 秒健康、1 秒宕机，持续调用

### 3.1 负载均衡这一侧

`update-results-list: false`、`initial-delay: 0`。时间轴（"0 秒" = 一轮成功的健康检查）：

| 时刻 | 发生的事 | `get()` 交出的列表 |
|---|---|---|
| 0 s | 一轮健康检查，10/10 存活 | 10 个 |
| 1 s | 服务宕机 | **仍是 10 个**（上一轮的结果） |
| 60 s | 下一轮（`interval` 计时器，从上一轮**结束**起算），`trigger=INTERVAL` | 0 个 |
| 120 s | refetch 与 interval 轮次恰好重合：refetch 先到，`switchMap` 换掉内层流（连同 interval 计时器），新一轮 `trigger=REFETCH` | 0 个 |
| 180 s | 下一轮，`trigger=INTERVAL` | 0 个 |

> 上表的 0 秒是 supplier 启动后的第 60 秒（第二轮）；refetch 相对 supplier 启动在 180 s / 360 s 触发，在场景时间里
> 就是 120 s / 300 s。180 是 60 的整数倍，所以 refetch 总是和一轮 interval 重合。结论：**发现宕机的时间由 `interval`
> 决定，最长约 60 秒**。

- 以上是"实例拒绝连接"（进程没了）的情况，探活立刻失败，一轮只要几毫秒。如果宕机的表现是**挂住**（黑洞、网络分区），
  blocking 模式下每个实例的探活要等满 connect + read 超时（默认 2 s + 3 s），10 个串行就是 50 秒——60 秒那一轮要到
  约 110 秒才出结果。
- refetch 只是重新读 discovery。实例并没有从注册表里消失，所以 refetch 读回来的还是 10 个，再交给健康检查过滤。

### 3.2 没有熔断器（基线，20 次/秒）

```
t=0        lb=10 ok=20 failed=0  noInstance=0
t=1..59    lb=10 ok=0  failed=20 noInstance=0      ← 59 秒 × 20 次，全部打到宕机实例
t=60..199  lb=0  ok=0  failed=0  noInstance=20     ← 列表空了，负载均衡器本地失败，不再发出请求
打到下游的失败调用：1180
```

### 3.3 backendA（COUNT_BASED 100），20 次/秒

```
t=3.45s   CLOSED -> OPEN            第 50 次失败：窗口 = 50 成功 + 50 失败
t=63.50s  OPEN -> HALF_OPEN         3.45 + 60 之后的第一次调用
t=63.95s  HALF_OPEN -> OPEN         10 次试探，列表已空 → 全部 noInstance 失败
t=124.00s OPEN -> HALF_OPEN / t=124.45s HALF_OPEN -> OPEN
t=184.50s OPEN -> HALF_OPEN / t=184.95s HALF_OPEN -> OPEN

t=0        CLOSED lb=10 ok=20 failed=0  rejected=0
t=1..2     CLOSED lb=10 ok=0  failed=20 rejected=0
t=3        OPEN   lb=10 ok=0  failed=10 rejected=10
t=4..59    OPEN   lb=10 ok=0  failed=0  rejected=20   ← 列表还是旧的 10 个，但熔断器挡住了
t=60..62   OPEN   lb=0                     rejected=20
t=63       OPEN   lb=0  noInstance=10      rejected=10 ← 半开试探
…
打到下游的失败调用：50；试探时在本地失败：30；被拒：3900
```

5 次/秒：10.80 秒打开（还是 50 次失败，只是攒得慢），下游同样只挨 50 次。

### 3.4 backendB（TIME_BASED 10 s），20 次/秒

```
t=5.95s   CLOSED -> OPEN            窗口 = 第 -4..5 秒：100 成功 + 100 失败
t=66.00s  OPEN -> HALF_OPEN
t=66.10s  HALF_OPEN -> OPEN         3 次试探，列表已空
t=126.15s OPEN -> HALF_OPEN / t=126.25s HALF_OPEN -> OPEN   （126.10 那次调用不"晚于"截止时刻，仍被拒）
t=186.30s OPEN -> HALF_OPEN / t=186.40s HALF_OPEN -> OPEN

t=1..4     CLOSED lb=10 failed=20
t=5        OPEN   lb=10 failed=20
t=6..59    OPEN   lb=10 rejected=20
…
打到下游的失败调用：100
```

比 backendA 慢：它要等失败**占到最近 10 秒的一半**，而 backendA 只要占到最近 100 次的一半（20 次/秒时 = 最近 5 秒）。

### 3.5 backendB，5 次/秒：永远不打开

```
（没有任何状态切换）
t=1..59    CLOSED lb=10 failed=5
t=60..199  CLOSED lb=0  noInstance=5
打到下游的失败调用：295；本地失败：700
```

10 秒窗口最多 50 次调用 < `minimumNumberOfCalls` 100，失败率一直是 -1。这就是 §2.5 的最低流量：**10 次/秒**。

### 3.6 只挂一个实例（10 个里的 i03），20 次/秒

两个熔断器结果相同：

```
t=1..59    CLOSED lb=10 ok=18 failed=2     ← 轮询，每 10 次有 1 次落到 i03
t=60..199  CLOSED lb=9  ok=20 failed=0     ← 60 秒那一轮把 i03 摘掉
打到下游的失败调用：118
```

失败率 10%，不会打开。要在这 60 秒里少失败，靠的是负载均衡层：缩短 `interval`，或打开 Spring Cloud LoadBalancer 的
重试（`spring.cloud.loadbalancer.retry.*`，换下一个实例重试）——本模块没有启用。

### 3.7 服务在 90 秒恢复

| | 负载均衡列表补回 | 第一次成功 | 原因 |
|---|---|---|---|
| 没有熔断器 | 120 s（下一轮） | 120 s | 只等健康检查 |
| backendA | 120 s | **124.00 s** | 63.95 s 重新打开 → 123.95 s 之后的第一次调用才试探，10 次试探成功 → 124.45 s CLOSED |
| backendB | 120 s | **126.15 s** | 66.10 s 重新打开 → 126.10 s 之后才试探 |

服务恢复后，用户还要多等 34 ~ 36 秒：30 秒等健康检查（90 → 120），再 4 ~ 6 秒等熔断器的半开时机。原因是半开试探
（63.5 s、66 s）遇到的是空列表：`No instances available` 按默认规则算失败，熔断器又开 60 秒，下一次试探只能在 120 s
那一轮之后的 124 s / 126 s。

把这个异常放进 `ignoreExceptions`（`CircuitBreakerScenarioTest.recovery_ignoringNoInstancesAvailable_closesRightAfterTheListComesBack`）：

```
t=3.45s   CLOSED -> OPEN
t=63.50s  OPEN -> HALF_OPEN         被忽略的试探不占名额：一直停在 HALF_OPEN
t=120.45s HALF_OPEN -> CLOSED       120 s 那一轮补回列表，接下来的 10 次试探全部成功
t=64..119  HALF_OPEN lb=0 noInstance=20   ← 半开期间每次调用都放行到负载均衡器，在本地失败（不发请求）
第一次成功：120.00 s（和没有熔断器一样）
```

### 3.8 汇总（全部宕机，1 ~ 200 秒）

| | 打开时刻 | 打到宕机实例的失败调用 | 负载均衡本地失败 | 被拒 |
|---|---|---|---|---|
| 无熔断器，20/s | — | 1180 | 2800 | 0 |
| backendA，20/s | 3.45 s | **50** | 30 | 3900 |
| backendA，5/s | 10.80 s | **50** | 30 | 915 |
| backendB，20/s | 5.95 s | **100** | 9 | 3871 |
| backendB，5/s | 从不 | 295 | 700 | 0 |

### 3.9 由此得出的配置建议

- **TIME_BASED 一定要显式写 `minimumNumberOfCalls`**，按真实流量的低谷来定（比如 backendB 写 10 ~ 20）；
  COUNT_BASED 不写时也要确认它没有大于 `slidingWindowSize`。
- 想让"负载均衡列表为空"不消耗半开试探，把它放进 `ignoreExceptions`（见 §3.7：恢复时间从 124 s 提前到 120 s）。
  `IllegalStateException` 范围很宽，实际使用时最好用 `recordException` 谓词只排除 `No instances available` 这一种。
- 发现宕机的速度由健康检查的 `interval` 决定；熔断器只决定在发现之前**挨多少次失败**。两者都需要。

## 4. 自己跑一遍

模拟（几毫秒，结果固定）：

```bash
mvn -pl creed-resource/creed-resource-lb-health test -Dtest=CircuitBreakerScenarioTest -Dscenario.print=true
```

`-Dscenario.print` 会打印每个场景的状态切换和按秒合并的时间线（就是 §3 里的内容）。

真实应用（需要等真实的 60 秒）：

```bash
mvn -pl creed-resource/creed-resource-lb-health spring-boot:run -Dspring-boot.run.workingDirectory="$PWD" \
  -Dspring-boot.run.jvmArguments="-DCREED_LB_HC_INTERVAL=60s -DCREED_LB_HC_REFETCH_INTERVAL=180s \
  -DCREED_LB_HC_INITIAL_DELAY=0 -DCREED_LB_HC_UPDATE_RESULTS_LIST=false -DCREED_LB_DEBUG=true"

B=localhost:18099/api/lb-health
curl -s $B/cb                                                   # 两个熔断器的状态与实际配置
curl -s -X POST "$B/cb/load?backend=backendB&rps=20&seconds=150" # 开始持续调用
# 看到下一个 [HC-RESULT]（0 秒）之后约 1 秒，让全部实例宕机：
for p in $(seq 18100 18109); do curl -s -X PUT "$B/target/instances/$p?up=false" >/dev/null; done
curl -s $B/cb/load                                              # 每秒一行：state / lb / ok / failed / noInstance / rejected
curl -s -X DELETE $B/target/instances                          # 全部恢复
curl -s -X POST $B/cb/backendB/reset
```

日志里 `[LOAD] t=…` 每秒一行，`[CB] backendB State transition from CLOSED to OPEN …` 记录每次切换。
真实运行和模拟的差别：调用是串行的，下游变慢时实际速率会低于 `rps`（`[LOAD]` 行里的总数）；健康检查轮次有真实的耗时。
