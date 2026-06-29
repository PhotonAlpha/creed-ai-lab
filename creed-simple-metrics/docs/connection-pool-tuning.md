# creed-simple-metrics 连接池与超时调优

> 针对 `logs/creed-simple-metrics.log` 中大量 `ConnectionRequestTimeoutException` 的根因分析与配置公式。
> 相关代码：`web/LoadBalancedRestClientConfiguration.java`、`lb/RestClientSuppliers.java`。
> 本文只给方法与推荐值，**暂不改 `application.yml`**。

## 1. 根因（从日志读出的事实）

| 信号 | 日志值 | 含义 |
|---|---|---|
| 异常 | `ConnectionRequestTimeoutException: deadline 3000ms, actual 3001ms` | 不是下游超时，而是**从连接池租借连接**等了 3s 没等到 |
| 池配置 | `max-total=50`, `max-per-route=50` | 整池只有 50 条连接 |
| 热路由数 | 4 个实例：8081/8082(catalog)、8091/8092(order) | 50 条要被 4 条 route 瓜分 |
| 下游延迟（连接持有时间） | median **2.0s**、p90 **7.0s**、max **10.0s** | 10s 正好顶到 `response-timeout`，连接被长时间占用 |
| 持有 ≥3s 的调用 | **1376 次** | 超过 `connection-request-timeout(3s)`，必然把排队者拖爆 |
| 入站并发 | Tomcat `max-threads` 未配 = **默认 200**；峰值已见 112 个 exec 线程、单秒 36 笔 | 最多 200 个线程同时来抢 50 条连接 |

**结论**：上游 Tomcat 最多 200 线程，每个线程一次下游调用要占 1 条连接，而池只有 50 条 → 150 个线程在池上排队；又因为下游持有连接普遍 2–7s（远大于 3s 的租借超时），排队者必然抛 `ConnectionRequestTimeoutException`。

> **池容量 < 上游并发，且连接持有时间 > 租借超时**，两个条件同时成立才爆。

## 2. 配置公式

### 2.1 连接持有时间（一切的基础）

```
T_hold ≈ connect + TLS握手 + 下游处理 + 传输  ≈ 下游响应延迟的 p95
```

本系统 p90 = 7s、p95 ≈ 8s（且被 `response-timeout=10s` 截顶）。

### 2.2 池大小 —— Little's Law

```
所需在途连接 N = λ_peak × T_hold

max-total      = ceil( λ_peak × T_hold(p95) × headroom )         headroom = 1.3 ~ 1.5
max-per-route  = ceil( λ_route_peak × T_hold(p95) × headroom )   夹在 [max-total / N_route , max-total]
```

另有一条**硬上限规则**（专治这个异常）：

```
max-total ≥ server.tomcat.threads.max          // 每个入站请求只发 1 次下游调用时
```

只要池 ≥ 上游最大并发线程数，池永远不会成为瓶颈，`ConnectionRequestTimeoutException`
从根上消失。两者取**实际更小可行值**：下游能扛多少，就把 Tomcat 也压到多少。

### 2.3 各超时

```
connect-timeout            = TCP+TLS 握手预算（同机/局域网 1–2s；跨网 3–5s）
socket-timeout             = response-timeout（包间静默保护，设成与之相等或略大）
response-timeout           = 下游 SLA = p99 延迟 × 1.2   （= 你愿意为一次下游等的上限）
connection-request-timeout = 客户端总预算 − response-timeout − connect-timeout
                             （= 允许在池里排队的时间；池足够大时它只需覆盖瞬时微突发）
```

核心不变式（时间预算守恒）：

```
入站请求总 SLA  ≥  connection-request-timeout + connect-timeout + response-timeout
```

## 3. 代入本系统的推荐值

峰值 λ ≈ 40 req/s、4 条热 route、T_hold(p95) ≈ 8s。纯 Little's Law 要
`40 × 8 × 1.3 ≈ 360` 条 —— 这说明**下游太慢**是真问题。两条路一起走：

**A. 先把下游持有时间压下来**（让连接更快回收）

