#!/usr/bin/env bash
# 停止 Fluent Bit 汇聚端：SIGTERM 后它会先把内存里的数据写出去（Grace 5s），最多等 30s。
#   bin/stop-aggregator.sh           正常关闭；超时就报错退出，进程保留
#   bin/stop-aggregator.sh --force   超时后 kill -9（没写出去的数据还在 state/aggregator/buffer，下次启动补发）
# 汇聚端停着时各台 forwarder 发不过来，数据留在 forwarder 自己的磁盘缓冲里，汇聚端起来后补发。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

stop_by_pidfile "Fluent Bit 汇聚端" "$FB_DIR/aggregator.pid" "fluent-bit -c aggregator.yaml" 30
