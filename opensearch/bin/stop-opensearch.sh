#!/usr/bin/env bash
# 停止 OpenSearch：SIGTERM，最多等 90s 正常关闭（刷盘）。
#   bin/stop-opensearch.sh           正常关闭；超时就报错退出，进程保留
#   bin/stop-opensearch.sh --force   超时后 kill -9
# 先停 Dashboards 和 Fluent Bit 汇聚端（汇聚端停掉前写不进来的数据会先落在它的磁盘缓冲里，不丢）。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

stop_by_pidfile OpenSearch "$RUN_DIR/opensearch.pid" "org.opensearch.bootstrap.OpenSearch" 90