- `response-timeout` 从 10s → **5s**（median 2s、p90 7s，5s 能保住绝大多数正常请求，
  快速甩掉长尾，连接周转翻倍）。

**B. 池对齐上游并发**

```yaml
server:
  tomcat:
    threads:
      max: 100          # 把入站并发收到下游扛得住的水平，别让 200 线程涌向池

creed:
  partner:
    http:
      max-total: 100              # ≥ tomcat.threads.max，池不再是瓶颈
      max-per-route: 40           # ≈ max-total / 热route数(4) 再留突发余量，单 route 不饿死他人
      connect-timeout: 2s         # 局域网/同机握手
      socket-timeout: 5s          # = response-timeout
      response-timeout: 5s        # 下游 SLA，砍掉 10s 长尾
      connection-request-timeout: 2s   # 池够大后排队极少；总预算 9s = 2 + 2 + 5
    health-check:
      http:                       # 健康检查池保持隔离、小而短，无需改
        max-total: 10
        max-per-route: 5
        connect-timeout: 2s
        socket-timeout: 2s
        response-timeout: 2s
        connection-request-timeout: 2s
```

> 这些当前都是 `@Value` 默认值，加上 YAML 即生效，无需改 Java 代码。

## 4. 验证口径

改完盯三个 Micrometer 指标（已接 `PoolingHttpClientConnectionManagerMetricsBinder`，
名字 `loadBalancedPool`）：

- `httpcomponents.httpclient.pool.total.pending`（排队数）应趋近 0；
- `...pool.total.connections{state=leased}` 长期 < `max`（不顶满）；
- 若 `pending` 仍 > 0，说明下游产能不够，**只能降 Tomcat 并发或修下游**，
  继续加池只会把内存和下游一起拖垮。

## 5. Per-route 可观测性（全局指标的盲区）

`PoolingHttpClientConnectionManagerMetricsBinder` 只读 `getTotalStats()`，**没有 per-route 维度**，
只暴露：

```
httpcomponents.httpclient.pool.total.max
httpcomponents.httpclient.pool.total.connections{state=available|leased}
httpcomponents.httpclient.pool.total.pending
httpcomponents.httpclient.pool.route.max.default   # 只是 maxPerRoute 的“配置值”，非某条 route 的实时占用
```

> **真正的绑定约束往往是 `maxPerRoute`**：流量高度集中在某一个 host 时，那条 route 顶到
> `route allocated: 20 of 20`，而全局还显示 `total allocated: 40 of 50`——“还有余量”纯属假象。
> 全局指标看不见这一层。

### 5.1 自定义 MultiGauge（in-JVM 最直接）

> ⚠️ **下面的 `PerRoutePoolMetrics` 目前尚未在代码库中创建，仅为本文档的参考实现。**
> 落地时需新建文件 `com/creed/simple/web/PerRoutePoolMetrics.java`，并在某处开启 `@EnableScheduling`。
> （对比：§6 的 `PoolLeasedOnlyTurboFilter` 已建文件并接入 `logback-spring.xml`。）

路由是动态出现的（首次调用后才进池），用 Micrometer `MultiGauge` + 定时刷新逐路由打 tag：

