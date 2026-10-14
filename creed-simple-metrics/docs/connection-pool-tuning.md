# creed-simple-metrics 连接池与超时调优

> 针对 `logs/creed-simple-metrics.log` 中大量 `ConnectionRequestTimeoutException` 的根因分析与配置公式。
> 相关代码：`lb/LoadBalancedRestClientConfiguration.java`、`lb/RestClientSuppliers.java`、
> `lb/HttpPoolProperties.java`、`lb/ManagedHttpClientPool.java`、`config/CamelConfig.java`。
> §1–§6 只给方法与推荐值，未改 `application.yml`；**§7 连接生命周期已落地**（2026-10-15）。

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


## 7. 连接生命周期：TTL / keep-alive / 空闲回收（已落地）

> 2026-10-15 落地。三个池（`loadBalancedPool`、`healthCheckPool`、`camelHttpPool`）统一走
> `HttpPoolProperties` 的五个生命周期参数。下面的源码结论都出自 httpclient5 **5.5.2**（本模块实际版本），
> 不是凭印象。

### 7.1 改之前的问题

原来只配了 connect/socket timeout 和池大小，生命周期全用默认值：

| 机制 | 配置位置 | 5.5.2 默认值 | 何时生效 |
|---|---|---|---|
| `timeToLive` | `ConnectionConfig` | `null`（**永不过期**） | lease 时按**创建时间**检查 |
| `validateAfterInactivity` | `ConnectionConfig` | 2s（`resolveValidateAfterInactivity` 的兜底值） | lease 时空闲超过它就做 `isStale()` |
| keep-alive 时长 | 响应头 `Keep-Alive: timeout=N`，无头时取 `RequestConfig.connectionKeepAlive` | **3 分钟** | 连接归还时算出过期时间 |
| `closeExpired()` / `closeIdle(t)` | 需要主动调用 | **没人调** | 后台清理 |

带来三个问题：

1. **keep-alive 比服务端还长**。下游是 Tomcat，不发 `Keep-Alive` 头，客户端就按 3 分钟算；Tomcat 默认
   空闲 **60s**（`DEFAULT_CONNECTION_TIMEOUT = 60000`，`keepAliveTimeout` 未配时等于它）就关连接。
   `isStale()` 能发现正常 FIN，**发现不了 NAT/防火墙悄悄丢掉的连接**，那时首个请求就报
   `NoHttpResponseException` 或 reset。
2. **没有 TTL，连接一直绑在老实例上**。实例扩容或恢复后，已建立的连接不会重新分布。
3. **没有后台清理**。死连接只在被借出时才会被发现；低峰期一直占着 fd 和池名额，指标里的 `available` 也不准。

### 7.2 三个坑（读源码确认）

- **`setConnectionManagerShared(true)` 时，`HttpClientBuilder.evictIdleConnections()` /
  `evictExpiredConnections()` 不生效，也不报错**。`build()` 里只在 `if (!this.connManagerShared)` 分支创建
  `IdleConnectionEvictor`。本模块所有 client 都是 shared（池归 Spring/Camel 管），所以回收线程只能自己建、
  自己管生命周期。
- **keep-alive 策略是 client 级设置，不是 pool 级**。同一个池上的每个 `HttpClientBuilder` 都要单独设，否则
  那个 client 归还的连接仍按 3 分钟算。`clusterPool` 上除了自身的 request factory，还有 `artisanRestClient`
  （`HttpServiceClientConfig`）和 `cookieRelayRequestFactory` 两个自建 client，都要接
  `clusterPool.keepAliveStrategy()`。
- **camel-http 的 `connectionTimeToLive` 组件参数不是这里的 TTL**。它只作用于 camel-http 自己创建的池，
  而 `camelHttpConnectionManager` 是外部传进去的。TTL 要配在 `ConnectionConfig` 上。

另外，`IdleConnectionEvictor` 的线程用 `catch (Exception ignore)` 吞掉异常，**出错就静默退出**。
`closeExpired()`/`closeIdle()` 在池已关闭时直接 return，正常不会抛，所以可以接受；排查时先确认
`jstack` 里还有 `idle-connection-evictor-*` 线程。

### 7.3 怎么改的

