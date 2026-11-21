#!/usr/bin/env bash
# 启动 Fluent Bit 汇聚端（$FB -c aggregator.yaml，MANUAL 4.1 / 4.2）：先 --dry-run 检查配置，再 nohup 启动，
# 等到本机指标端口的 /api/v1/health 返回 ok。
#   bin/start-aggregator.sh             启动并等待就绪（默认最多 30s；WAIT_TIMEOUT=秒 可改）
#   bin/start-aggregator.sh --no-wait   启动后立刻返回
# 在 $FB_DIR 下启动（相对路径 parsers.yaml / state/ 都按它解析）；pid、日志：$FB_DIR/aggregator.pid、aggregator.log
# 先启动 OpenSearch（没起来也能启动：写不进去的先落盘缓冲）。已在运行则什么都不做。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

NAME="Fluent Bit 汇聚端"
PIDFILE="$FB_DIR/aggregator.pid"
LOG="$FB_DIR/aggregator.log"
: "${AGG_HTTP_PORT:=2020}"     # aggregator.yaml 的 service.http_port

need_fb
[[ -f "$FB_DIR/aggregator.yaml" ]] || { fail "没有 $FB_DIR/aggregator.yaml（FB_DIR 设对了吗）"; exit 1; }
port_open 9200 || warn "OpenSearch（9200）现在连不上：汇聚端照常启动，数据先落在 state/aggregator/buffer，OpenSearch 起来后补发"

check_not_running "$NAME" "$PIDFILE" "fluent-bit -c aggregator.yaml" 24224

if (( ! ALREADY_RUNNING )); then
  (cd "$FB_DIR" && "$FB" -c aggregator.yaml --dry-run >/dev/null 2>&1) \
    || { fail "aggregator.yaml 检查不通过："; (cd "$FB_DIR" && "$FB" -c aggregator.yaml --dry-run) >&2 || true; exit 1; }
  start_bg "$FB_DIR" "$LOG" "$PIDFILE" "$FB" -c aggregator.yaml
fi
wait_ready "http://127.0.0.1:$AGG_HTTP_PORT/api/v1/health" 30 "$PIDFILE" "$LOG" "$NAME" || exit 1
(( NO_WAIT )) || { port_open 24224 && ok "24224 在监听，forwarder 可以连了"; } || warn "24224 还没在监听，看 $LOG"