```java
package com.creed.simple.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 暴露 per-route 池占用，补足官方 binder 只看全局的盲区。需在某处开启 @EnableScheduling。 */
@Component
public class PerRoutePoolMetrics {

    private record Pool(String name, PoolingHttpClientConnectionManager cm,
                        MultiGauge leased, MultiGauge available,
                        MultiGauge pending, MultiGauge max) {}

    private final List<Pool> pools;

    public PerRoutePoolMetrics(
            MeterRegistry registry,
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager clusterPool,
            @Qualifier("healthCheckHttpConnectionManager") PoolingHttpClientConnectionManager healthPool) {
        this.pools = List.of(
                build(registry, "loadBalancedPool", clusterPool),
                build(registry, "healthCheckPool", healthPool));
    }

    private static Pool build(MeterRegistry r, String name, PoolingHttpClientConnectionManager cm) {
        String metric = "httpcomponents.httpclient.pool.route.connections";
        return new Pool(name, cm,
                MultiGauge.builder(metric).tag("httpclient", name).tag("state", "leased").register(r),
                MultiGauge.builder(metric).tag("httpclient", name).tag("state", "available").register(r),
                MultiGauge.builder(metric).tag("httpclient", name).tag("state", "pending").register(r),
                MultiGauge.builder("httpcomponents.httpclient.pool.route.max").tag("httpclient", name).register(r));
    }

    @Scheduled(fixedRate = 10_000)
    public void refresh() {
        for (Pool p : pools) {
            List<MultiGauge.Row<?>> leased = new ArrayList<>();
            List<MultiGauge.Row<?>> available = new ArrayList<>();
            List<MultiGauge.Row<?>> pending = new ArrayList<>();
            List<MultiGauge.Row<?>> max = new ArrayList<>();
            for (HttpRoute route : p.cm().getRoutes()) {
                HttpHost t = route.getTargetHost();
                Tags tags = Tags.of("target", t.getHostName() + ":" + t.getPort());
                PoolStats s = p.cm().getStats(route);
                leased.add(MultiGauge.Row.of(tags, s.getLeased()));
                available.add(MultiGauge.Row.of(tags, s.getAvailable()));
                pending.add(MultiGauge.Row.of(tags, s.getPending()));
                max.add(MultiGauge.Row.of(tags, s.getMax()));
            }
            p.leased().register(leased, true);      // overwrite=true：route 消失时清掉旧行
            p.available().register(available, true);
            p.pending().register(pending, true);
            p.max().register(max, true);
        }
    }
}
```

对应 PromQL：

```promql
# 每个下游 host 的占用率——这才是 maxPerRoute 的绑定约束
httpcomponents_httpclient_pool_route_connections{httpclient="loadBalancedPool",state="leased"}
  / httpcomponents_httpclient_pool_route_max{httpclient="loadBalancedPool"}

# 单条 route 顶满告警：leased 触到 per-route 上限且有人在排队
httpcomponents_httpclient_pool_route_connections{state="leased"}
  >= httpcomponents_httpclient_pool_route_max
and on(target)
httpcomponents_httpclient_pool_route_connections{state="pending"} > 0
```

**单 route 顶满 + 全局有余 = 该调高 `maxPerRoute`（而非 `maxTotal`）的明确信号。**

### 5.2 其它方案对比（按改动量由小到大）

> 对 Apache HttpClient5 这一层，社区/官方**没有开箱即用的 per-route 池指标**，自定义 MultiGauge
> 是公认做法。其余“更好的方法”本质是**换一层去观测**。

| 方案 | 改动量 | per-route 可见性 | 额外收益 |
|---|---|---|---|
| 自定义 MultiGauge（§5.1） | 小 | ✅ leased/pending/max | 无，纯观测 |
| **Resilience4j Bulkhead** | 小–中 | ✅ `available/max` 免费 | **快速失败**，替代 3s 干等 |
| OpenTelemetry http.client 指标 | 小（接 agent） | ⚠️ 仅延迟/错误按 `server.address` | 全链路 trace |
| Reactor Netty `ConnectionProvider.metrics(true)` | 大（换客户端） | ✅ 原生按 `remote.address` | 响应式栈统一 |
| Envoy / 服务网格 | 大（平台级） | ✅ 最全（`upstream_rq_pending_overflow`） | 熔断/重试/mTLS 下沉 |

- **Resilience4j Bulkhead**：把隐形的 `maxPerRoute` 提成显式舱壁，按 service-id/host 命名，
  Micrometer 免费给 `resilience4j_bulkhead_available_concurrent_calls{name=...}`，且不再傻等 3s。
- **Reactor Netty**：若改用 WebClient，`.metrics(true)` 天生按 `remote.address` 分组，正好补上 HC5 缺的维度。
- **Envoy/mesh**：`envoy_cluster_upstream_rq_pending_overflow` 就是“这个 upstream 池满了”的精确信号。

务实路线：**MultiGauge 解“看得见” + Resilience4j Bulkhead 解“快速失败且免费可观测”**，两者叠加即可。

