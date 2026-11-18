# creed-resource-lb-health

验证 Spring Cloud LoadBalancer 健康检查的配置：**`ServiceInstanceListSupplier.get()` 只返回最近一轮"全部检查完成"
的完整存活列表，不会返回 1、2、3…… 逐步增长的部分列表**。

应用同时是客户端和它自己的 **13 个目标实例**：`lb-health-target` 在 simple discovery 里登记了
`localhost:18100`～`18109`，舱壁测试用的 `lb-health-bulkhead` 登记了 `18110`～`18112`，
`TargetConnectorsConfiguration` 为每个 cluster 的每个端口开一个额外的 Tomcat connector，
`/target/health` 按本地端口返回 UP / DOWN / 延迟响应，可以在运行时切换。

版本：Spring Boot 4.1.1 / Spring Cloud 2025.1.3（`spring-cloud-loadbalancer` / `spring-cloud-commons` **5.0.3**）。
下面的结论都来自这个版本的源码，没有凭记忆推断。

## 运行

```bash
mvn -q -pl creed-resource/creed-resource-lb-health -am -DskipTests install
mvn -pl creed-resource/creed-resource-lb-health spring-boot:run \
  -Dspring-boot.run.workingDirectory="$PWD" -Dspring-boot.run.jvmArguments="-DCREED_LB_DEBUG=true"
```

| 请求 | 作用 |
|---|---|
| `GET  :18099/api/lb-health/lb/instances` | 负载均衡此刻拿到的列表（`supplier.get().next()`，和 `RoundRobinLoadBalancer` 读的一样） |
| `GET  :18099/api/lb-health/lb/choose?n=40` | 选 n 次，按端口计数 |
| `GET  :18099/api/lb-health/lb/call` | 通过 `@LoadBalanced RestTemplate` 真正调用一次 |
| `PUT  :18099/api/lb-health/target/instances/18103?up=false` | 让 i03 的健康检查返回 503 |
| `PUT  :18099/api/lb-health/target/instances/18107?delay=4s` | 让 i07 4s 后才响应（超过 3s 读超时 → DOWN） |
| `DELETE :18099/api/lb-health/target/instances` | 全部恢复 |

不需要 Docker、PKI 或授权服务器。HTTP，不在 OAuth2 mesh 里。

## 结构

```
blocking DiscoveryClient                 每次订阅发射一次后完成
  └ [RefetchLogging]                     仅 debug：[REFETCH]
      └ HealthCheckServiceInstanceListSupplier
            Flux.defer(delegate).repeatWhen(refetch-instances-interval)
              .switchMap(list -> healthCheckFlux(list))      ← 每次 refetch 取消正在跑的内层流
              .delaySubscription(initial-delay).replay(1).refCount(1)
            healthCheckFlux = Flux.defer(一轮).repeatWhen(interval)
```

- 健康检查是最外层的 bean。外面**不叠 `withCaching()`**：缓存未命中时取的是 `delegate.get().take(1)`（第一次发射），
  一存就是 35s；健康检查的 `replay(1)` 本身就是缓存。
- `afterPropertiesSet()` 建立常驻订阅，所以两次请求之间检查也一直在跑；`get()` 只是读 `replay(1)` 里的最新值。
- 配置类：`LbHealthConfiguration`（`@LoadBalancerClient(name = "lb-health-target")`）→
  `lb/TargetLoadBalancerClientConfiguration`（子上下文，**不加** `@Configuration`，否则会被组件扫描到主上下文里）。

## 参数约束及原因

