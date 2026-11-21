#!/usr/bin/env bash
# 启动 OpenSearch（MANUAL 1.3 的 nohup 命令）并等到 9200 能访问。
#   bin/start-opensearch.sh             启动并等待就绪（默认最多 120s，WAIT_TIMEOUT=秒 可改）
#   bin/start-opensearch.sh --no-wait   启动后立刻返回
# pid：$RUN_DIR/opensearch.pid   控制台日志：$RUN_DIR/logs/opensearch-console.log
# 已在运行则什么都不做。macOS 上要先在 bin/env.local.sh 里设 OPENSEARCH_JAVA_HOME（包里的 JDK 是 Linux 的）。

set -euo pipefail
. "$(dirname "$0")/_lib.sh"
parse_flags "$@"

NAME=OpenSearch
PIDFILE="$RUN_DIR/opensearch.pid"
LOG="$RUN_DIR/logs/opensearch-console.log"

[[ -n "$OS_HOME" && -x "$OS_HOME/bin/opensearch" ]] || { fail "找不到 OpenSearch：OS_HOME=${OS_HOME:-（空）}，在 bin/env.local.sh 里设 OS_HOME"; exit 1; }
need_not_root "$NAME"
if [[ "$(uname -s)" == Darwin && -z "${OPENSEARCH_JAVA_HOME:-}" ]]; then
  fail "macOS 上必须设 OPENSEARCH_JAVA_HOME（本机 JDK 21+），写进 bin/env.local.sh，见 MANUAL 1.3"
  exit 1
fi
export OPENSEARCH_JAVA_HOME="${OPENSEARCH_JAVA_HOME:-}"
[[ -z "$OPENSEARCH_JAVA_HOME" ]] && unset OPENSEARCH_JAVA_HOME

check_not_running "$NAME" "$PIDFILE" "org.opensearch.bootstrap.OpenSearch" 9200

# 必须在 OS_HOME 下启动：jvm.options 里有相对路径 agent/opensearch-agent.jar
(( ALREADY_RUNNING )) || start_bg "$OS_HOME" "$LOG" "$PIDFILE" ./bin/opensearch
wait_ready "http://127.0.0.1:9200" 120 "$PIDFILE" "$LOG" "$NAME" || exit 1
(( NO_WAIT )) || curl -s 'http://127.0.0.1:9200/_cluster/health?filter_path=cluster_name,status,number_of_nodes'; echo
