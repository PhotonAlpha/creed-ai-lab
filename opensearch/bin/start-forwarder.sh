#!/usr/bin/env bash
# 启动 Fluent Bit forwarder（HOSTNAME=... $FB -c forwarder.yaml，MANUAL 4.3 / 4.4）：先 --dry-run 检查配置，
# 再 nohup 启动，等到本机指标端口的 /api/v1/uptime 能访问。
#   bin/start-forwarder.sh             启动并等待就绪（默认最多 30s；WAIT_TIMEOUT=秒 可改）
#   bin/start-forwarder.sh --no-wait   启动后立刻返回
# host 字段 = FORWARDER_HOSTNAME，默认 hostname -s（Mac 的主机名不好认时在 bin/env.local.sh 里写一个，如 ethan-mac）。
# 在 $FB_DIR 下启动；pid、日志：$FB_DIR/forwarder.pid、forwarder.log。同一个 FB_DIR 只能跑一个 forwarder（书签 tail.db 独占）。
# Linux 业务服务器上：FB_DIR=/opt/logstack-forwarder bin/start-forwarder.sh（见 MANUAL 4.4）。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

NAME="Fluent Bit forwarder"
PIDFILE="$FB_DIR/forwarder.pid"
LOG="$FB_DIR/forwarder.log"
: "${FWD_HTTP_PORT:=2021}"     # forwarder.yaml 的 service.http_port
: "${FORWARDER_HOSTNAME:=$(hostname -s)}"

need_fb
[[ -f "$FB_DIR/forwarder.yaml" ]] || { fail "没有 $FB_DIR/forwarder.yaml（FB_DIR 设对了吗）"; exit 1; }
# 不按端口判断：forwarder 不监听业务端口，2021 被占只说明有别的 Fluent Bit
check_not_running "$NAME" "$PIDFILE" "fluent-bit -c forwarder.yaml"

if (( ! ALREADY_RUNNING )); then
  (cd "$FB_DIR" && HOSTNAME="$FORWARDER_HOSTNAME" "$FB" -c forwarder.yaml --dry-run >/dev/null 2>&1) \
    || { fail "forwarder.yaml 检查不通过："; (cd "$FB_DIR" && HOSTNAME="$FORWARDER_HOSTNAME" "$FB" -c forwarder.yaml --dry-run) >&2 || true; exit 1; }
  info "host = $FORWARDER_HOSTNAME"
  # env 再 exec fluent-bit：pid 就是 fluent-bit 自己的（bash 的 HOSTNAME 不导出，必须显式给）
  start_bg "$FB_DIR" "$LOG" "$PIDFILE" env HOSTNAME="$FORWARDER_HOSTNAME" "$FB" -c forwarder.yaml
fi
wait_ready "http://127.0.0.1:$FWD_HTTP_PORT/api/v1/uptime" 30 "$PIDFILE" "$LOG" "$NAME" || exit 1