| 参数 | 取值 | 原因 |
|---|---|---|
| `health-check.update-results-list` | **`false`（必须）** | 默认 `true` 时，每轮从空列表开始，每有一个实例返回就发射一次累积列表 `[1]`、`[1,2]`……；`get()` 背后是 `replay(1)`，在这一轮结束前拿到的就是部分列表，每轮都会重现。`false` 时一轮只在 `collectList()` 完成时发射一次。 |
| `health-check.refetch-instances` | **`true`（必须）** | `withBlockingDiscoveryClient()` 是 `Mono.fromCallable(getInstances)`，发射一次就完成；不 refetch 就永远看不到扩缩容。 |
| `health-check.interval` | `30s`，**< refetch** | 外层是 `switchMap`：每次 refetch 都取消正在跑的内层流，**连同它的 interval 计时器**。refetch ≤ interval 时 interval 永远到不了，检查只按 refetch 的节奏跑，而且每次都要重新查一次 discovery。`interval` 是从一轮**结束**开始计时的（`repeatWhen`），所以周期 = 一轮耗时 + interval。 |
| `health-check.refetch-instances-interval` | `120s`，**> 一轮的最坏耗时** | 一轮还没跑完就被 refetch 取消，结果直接丢掉；如果每轮都比 refetch 间隔长，就**永远没有一轮能完成**，`get()` 一直停在最后一次完成的列表上（下线的实例也一直在里面）。 |
| 单次探活超时 `creed.lb-health.health-check.connect-timeout` / `read-timeout` | `2s` / `3s` | 阻塞模式下这就是**唯一**有效的超时。Spring 给每个探活加了 `timeout(interval)`，但阻塞探活在 `subscribe()` 里就把活干完了，timeout 的计时器在那之后才启动，永远不会触发。 |
| 阻塞模式一轮最坏耗时 | 实例数 × (connect + read) = **10 × 5s = 50s < 120s** | 阻塞探活在同一个 Reactor `parallel` 线程上**串行**执行（`Flux.merge` 逐个订阅，每个都阻塞）。一个卡住的实例会挡住它后面所有实例。 |
| `health-check.initial-delay` | `0` | 第一轮之前没有任何值，`get().next()` 会一直等；延迟多少，第一个请求就多等多少。 |
| `eager-load.clients` | `[lb-health-target]` | supplier 默认在第一个请求时才创建，那个请求要等完整的第一轮。`LoadBalancerEagerContextInitializer` 在 `ApplicationReadyEvent` 时就建好子上下文，第一轮在流量到来前跑完。 |
| 健康检查用的 RestTemplate | **不带 `@LoadBalanced`**，显式传给 `withBlockingHealthChecks(restTemplate)` | 带了的话，探活 URL `http://localhost:18100/...` 会被负载均衡拦截器当成 serviceId `localhost`，找不到实例，异常被内置探活的 `catch (Exception ignored)` 吞掉变成 `false` → 每一轮都是**空列表**。注意无参的 `withBlockingHealthChecks()` 用的是 `context.getBean(RestTemplate.class)`：本模块唯一的 `RestTemplate` bean 正是带 `@LoadBalanced` 的业务客户端，所以只能显式传入。 |

**不要只为某一项写 `spring.cloud.loadbalancer.clients.lb-health-target.*`**：这个块不继承全局配置，没写的每一项都退回库的
默认值（interval 25s、update-results-list true、refetch false……）。启动校验读的是生效后的值，会报出来。

### 两种探活模式：`creed.lb-health.health-check.mode`

| | `blocking`（默认） | `webclient` |
|---|---|---|
| builder | `withBlockingHealthChecks(RestTemplate)` | `withHealthChecks(WebClient)`（`JdkClientHttpConnector`，同样 2s / 3s） |
| 一轮内的探活 | 串行，一个线程 | 并发 |
| Spring 的 `timeout(interval)` | 不生效 | 生效 |
| 一轮最坏耗时 | 实例数 × 单次超时 | min(单次超时, interval) |

实测（`delay=4s` 的 i07）：阻塞模式 10 个 `[HC]` 都在 `parallel-1` 上，i07 的 3s 读超时把 i08/i09 推迟了 3s；
webclient 模式 10 个请求在 5ms 内全部发出，一轮 3s。两种模式下 `get()` 都只在一轮结束时变化。

## 启动校验（`HealthCheckConfigValidator`，WARN `[LB-HEALTH-CONFIG]`）

读 `LoadBalancerClientFactory.getProperties(serviceId)`（生效后的值）和 discovery 当前的实例数，违反以下任一条就打 WARN（不阻止启动）：

- `update-results-list=true`
- `refetch-instances=false`
- `refetch-instances-interval <= interval`
- 一轮最坏耗时 `>= refetch-instances-interval`（blocking：实例数 × 单次超时；webclient：min(单次超时, interval)）
- `initial-delay > 0`
- `eager-load.clients` 不包含该服务

全部满足时打一行 INFO：`OK - 10 instances, interval=PT30S, refetch-instances-interval=PT2M, worst-case round=PT50S`。

## 调试日志：`creed.lb-health.debug.enabled=true`（`CREED_LB_DEBUG`，默认关闭）

