# Grafana / observability notes

[简体中文](#观测说明简体中文)

There is **no separate Grafana stack for this project**. `creed-resource-env-matrix` is an ordinary
member of the creed-ai-lab mesh and reports into the stack that already exists at
[`monitoring/`](../../monitoring) in the repository root.

## How its metrics get there

The module depends on `creed-common-metrics` and its `application.yml` does
`spring.profiles.include: actuator`, which activates
[`application-actuator.yml`](../../creed-common-metrics/src/main/resources/application-actuator.yml).
That puts it in **push mode**, the same as the catalog / order / payment resource servers:

```
app (OTLP exporter) --> otel-collector :4318 --> Prometheus scrapes collector :8889 --> Grafana :3000
```

Because the single `otel-collector` Prometheus job already covers every push-mode module, **adding
this service needed no change to `monitoring/prometheus.yml`**. (Only `creed-simple-metrics` is
different — it is pull-mode and has its own scrape job.)

## Bring the stack up

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

- Grafana <http://localhost:3000> — dashboards provisioned from `monitoring/dashboards/`,
  main one is `otel-jvm-statistics` (uid `otel-jvm-statistics`).
- Prometheus <http://localhost:9090>
- Collector scrape endpoint <http://localhost:8889/metrics>

Select this service with the dashboard's `$service` / `$instance` variables — they key off the
`service_name` / `service_instance_id` resource attributes, which the actuator profile pins to
`${spring.application.name}` and `${name}:${port}`.

## Caveats specific to this module

- The **`dev` profile excludes `SslObservabilityAutoConfiguration`** (and disables the SSL health
  indicator), because that binder eagerly opens every declared SSL bundle at startup and the dev
  profile deliberately runs without the mesh PKI. You therefore get no
  `ssl_certificate_expiry_*` series when running with `dev` — use `primary` / `secondary` for that.
- Don't guess metric names. Read them live:
  ```bash
  curl -s localhost:8889/metrics | grep -i env_matrix
  ```
- Endpoint **health** shown in the UI is not a Prometheus metric and is mocked by default — see the
  health-check section of the [README](../README.md). Don't build alerts on it.

---

## 观测说明（简体中文）

本项目**没有独立的 Grafana 栈**。`creed-resource-env-matrix` 是 creed-ai-lab 服务网格中的普通成员，
数据上报到仓库根目录下已有的 [`monitoring/`](../../monitoring) 栈。

### 指标如何上报

该模块依赖 `creed-common-metrics`，且 `application.yml` 中 `spring.profiles.include: actuator`
启用了 [`application-actuator.yml`](../../creed-common-metrics/src/main/resources/application-actuator.yml)，
因此与 catalog / order / payment 一样处于**推送模式**：

```
应用（OTLP 导出）--> otel-collector :4318 --> Prometheus 抓取 collector :8889 --> Grafana :3000
```

由于 Prometheus 中单个 `otel-collector` job 已覆盖所有推送模式的模块，**新增本服务无需修改
`monitoring/prometheus.yml`**。（只有 `creed-simple-metrics` 是拉取模式，需要独立的 scrape job。）

### 启动监控栈

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

- Grafana <http://localhost:3000> —— 仪表盘由 `monitoring/dashboards/` 预置，主看板为
  `otel-jvm-statistics`（uid `otel-jvm-statistics`）。
- Prometheus <http://localhost:9090>
- Collector 抓取端点 <http://localhost:8889/metrics>

在看板的 `$service` / `$instance` 变量中选择本服务；它们取自 `service_name` / `service_instance_id`
资源属性，actuator profile 将其固定为 `${spring.application.name}` 与 `${name}:${port}`。

### 本模块特有的注意事项

- **`dev` profile 排除了 `SslObservabilityAutoConfiguration`**（并关闭 SSL 健康指示器），因为该
  binder 会在启动时急切打开所有已声明的 SSL bundle，而 `dev` profile 刻意在没有网格 PKI 的情况下运行。
  因此使用 `dev` 时不会有 `ssl_certificate_expiry_*` 指标 —— 需要时请使用 `primary` / `secondary`。
- 不要凭记忆猜指标名，实时查看：
  ```bash
  curl -s localhost:8889/metrics | grep -i env_matrix
  ```
- UI 中显示的端点**健康状态**不是 Prometheus 指标，且默认是模拟的 —— 详见 [README](../README.zh-CN.md)
  的健康检查一节。**不要**基于它配置告警。
