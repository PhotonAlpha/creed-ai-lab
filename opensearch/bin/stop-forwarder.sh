#!/usr/bin/env bash
# 停止 Fluent Bit forwarder：SIGTERM 后它会先把读到的日志发出去（Grace 5s），最多等 30s。读到哪了记在书签 tail.db 里，
# 下次启动接着读，不重复不漏。
#   bin/stop-forwarder.sh           正常关闭；超时就报错退出，进程保留
#   bin/stop-forwarder.sh --force   超时后 kill -9（没发出去的在 state/forwarder/storage，下次启动补发）
# Linux 业务服务器上：FB_DIR=/opt/logstack-forwarder bin/stop-forwarder.sh

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

stop_by_pidfile "Fluent Bit forwarder" "$FB_DIR/forwarder.pid" "fluent-bit -c forwarder.yaml" 30