| 前缀 | 位置 | 含义 |
|---|---|---|
| `[REFETCH] 时间 size=N` | discovery 和健康检查之间的 `DelegatingServiceInstanceListSupplier`，`doOnNext` | 一次 refetch；健康检查的 `switchMap` 会在此刻换掉内层流 |
| `[HC] 时间 URI thread=…` | 探活 RestTemplate 的拦截器 / WebClient 的 filter | 一次探活，能看出串行还是并发、在哪个线程；失败时多一行 `DOWN: 异常`（内置探活会把异常吞掉） |
| `[HC-STREAM] start` | 子类重写 `healthCheckFlux` 的 `doOnSubscribe` | 新的内层流（启动或 refetch） |
| `[HC-ROUND] start round=N trigger=REFETCH\|INTERVAL` | 子类重写 `isAlive`：一轮组装时第一次调用 | 一轮开始。内层流的第一轮是 `REFETCH`（启动时第一次拿到列表也算），之后每轮都是 `INTERVAL`（`repeatWhen` 的计时器）；`healthCheckFlux` 每被 `switchMap` 调用一次就把下一轮标成 `REFETCH` |
| `[HC-RESULT] round=N trigger=… size=n/N alive=[…]` | `doOnNext` | 这一轮完成（`update-results-list=true` 时每个存活实例一行） |
| `[HC-STREAM] cancelled by refetch …` | `doOnCancel` | 两轮之间被取消：正常（只丢了 interval 计时器）。**MID-ROUND**（WARN）：这一轮结果被丢弃；反复出现说明一轮比 refetch 间隔长 |

`HealthCheckServiceInstanceListSupplier.healthCheckFlux` / `isAlive` 在 5.0.3 里是 `protected`，可以继承重写。但
`withBlockingHealthChecks(...)` / `withHealthChecks(...)` 总是 new 原始类，所以 debug 开启时改用
`builder.with((ctx, delegate) -> new ObservableHealthCheckServiceInstanceListSupplier(...))`，探活函数是内置 lambda
的拷贝（`HealthCheckClients`）。debug 关闭时走的就是原生的 builder 方法。

**MDC `hcTrigger` / `hcRound`**（仅 debug）：每次探活都带着所属的轮次，`logging.pattern.level` 把它们以 Splunk
key=value 写在级别后面，Logbook 的请求 / 响应行就成了
`TRACE trigger=INTERVAL round=12 … origin=local type=request …`。blocking 模式下探活就在订阅它的线程上跑，MDC 只在
订阅期间设置、随后移除；webclient 模式下 Logbook 在 JDK HttpClient 的工作线程上写日志，所以轮次也写进 Reactor
Context，由 `HealthCheckMdc`（`context-propagation` + `Hooks.enableAutomaticContextPropagation()`，全局，只在 debug +
webclient 时开启）恢复到那些线程上。

## 熔断器与舱壁：每个 cluster 各一套（resilience4j）

业务 `RestTemplate`（`@LoadBalanced`）的每次调用都是 `Bulkhead( CircuitBreaker( call ) )`（`DownstreamCaller`）。舱壁在外面，和 resilience4j 注解的默认顺序相反：舱壁的拒绝和排队都不进熔断器的统计（对比和实测见文档 §4.4）。
**cluster** 指 `spring.cloud.discovery.client.simple.instances` 下的一个键。启动时每个 cluster 各建一个熔断器和一个舱壁，
名字就是 cluster 名，配置来自 `resilience4j.circuitbreaker.configs.default` / `resilience4j.bulkhead.configs.default`。
`instances.<cluster>` 可以单独覆盖某一个 cluster。一个 cluster 的故障不会打开另一个 cluster 的熔断器。

| cluster | 实例 | 用途 |
|---|---|---|
| `lb-health-target` | 10 个，18100–18109，带健康检查 | 熔断场景 |
| `lb-health-bulkhead` | 3 个，18110–18112，Spring 默认负载均衡，舱壁上限 **10**，`max-wait-duration: 60s`（满了先排队） | 舱壁场景：`delay=2s` + 并发调用 |

哪些异常算失败由 `DownstreamCircuitBreakerFailurePredicate` 决定：I/O 错误、5xx、408、429 和 `No instances available`
算失败；其他 4xx 记为**成功**（谓词返回 false 不是"忽略"）。`/target/whoami` 跟随实例状态：`up=false` → 503，`delay` → 变慢。