| 参数（`creed.partner.http.*` / `creed.partner.health-check.http.*` / `creed.camel.http.*`） | 值 | 落到哪里 |
|---|---|---|
| `time-to-live` | 5m | `ConnectionConfig.setTimeToLive` |
| `validate-after-inactivity` | 2s | `ConnectionConfig.setValidateAfterInactivity`（显式写出默认值） |
| `keep-alive` | 30s | `RestClientSuppliers.keepAliveStrategy(cap)`：服务端给的更短就用服务端的，否则用上限。**只用于 `clusterPool` / `healthCheckPool`**，见下文 camel 池说明 |
| `max-idle-time` | 30s | evictor 的 `closeIdle` 阈值 |
| `eviction-interval` | 5s | evictor 的扫描周期（每轮 `closeExpired()` + `closeIdle()`） |

各值之间必须满足：

```
validate-after-inactivity (2s)
  < max-idle-time (30s) ≈ keep-alive (30s)
  < 路径上最短的空闲超时（Tomcat 60s；中间有 LB/NAT 时取它的）
  < time-to-live (5m)
```

evictor 的归属：

- `clusterPool` / `healthCheckPool`：`RestClientSuppliers.pool(...)` 创建并启动，
  `ManagedHttpClientPool.close()` 先停 evictor 再关池。
- `camelHttpPool`：单独的 `camelHttpPoolEvictor` bean（`initMethod="start"`, `destroyMethod="shutdown"`）。
  池由 `HttpComponent.doStop()` 关闭，早于 Spring 销毁这个 bean；池关闭后清理调用是空操作，所以这个顺序没问题。
- camel 池的 client **直接用 `DefaultConnectionKeepAliveStrategy.INSTANCE`**（即 HttpClient 默认策略，Tomcat
  不发头时按 3 分钟算），`creed.camel.http` 下没有 `keep-alive` 项。空闲连接的上限由 evictor 的
  `max-idle-time` 保证：空闲超过 30s 的连接最晚在下一轮扫描（5s）被关掉，最长约 35s，仍然小于 Tomcat 的 60s。
  代价是这条保证完全依赖 evictor 线程存活；如果 evictor 退出了，camel 池会回到"3 分钟 keep-alive"的状态。
  lease/response 两个超时仍是组件上的 `@Value`（`creed.camel.http.connection-request-timeout` /
  `response-timeout`）。
- camel-http 池原来散在 `@Value("${creed.camel.http.*}")` 里，现在通过 `Binder` 绑定成同一个
  `HttpPoolProperties`，默认值和原来的 `@Value` 兜底值一致（50/20/5s/10s/3s/10s）。

**仍然存在的竞态**：连接刚借出，服务端恰好关闭，这个窗口无法完全消除。这部分靠 `HttpClients.custom()` 默认的
`DefaultHttpRequestRetryStrategy` 兜底，它只对**幂等请求**在 `NoHttpResponseException` 时重试；POST 不会自动重试。

### 7.4 验证（2026-10-15，本机实测）

- 启动后 `jstack` 里有 **3 个** `idle-connection-evictor-1` 线程（cluster / health-check / camel 三个池各一个）。
- 调用 API-7（`POST /simple/camel/api/v1/orders/accounts`，它通过 `clusterPool` 打本机 `/mock/*`），然后轮询
  `/simple/actuator/metrics/httpcomponents.httpclient.pool.total.connections?tag=httpclient:loadBalancedPool&tag=state:available`：

  | 调用后 | 0s | 20s | 30s | 36s | 42s |
  |---|---|---|---|---|---|
  | available | 1 | 1 | 1 | **0** | 0 |

  空闲连接在 30–36s 之间被关掉，即 30s 阈值加一个 5s 扫描周期。改之前要大约 3 分钟。
- 单测：`RestClientSuppliersTest`（keep-alive 上限的两个方向、evictor 随池启停）和
  `CamelConfigHttpPoolTest`（`creed.camel.http.*` 绑定和默认值、evictor 启停）。
- 想看每条连接的关闭和重建：把 `org.apache.hc.client5.http.impl.io` 调到 DEBUG（同时注意 §6 的 TurboFilter
  只放行 "endpoint leased" 那一行，看生命周期时要临时去掉）。
