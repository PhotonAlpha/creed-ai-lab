# k6 stress test — creed-simple-metrics

A small [k6](https://k6.io) framework that stress-tests every REST API of the
`creed-simple-metrics` module: **100 virtual users, 10 rounds each**. Each round walks
the full API surface, so every endpoint is hit `VUS * ITERATIONS` times
(default `100 * 10 = 1000` rounds → `7000` requests).

## Layout

```
k6TestRun/
├── config.js        # target URL, load profile (VUS/ITERATIONS), endpoint list, thresholds
├── stress-test.js   # entry point: per-vu-iterations scenario, full-API sweep per round
├── lib/api.js       # request helper: tags + per-endpoint metrics + checks
├── run.sh           # convenience wrapper, exports a JSON summary to results/
└── README.md
```

## Endpoints under test

All live under `https://localhost:8096/camel/api` (Camel servlet, HTTPS):

| Method | Path                | Notes                                   |
|--------|---------------------|-----------------------------------------|
| GET    | `/hello`            | self-contained                          |
| GET    | `/time`             | self-contained                          |
| POST   | `/echo`             | JSON body                               |
| GET    | `/catalog`          | downstream → catalog-resource (8081/82) |
| GET    | `/order`            | downstream → order-resource (8091/92)   |
| GET    | `/aggregate`        | multicast catalog + order               |
| GET    | `/aggregate-notify` | aggregate + wire-tap notification       |

## Prerequisites

1. **Install k6**: `brew install k6` (macOS) or see the
   [install docs](https://k6.io/docs/get-started/installation/).
2. **Start `creed-simple-metrics`** (HTTPS on 8096).
3. **Start the downstream resource servers** if you want the `/catalog`, `/order`,
   `/aggregate`, `/aggregate-notify` routes to succeed — `catalog-resource` (8081, 8082)
   and `order-resource` (8091, 8092). Without them those four endpoints will error and the
   `http_req_failed` threshold will trip. The two self-contained endpoints (`/hello`,
   `/time`) and `/echo` work standalone.

> TLS: the listener uses a self-signed Creed CA cert and `clientAuth=NONE`, so the test
> sets `insecureSkipTLSVerify: true` and needs **no** client certificate.

## Run

```bash
cd k6TestRun
./run.sh                          # 100 VUs x 10 rounds → HTML report (results/report-<ts>.html)
# or directly:
k6 run stress-test.js
```

### Output formats (`OUTPUT`)

`OUTPUT` selects where results go; comma-separate to combine. Default is `html`.

| `OUTPUT`            | Result                                                              |
|---------------------|--------------------------------------------------------------------|
| `html` (default)    | self-contained HTML report `results/report-<ts>.html` (k6 web dashboard export) |
| `json`              | `results/summary-<ts>.json`                                         |
| `prometheus`        | push live metrics to Prometheus → Grafana dashboard                |
| `html,prometheus`   | HTML report **and** live Prometheus metrics                        |
| `both`              | alias for `json,prometheus` (back-compat)                          |

```bash
OUTPUT=html ./run.sh                       # HTML report only
OUTPUT=html,prometheus ./run.sh            # HTML report + live Grafana
# direct equivalent of the HTML report:
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=results/report.html k6 run stress-test.js
```

> The HTML report uses k6's **built-in web dashboard** (no external libraries), so it works
> fully offline. Open the generated `results/report-*.html` in any browser.

### Overrides

| Variable     | Default                               | Meaning                          |
|--------------|---------------------------------------|----------------------------------|
| `VUS`        | `100`                                 | number of virtual users          |
| `ITERATIONS` | `10`                                  | rounds per user                  |
| `BASE_URL`   | `https://localhost:8096/camel/api`    | target base URL                  |
| `MAX_DURATION` | `15m`                               | hard cap on the scenario         |

```bash
k6 run -e VUS=200 -e ITERATIONS=20 stress-test.js
k6 run -e BASE_URL=https://localhost:8096/camel/api stress-test.js
```

## Reading the results

- **Built-in metrics**: `http_req_duration` (p95/p99), `http_req_failed`, `iterations`, etc.
- **Per-endpoint breakdown**: custom `endpoint_duration` trend and `endpoint_errors` counter,
  tagged with `endpoint` and `method`. Filter in the summary or push to a backend.
- **Thresholds** (run fails / non-zero exit if breached): error rate `<1%`,
  `p95 < 2s`, `p99 < 5s`, check success `>99%`. Tune them in `config.js`.

`./run.sh` also writes `results/summary-<timestamp>.json` for archiving / comparison.

## Live monitoring → Prometheus + Grafana

The run can push live metrics into the repo's monitoring stack
(`../monitoring/docker-compose.yml`) using k6's native **Prometheus remote write** output —
no extra services needed.

1. **Start the monitoring stack** (Prometheus already has the remote-write receiver enabled):
   ```bash
   cd ../monitoring && docker compose up -d
   ```
2. **Run with Prometheus output**:
   ```bash
   cd ../k6TestRun
   OUTPUT=prometheus ./run.sh         # live metrics only
   OUTPUT=both ./run.sh               # live metrics + JSON summary
   ```
   Or directly:
   ```bash
   K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
   K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,min,max' \
   k6 run -o experimental-prometheus-rw stress-test.js
   ```
3. **Open Grafana** at <http://localhost:3000> (admin/admin) → dashboard
   **"k6 — creed-simple-metrics stress test"** (folder `k6`). It shows active VUs, total
   requests, HTTP error rate, check success rate, requests/sec & errors/sec by endpoint,
   and overall + per-endpoint latency. Set the time range to *Last 15 minutes* with 5s refresh.

### How it's wired

| Piece | Where | What changed |
|-------|-------|--------------|
| Remote-write receiver | `monitoring/docker-compose.yml` | added `--web.enable-remote-write-receiver` to Prometheus |
| Dashboard (provisioned) | `monitoring/dashboards/k6/k6-stress-test.json` | auto-loaded into the `k6` folder |
| Push config | `run.sh` / env | `K6_PROMETHEUS_RW_SERVER_URL`, `K6_PROMETHEUS_RW_TREND_STATS` |

Override the push target with `PROMETHEUS_RW_URL=... OUTPUT=prometheus ./run.sh`.

k6 metric names in Prometheus are prefixed `k6_` (e.g. `k6_http_reqs_total`,
`k6_http_req_duration_p95`, `k6_http_req_failed_rate`, and the custom
`k6_endpoint_duration_p95` / `k6_endpoint_errors_total`, all carrying the `endpoint` label).