| 接口 | 作用 |
|---|---|
| `GET /cb` | 每个 cluster：熔断器状态、窗口调用数 / 失败率（-1 = 未达 `minimumNumberOfCalls`）、舱壁在途数 / 峰值、实际生效的配置 |
| `GET /cb/call?cluster=lb-health-bulkhead` | 走该 cluster 的一次调用。熔断器打开：503 `{"rejected":"circuit-breaker"}`；舱壁满：先排队，等满 `max-wait-duration` 仍拿不到名额才 429 `{"rejected":"bulkhead"}` |
| `POST /cb/load?cluster=&rps=20&seconds=150&concurrent=false` / `GET /cb/load` / `DELETE /cb/load` | 持续调用，按秒统计 ok / failed / noInstance / rejected / bulkheadFull / waiting（排队中）/ inFlight（日志 `[LOAD]`）。`concurrent=true` 每个调用一个虚拟线程，不等上一个返回；发送结束后继续每秒一行，直到排队的调用都结束 |
| `POST /cb/{cluster}/reset` | 重置熔断器和峰值计数 |

**actuator**：`/api/lb-health/actuator/` 下有 `health`（组件 `circuitBreakers`，每个 cluster 一项；熔断器 OPEN 不会让
本应用变 DOWN）、`circuitbreakers`、`circuitbreakerevents`、`bulkheads`、`bulkheadevents`、`metrics`（`resilience4j.*`）、
`info`、`loggers`。resilience4j 2.3.0 自带的 health indicator 在 Boot 4 上注册不了，由 `CircuitBreakersHealthIndicator` 代替。
舱壁默认上限 `configs.default.max-concurrent-calls` = 20。

`configs.default` 每一项的作用、组合起来的效果（例如打开前要攒 30 ~ 120 次失败）、风险清单（最重要的：业务
`RestTemplate` 没有超时），以及"interval 60 s、refetch 180 s，0 秒健康、1 秒宕机，持续调用"的完整推演，都在
[`docs/resilience4j-circuit-breaker.md`](docs/resilience4j-circuit-breaker.md)。推演由 `CircuitBreakerScenarioTest`
在虚拟时间上跑出来（`-Dscenario.print` 打印时间线）。

## 测试

- `HealthCheckRoundTest`：Spring 原生的 `HealthCheckServiceInstanceListSupplier`（和 debug 子类）+ 假 discovery（10 个实例）+
  可编排的探活（每个实例的延迟、成功/失败/异常/挂起），StepVerifier + virtual time。阻塞探活用"单个虚拟线程 + 队列"模拟：
  串行执行，正在跑的不能中断，排队的会随着这一轮被取消而丢弃。
  - `update-results-list=false`：每次发射都是完整的 10 个；对照组 `true`：1…10 逐个增长，下一轮又缩回 1。
  - `interval < refetch`：轮次间隔 35s（一轮 5s + interval 30s），120s 时 refetch 替换计时器。
  - `refetch(20s) < interval(30s)`：轮次只出现在 0/20/40…s，30s 的 interval 从未生效，每次检查都重新查 discovery。
  - 一轮（10 × 15s 串行 = 150s）超过 refetch(120s)：10 分钟内一次都不发射，`get()` 一直是旧列表（含已 DOWN 的 i03），
    5 次 MID-ROUND 取消。对照：10 × 5s = 50s 时正常更新；同样的慢探活用 webclient 并发模式，一轮 15s，也正常更新。
  - 部分实例不健康（返回 false / 抛异常 / 挂起）：只在一轮结束时一次性发射 7 个健康实例；挂起的实例在 webclient
    模式下被 `timeout(interval)` 截断，在阻塞模式下只能靠 HTTP 超时。
- `HealthCheckConfigValidatorTest`：上面每条校验规则。
- `LbHealthApplicationTest`：完整应用，10 个 connector 在随机空闲端口上，通过真实 HTTP 探活：eager-load 在没有请求时就建好
  supplier；一个实例 503、一个超过读超时后，每 20ms 读一次 `get()`，读到的只可能是"上一轮完整列表"或"新一轮完整列表"；
  `choose` 不会选到 DOWN 的实例。熔断器 / 舱壁：两个 cluster 各有一套，配置等于 `configs.default`；空窗口时第 50 次失败打开，
  另一个 cluster 不受影响；自动切到 HALF_OPEN；舱壁满时排队（60 s），120 次全部成功、在途峰值 = 10、排队时间不算慢调用；等满时间仍拿不到名额就拒绝。
- `CircuitBreakerScenarioTest`：§3 的推演（全部宕机 20 次/秒、5 次/秒，挂 1 个实例，90 秒恢复）。
- `DownstreamCircuitBreakerFailurePredicateTest`：每类异常的判定；返回 false 会被记为成功。
