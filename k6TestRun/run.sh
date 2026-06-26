#!/usr/bin/env bash
# Convenience wrapper to run the creed-simple-metrics k6 stress test.
#
# Usage:
#   ./run.sh                          # default: html report (results/report-<ts>.html)
#   VUS=200 ITERATIONS=20 ./run.sh    # override load
#   BASE_URL=https://host:8096/camel/api ./run.sh
#
# OUTPUT selects the result sink(s); comma-separate to combine:
#   OUTPUT=html ./run.sh              # self-contained HTML report (k6 web dashboard export)
#   OUTPUT=json ./run.sh              # results/summary-<ts>.json
#   OUTPUT=prometheus ./run.sh        # push live metrics to Prometheus (Grafana dashboard)
#   OUTPUT=html,prometheus ./run.sh   # HTML report + live Prometheus metrics
#   OUTPUT=both ./run.sh              # alias for json,prometheus (back-compat)
#
# Prometheus output requires the monitoring stack running (monitoring/docker-compose.yml)
# with the remote-write receiver enabled (already configured), reachable at
# PROMETHEUS_RW_URL (default http://localhost:9090/api/v1/write).
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 not found. Install it: 'brew install k6' (macOS) or see https://k6.io/docs/get-started/installation/" >&2
  exit 1
fi

mkdir -p results
TS="$(date +%Y%m%d-%H%M%S)"

OUTPUT="${OUTPUT:-html}"
[[ "$OUTPUT" == "both" ]] && OUTPUT="json,prometheus"   # back-compat alias
PROMETHEUS_RW_URL="${PROMETHEUS_RW_URL:-http://localhost:9090/api/v1/write}"

HTML_REPORT="results/report-${TS}.html"
JSON_SUMMARY="results/summary-${TS}.json"

OUTPUT_ARGS=()
want_html=false want_json=false want_prom=false

IFS=',' read -ra SINKS <<< "$OUTPUT"
for sink in "${SINKS[@]}"; do
  case "$sink" in
    html)
      want_html=true
      # Built-in web dashboard: export a self-contained HTML report at the end of the run.
      export K6_WEB_DASHBOARD=true
      export K6_WEB_DASHBOARD_EXPORT="$HTML_REPORT"
      ;;
    json)
      want_json=true
      OUTPUT_ARGS+=(--summary-export "$JSON_SUMMARY")
      ;;
    prometheus|prom)
      want_prom=true
      OUTPUT_ARGS+=(-o experimental-prometheus-rw)
      ;;
    *)
      echo "Unknown OUTPUT token '$sink' (use: html | json | prometheus, comma-separated)" >&2
      exit 1
      ;;
  esac
done

# Export which trend statistics k6 ships to Prometheus (gauges per stat), and where to push.
export K6_PROMETHEUS_RW_SERVER_URL="$PROMETHEUS_RW_URL"
export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),avg,min,max}"

k6 run \
  "${OUTPUT_ARGS[@]}" \
  ${VUS:+-e VUS=$VUS} \
  ${ITERATIONS:+-e ITERATIONS=$ITERATIONS} \
  ${BASE_URL:+-e BASE_URL=$BASE_URL} \
  stress-test.js

$want_html && echo "HTML report written to ${HTML_REPORT}"
$want_json && echo "Summary written to ${JSON_SUMMARY}"
$want_prom && echo "Metrics pushed to Prometheus at ${PROMETHEUS_RW_URL} — open Grafana: http://localhost:3000 (dashboard: 'k6 — creed-simple-metrics stress test')"
