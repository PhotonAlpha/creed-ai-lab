#!/usr/bin/env bash
# 启动 OpenSearch Dashboards（MANUAL 2.3 的 nohup 命令）并等到 5601 的 /api/status 返回 200。
#   bin/start-dashboards.sh             启动并等待就绪（默认最多 180s，第一次启动要 1～2 分钟；WAIT_TIMEOUT=秒 可改）
#   bin/start-dashboards.sh --no-wait   启动后立刻返回
# pid：$RUN_DIR/dashboards.pid   日志：$RUN_DIR/logs/dashboards.log
# 先启动 OpenSearch。已在运行则什么都不做。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

NAME=Dashboards
PIDFILE="$RUN_DIR/dashboards.pid"
LOG="$RUN_DIR/logs/dashboards.log"
# node 进程的命令行：<OSD_HOME>/node/bin/node（或系统 node）... <OSD_HOME>/src/cli/dist
PATTERN="${OSD_HOME}/src/cli/dist"

[[ -n "$OSD_HOME" && -x "$OSD_HOME/bin/opensearch-dashboards" ]] || { fail "找不到 Dashboards：OSD_HOME=${OSD_HOME:-（空）}，在 bin/env.local.sh 里设 OSD_HOME"; exit 1; }
need_not_root "$NAME"
if [[ "$(uname -s)" == Darwin && -d "$OSD_HOME/node" ]]; then
  fail "macOS 上不能用包里自带的 Linux node：cd $OSD_HOME && mv node node.linux（之后用 Homebrew 的 node），见 MANUAL 4.2 的表"
  exit 1
fi
port_open 9200 || warn "OpenSearch（9200）现在连不上：Dashboards 会一直重试，先 bin/start-opensearch.sh"

check_not_running "$NAME" "$PIDFILE" "$PATTERN" 5601

(( ALREADY_RUNNING )) || start_bg "$OSD_HOME" "$LOG" "$PIDFILE" ./bin/opensearch-dashboards
wait_ready "http://127.0.0.1:5601/api/status" 180 "$PIDFILE" "$LOG" "$NAME" || exit 1
