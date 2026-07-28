# Tomcat 排队估算：PromQL → Splunk SPL

`otel-jvm-statistics.json` 面板 705（*Tomcat Estimated Request Backlog*）的 PromQL 到 Splunk SPL 的
等价翻译。两种 Splunk 数据形态各给一版：**metrics index**（`mstats`）和**本仓库现有的
`actuator-metrics` 日志行**（事件索引）。

## 1. 源表达式与语义

```promql
clamp_min(
    sum by (name) (tomcat_connections_current{service_name="$service", service_instance_id="$instance"})
  - sum by (name) (tomcat_threads_busy{service_name="$service", service_instance_id="$instance"})
, 0)
* on (name) group_left ()
(   sum by (name) (tomcat_threads_busy{service_name="$service", service_instance_id="$instance"})
 >= bool
    sum by (name) (tomcat_threads_config_max{service_name="$service", service_instance_id="$instance"}))
```

拆成四步，SPL 逐条对应：

| # | PromQL | 含义 | SPL |
|---|---|---|---|
| 1 | `sum by (name)` | 按 connector 名聚合（本仓库每实例仅 1 个 connector，实为恒等） | `BY name` / `stats ... BY` |
| 2 | `a - b` | 开着但没有工作线程的连接数 | `eval` 减法 |
| 3 | `clamp_min(..., 0)` | 负值截为 0 | `if(x > 0, x, 0)` |
| 4 | `>= bool` … `*` | 线程池打满时才计数的**门控**（0/1 相乘） | `eval gate = if(...,1,0)` 再相乘 |

**门控是这个公式的关键。** 不加门控，空闲的 keep-alive 连接会被算成"排队"。Tomcat 只有在
每个 worker 线程都被占满之后才可能排队，此时"连接数 − busy 线程数"才等于等不到线程的请求数。

> ⚠️ **不要减 `tomcat_connections_keepalive_current`。** Tomcat NIO 的 `keepAliveCount` 统计的是
> 所有注册在 poller 上的 socket，**包含正在处理请求的那些**。实测满载时 `keepalive == current - 1`
> （偶尔还会 `keepalive > current`，见 §5 的 01:03:22 采样），减掉它会让指标在**连接器饱和的那一刻
> 变成负数**——与意图完全相反。

## 2. 方案 A：Splunk metrics index（`mstats`）

指标以 metrics index 落库时（OTel collector 的 Splunk HEC exporter 等）。指标名按接入链路可能是
点号（Micrometer 原名）或下划线（经 Prometheus exporter），按实际情况二选一。

```spl
| mstats
    latest(tomcat.connections.current) AS conn_current,
    latest(tomcat.threads.busy)        AS threads_busy,
    latest(tomcat.threads.config.max)  AS threads_max
  WHERE index=creed_metrics
    AND "service.name"="$service$"
    AND "service.instance.id"="$instance$"
  BY name span=15s
| eval gate         = if(isnotnull(threads_max) AND threads_max > 0 AND threads_busy >= threads_max, 1, 0)
| eval est_queued   = if(conn_current - threads_busy > 0, conn_current - threads_busy, 0) * gate
| eval free_threads = if(threads_max - threads_busy > 0, threads_max - threads_busy, 0)
| timechart span=15s max(est_queued) AS "est. queued", max(free_threads) AS "free threads" BY name
```

- `span=15s` 对齐 Prometheus 的 `scrape_interval: 15s`（见 `monitoring/prometheus.yml`）。
- `latest()` 而非 `avg()`：gauge 的瞬时值语义等价于 Prometheus instant vector 取最近样本。
  Splunk < 8.0 的 `mstats` 没有 `latest`，退化用 `avg()`——span 与采集间隔一致时两者基本相同。
- `threads_max > 0` 是 PromQL 里没有的**加固**，理由见 §4。

## 3. 方案 B：本仓库的 `actuator-metrics` 日志行（事件索引）

`ActuatorMetricsLogger`（`creed-common-metrics`）每 10s 打一批扁平 key=value 行到
`logs/<app>-metrics.log`，KV_MODE=auto 直接抽字段，无需 props.conf。实测行样例：

```
2026-07-29 01:05:03.669 actuator-metrics metric=tomcat_threads_busy application=creed-simple-metrics value=0.0
2026-07-29 01:05:03.669 actuator-metrics metric=tomcat_connections_current application=creed-simple-metrics value=0.0
```

```spl
index=creed_app_logs "actuator-metrics"
    application="$service$"
    metric IN (tomcat_connections_current, tomcat_threads_busy, tomcat_threads_config_max)
| bin _time span=10s
| eval conn_current = if(metric=="tomcat_connections_current", value, null())
| eval threads_busy = if(metric=="tomcat_threads_busy",        value, null())
| eval threads_max  = if(metric=="tomcat_threads_config_max",  value, null())
| stats max(conn_current) AS conn_current,
        max(threads_busy) AS threads_busy,
        max(threads_max)  AS threads_max
    BY _time, application, host
| eval gate         = if(isnotnull(threads_max) AND threads_max > 0 AND threads_busy >= threads_max, 1, 0)
| eval est_queued   = if(conn_current - threads_busy > 0, conn_current - threads_busy, 0) * gate
| eval free_threads = if(threads_max - threads_busy > 0, threads_max - threads_busy, 0)
| timechart span=10s max(est_queued) AS "est. queued", max(free_threads) AS "free threads" BY host
```