## 6. 只保留 `PoolingHttpClientConnectionManager` 的 "endpoint leased" 这一行

开 DEBUG 看池占用时，`PoolingHttpClientConnectionManager` 会刷大量行
（releasing / released / acquired / executing exchange / keep-alive）。这些都来自**同一个 logger、同一 level**，
靠 logger 名或 level 无法区分，只能按**消息内容**过滤。

HC5 的 leased 行格式串是 `LOG.debug("{} endpoint leased {}", ...)`，"endpoint leased" 是字面量、
跨 HC5 5.x 稳定，因此用一个全局 **TurboFilter** 匹配 format 串最干净：
**无需 Janino、无需专用 appender，现有 logger→CONSOLE/BUSINESS 路由不变。**

> 为何不用纯 XML 的 `EvaluatorFilter`：它依赖 Janino（本项目没有），且 filter 是 per-appender 的——
> 而 CONSOLE/BUSINESS 与 root 共享，给它们加 filter 会把所有人的日志一起删掉。

```java
// com/creed/simple/web/PoolLeasedOnlyTurboFilter.java
public class PoolLeasedOnlyTurboFilter extends TurboFilter {
    private static final String POOL_LOGGER =
            "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null || !POOL_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;        // 非目标 logger：交给正常流程
        }
        return (format != null && format.contains("endpoint leased"))
                ? FilterReply.NEUTRAL          // 放行这一行
                : FilterReply.DENY;            // 丢弃该 logger 其余所有行
    }
}
```

```xml
<!-- logback-spring.xml，紧跟 <include .../defaults.xml> 之后 -->
<turboFilter class="com.creed.simple.web.PoolLeasedOnlyTurboFilter"/>
```

> ⚠️ **关键坑：`format == null` 必须返回 NEUTRAL，不能 DENY。**
> HC5 每行日志外面都包了 `if (LOG.isDebugEnabled())`，而 logback 在这个探测里调用 turbo-filter 链时
> 传的是 **`format=null`**（只是 level 探测，不是真正的日志调用）。若在 null 上 `DENY`，会让
> `isDebugEnabled()` 返回 `false`，HC5 **根本不会执行** `debug("{} endpoint leased {}", ...)`，于是你
> 一行都看不到、也永远拿不到真正的 format 串。正确做法：null 探测放行(NEUTRAL)，内容匹配只在真正的
> 日志调用（`format != null`，携带 `"{} endpoint leased {}"`)上生效。

### 6.1 TurboFilter vs 普通 Filter

| | 普通 `Filter` | `TurboFilter` |
|---|---|---|
| 挂载点 | 单个 appender | 全局 `LoggerContext` |
| 触发时机 | 事件已创建、过 level、到 appender 之后 | **每次** `log.x()`，在创建 `ILoggingEvent` **之前**、level 判断之前 |
| 拿到的数据 | 完整 `ILoggingEvent`（已格式化） | 原始 format 串 + params（未替换） |

### 6.2 性能说明

- **过滤器本身可忽略**：每条日志只做一次引用判断 + `String.equals`(logger 名)，绝大多数在首字符就
  fail-fast；只有 pool logger 才走 `contains`。无锁、无状态、线程安全。
- **对过滤目标反而更省**：DENY 发生在事件创建 / `{}` 替换 / 写盘**之前**，被丢的行不拼串、不落盘，
  比“EvaluatorFilter 先造事件再扔”省得多（日志真正的瓶颈是 I/O）。
- **真正的开销在“开 DEBUG”本身**，不在 TurboFilter：HC5 的 `formatStats(...)` 作为方法参数会被 Java
  **先求值**，且外层 `isDebugEnabled()` 为真，所以每次 lease/release 仍会算一遍 stats；TurboFilter 砍得掉
  输出量与 I/O，砍不掉这部分 JVM 内计算。
- **想彻底零日志开销**：别靠 DEBUG 看占用，改用 §5 的 per-route 指标，再把该 logger 调回 `INFO`，
  `formatStats` 的逐次计算也一并消失。**日志看事件流，指标看占用率，生产首选后者。**
