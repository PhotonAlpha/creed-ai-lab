#!/usr/bin/env bash
# 停止 OpenSearch Dashboards：SIGTERM，最多等 30s。
#   bin/stop-dashboards.sh           正常关闭；超时就报错退出，进程保留
#   bin/stop-dashboards.sh --force   超时后 kill -9

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

stop_by_pidfile Dashboards "$RUN_DIR/dashboards.pid" "${OSD_HOME}/src/cli/dist" 30