`span=10s` 对齐 `creed.metrics.jvm-memory.interval-ms:10000` 的调度周期。三个 `eval` 的行转列也可以
写成更紧凑的 `| eval {metric} = value`，但显式写法更好读、也更容易加字段。

这里用 `max()` 而不是方案 A 的 `latest()`：`bin` 会把桶内所有事件的 `_time` 改写成桶起点，
`latest()` 依赖 `_time` 排序，此时就没有确定的"最新"了。span 与打点周期都是 10s、每个指标每桶
恰好一个样本，`max()` 即那个唯一值。若把 span 放大到多个打点周期，改用
`| eval b=_time-(_time%<span>) | stats latest(...) BY b, ... | rename b AS _time`——保留原始
`_time` 供 `latest()` 排序。

### 与 PromQL 的三处字段差异（本仓库特有）

| PromQL | 日志行 | 说明 |
|---|---|---|
| `service_name` | `application` | 即 `spring.application.name` |
| `service_instance_id` | *（无）* | 用 `host` + `source`（每实例一个日志文件）区分 |
| `name`（connector） | *（无）* | **见下** |

**`sum by (name)` 在方案 B 里无法复现。** `loggingTomcatMetrics()` 走
`loggingMetricKeys(..., null)` → `emit(name, "", ...)`，**不带任何 tag**；取数用
`MetricsEndpoint.metric(type, null)`，已经在 tag 维度上聚合过了。所以日志里的值等价于
`sum()`（无 `by`）而不是 `sum by (name)`。本仓库每个应用只有一个 connector，两者数值相同；
但多 connector 的应用（如同时开 HTTP + HTTPS）在 Splunk 侧会被**合并成一条**且无法拆分——
要拆就得改 `ActuatorMetricsLogger`，让 Tomcat 这批指标和 `executor.*` / `httpcomponents.*`
一样带上 tag（那两类是走 `loggingMeters()` 的，行里有 `httpclient=camelHttpPool` 这样的标签）。

## 4. 为什么多了 `threads_max > 0`

`busy >= config_max` 在**两者都是 0** 时成立 → `gate=1`。数据健康时不会发生（`config_max` 恒为
50/200），但只要采集侧出问题吐出 0，门控就会误开，`est_queued` 直接等于连接数——假告警。
Splunk 侧尤其要防，因为本仓库当前就处在这个状态（§6）。

同样的加固也可以回写到 Grafana 面板（`and tomcat_threads_config_max > 0`）；面板现在没加，
因为 Prometheus 侧数据是好的。需要的话我可以补上。

## 5. 验证数据

2026-07-29 01:00 的 k6 压测（100 VU × 10 轮，`k6TestRun/run.sh`），直接采样 Prometheus 里的
面板表达式：

```
01:01:30 conn=102  ka=101  busy=50 | est.queued=52  free=0    ← 饱和
01:02:41 conn=135  ka=134  busy=50 | est.queued=85  free=0
01:03:22 conn=110  ka=111  busy=50 | est.queued=60  free=0    ← ka > conn，旧公式会得 -51
01:05:03 conn=83   ka=82   busy=50 | est.queued=33  free=0
01:05:13 conn=35   ka=34   busy=33 | est.queued=0   free=17   ← 退出饱和，门控关闭
01:05:43 conn=2    ka=1    busy=0  | est.queued=0   free=50   ← 空闲
```

`busy` 全程钉在 50/50（`server.tomcat.threads.max` 默认 50），`free threads` 归零期间
`est. queued` 在 33–85 之间；线程一空出来立刻回 0，空闲期无假阳性。同一时段 200 线程的
`creed-resource-catalog` 始终为 0。

k6 本身的阈值是失败的（8.56% 请求失败、p95 60s、800 个 iteration 被丢弃）——这正是刻意打满
的预期结果，不是查询的问题。

## 6. ⚠️ 现状：方案 B 在 `creed-simple-metrics` 上取不到数

`logs/creed-simple-metrics-metrics.log` 里 **131 个采样点的 6 个 Tomcat 指标全部是 `value=0.0`**，
包括上面压测满载的时间段（Prometheus 同期是 `busy=50, conn=140`）。`tomcat_threads_config_max`
也是 `0.0` 而不是 `50`。所以方案 B 的查询在这个模块上只会返回 0。

对照组 `creed-gateway-partner` 是正常的（`tomcat_threads_config_max=200.0`、
`tomcat_connections_current=1.0/2.0`），说明**不是查询写错，是 simple-metrics 的指标采集侧问题**
（`MetricsEndpoint` 在该模块拿不到 Tomcat meter；它是唯一用 `micrometer-registry-prometheus`
拉模式的模块，其 `/actuator/prometheus` 的数值是正常的）。

**方案 A / Grafana 面板不受影响**——那条链路的数据经 §5 实测是准的。方案 B 要在
simple-metrics 上可用，得先修 `ActuatorMetricsLogger` 的取数；根因未定位，尚未修。

## 相关

- 面板定义：`monitoring/dashboards/otel-jvm-statistics.json`（panel id 705）
- 指标名映射与 `keepAliveCount` 坑：`.claude/skills/creed-platform/SKILL.md`
- 日志行格式：`creed-common-metrics/src/main/java/com/creed/metrics/ActuatorMetricsLogger.java`
