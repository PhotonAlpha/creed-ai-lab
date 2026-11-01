# CallerRunsPolicy 到底占用多少 Tomcat 线程

针对 `com.creed.metrics.config.ThreadPoolConfig#taskExecutor`（bean 名 `myTask`）的分析与实测。

## 前提

| 项 | 值 |
|---|---|
| core / max / queue | 40 / 160 / 100 |
| 拒绝策略 | `ThreadPoolExecutor.CallerRunsPolicy` |
| `server.tomcat.threads.max` | 300 |
| 请求处理方式 | 请求线程 for 循环 `CompletableFuture.runAsync(task, taskExecutor)` 提交 N 个任务，然后 `allOf(...).join()` |
| 单个任务 | 调远程 API，响应 500～1000ms（模拟中用 sleep 代替） |

池子同一时刻最多容纳 **160 个在执行 + 100 个排队 = 260** 个任务，超过的那一个才会被拒绝。

## 结论

1. **单个 API 请求，caller-run 只会占用一个 Tomcat 线程，也就是处理这个请求的那个线程。**
   `CallerRunsPolicy` 在"提交任务的线程"上执行被拒绝的任务。一个请求只有一个提交线程，所以不管
   array 多长、被拒绝多少次，都是这一个线程**串行地**多执行了几个任务，不会再借用别的 Tomcat 线程。
2. **N 个并发请求时，就有 N 个 Tomcat 线程**，每个请求占一个，与 caller-run 无关，因为它们本来就在
   `join()` 上等待。caller-run 的影响是：这些线程在被拒绝时由"等待"变成"自己调远程 API"。实测中
   N 个线程**会同时**处于 caller-run 状态（峰值并发 = N），每个请求被拒绝的次数也随 N 增加。
3. "大量 Tomcat 线程被占用"是**并发请求数**造成的，不是一个请求的 array 很长造成的。

## 情况 1：一个请求，array = 500

推演：

1. 第 1～260 个任务：建满 160 个线程，排满 100 个队列位。
2. 第 261 个被拒绝，请求线程自己执行它，阻塞 500～1000ms，**这段时间 for 循环不再提交新任务**。
3. 在这段时间里池子完成约 160 个任务，队列被取空，又空出 100～200 个位置。
4. 循环继续提交，再次塞满，再被拒绝……直到 500 个全部提交。

剩下的 500 − 260 = 240 个任务，每轮 caller-run 期间池子能消化大约 100～200 个，所以总共拒绝
**约 3 次**。这就是 `CallerRunsPolicy` 的背压：被拒绝时提交方会自动减速。

实测（5 次，真实时延 500～1000ms）：

| run | caller-run 次数 | 执行过 caller-run 的 Tomcat 线程数 | caller-run 峰值并发 | 请求耗时 |
|---|---|---|---|---|
| 1 | 3 | 1 | 1 | 3103 ms |
| 2 | 4 | 1 | 1 | 3513 ms |
| 3 | 3 | 1 | 1 | 3472 ms |
| 4 | 3 | 1 | 1 | 3511 ms |
| 5 | 3 | 1 | 1 | 3018 ms |

**Tomcat 线程消耗：1 个。** 线上日志里看到的"3 次 caller-run"，其实是同一个 `http-nio-*-exec-N` 出现了 3 次。

## 情况 2：多个并发请求，每个 array = 500

所有请求共享这一个 160 + 100 的池子，池子很快被占满，每个请求线程都会频繁被拒绝。

| 并发请求数 | 时延 | caller-run 总次数 | 每请求 caller-run | 执行过 caller-run 的 Tomcat 线程数 | 峰值并发 | 请求耗时 avg / max |
|---|---|---|---|---|---|---|
| 2 | 1.0× | 10 | 5 | 2 | 2 | 5.1 s / 5.3 s |
| 5 | 1.0× | 64 | 10～15 | 5 | 5 | 10.8 s / 12.5 s |
| 10 | 1.0× | 248 | 19～28 | 10 | 10 | 20.5 s / 23.0 s |
| 20 | 1.0× | 1028 | 43～57 | 20 | 20 | 40.1 s / 42.9 s |
| 50 | 0.1× | 5682 | 100～122 | 50 | 50 | 9.0 s / 9.5 s（真实时延下约 90 s） |
| 100 | 0.1× | 18626 | 165～198 | 100 | 100 | 14.6 s / 15.3 s（约 146 s） |
| 300 | 0.1× | 96672 | 258～338 | 300 | 300 | 25.1 s / 25.9 s（约 250 s） |

（50 个及以上并发请求时，为了缩短运行时间把时延缩为 50～100ms。排队行为与时延成比例，所以拒绝次数基本不受影响，耗时按 10 倍换算。）

对比：任务很少但并发请求很多时，拒绝次数就是精确的"超出容量"部分：

| 并发请求数 × array | caller-run 总次数 | 执行过 caller-run 的 Tomcat 线程数 |
|---|---|---|
| 300 × 1 | 40（= 300 − 260） | 40 |
| 300 × 5 | 758 | 280 |

**Tomcat 线程消耗：N 个（N = 并发请求数），上限是 `threads.max` = 300。**

- 每个请求始终只占一个线程，caller-run 不会让一个请求占用多个线程。
- 全部请求的总吞吐 ≈ (160 + N) / 平均时延：池子贡献 160 个，每个 caller-run 中的 Tomcat 线程再贡献 1 个。
  所以单个请求的耗时随 N 大致线性增长（20 个并发时约 40s）。
- 当 N 接近 300 时，Tomcat 已经没有空闲线程，新请求（包括 health check、actuator）只能堆在
  accept 队列里，这才是 `CallerRunsPolicy` 在生产上的真正风险。

## 与线上的差异

- 模拟用的是 `sleep`。真实的 RestTemplate 还受 HTTP 连接池限制：如果 `max-per-route` 只有 20，
  那么 160 个 worker 对同一个下游最多只有 20 个请求真正在途，其余都在等连接。这样池子的吞吐更低，
  拒绝次数会比上表更多，但"一个请求只占一个 Tomcat 线程"这一点不变。
- `allOf(...).join()` 本身就会让请求线程一直占着，直到最后一个任务完成，与是否 caller-run 无关。

## 如何复现

`thread-pool-caller-runs/CallerRunsSim.java` 使用真实的 `ThreadPoolTaskExecutor` 和同样的配置，用一个 300 线程、
线程名为 `http-nio-8096-exec-*` 的固定线程池模拟 Tomcat。在任务里判断当前线程名是否以 `http-nio`
开头，以此统计 caller-run。

```bash
R=/Users/ethan/Desktop/workspace/repos/org/springframework
CP=$R/spring-context/7.0.7/spring-context-7.0.7.jar:$R/spring-core/7.0.7/spring-core-7.0.7.jar:\
$R/spring-beans/7.0.7/spring-beans-7.0.7.jar:$R/spring-aop/7.0.7/spring-aop-7.0.7.jar:\
/Users/ethan/Desktop/workspace/repos/commons-logging/commons-logging/1.3.5/commons-logging-1.3.5.jar
cd creed-common-metrics/docs/thread-pool-caller-runs
java -cp "$CP" CallerRunsSim.java 1 500 1.0 5     # 情况 1：1 个请求 × 500，跑 5 次
java -cp "$CP" CallerRunsSim.java 20 500 1.0      # 情况 2：20 个并发请求 × 500
java -cp "$CP" CallerRunsSim.java 300 500 0.1     # 300 个并发请求，时延缩为 1/10
```

参数依次为：并发请求数、array 长度、时延缩放系数、[运行次数]。
