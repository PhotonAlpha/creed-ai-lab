#!/usr/bin/env bash
# 一键体检。任何一项 FAIL 时退出码为 1（可接监控 / cron）。只读，不改任何东西。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

FAILS=0
WARNS=0
bad()  { fail "$*"; FAILS=$((FAILS + 1)); }
soft() { warn "$*"; WARNS=$((WARNS + 1)); }
section() { printf '\n%s== %s ==%s\n' "$C_BLUE" "$*" "$C_OFF"; }

listening() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    [[ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]]
  fi
}

section "1. 端口监听"
for p in 9200 5601 24224; do
  listening "$p" && ok "$p 在监听" || bad "$p 没有监听"
done
listening 2020 && ok "2020 在监听" || soft "2020 没有监听（Fluent Bit 指标）"

section "2. 集群健康"
if api GET "/_cluster/health"; then
  status=$(grep -o '"status":"[a-z]*"' <<<"$API_BODY" | cut -d'"' -f4 || true)
  case "$status" in
    green)  ok "cluster status = green" ;;
    yellow) bad "cluster status = yellow：有副本分不出去。执行:"
            echo "    curl -s '$OS_URL/_cat/shards?h=index,shard,prirep,state,unassigned.reason' | grep UNASSIGNED"
            echo "    curl -s -XPUT '$OS_URL/_all/_settings?expand_wildcards=all' -H 'Content-Type: application/json' -d '{\"index.number_of_replicas\":0}'" ;;
    *)      bad "cluster status = ${status:-unknown}" ;;
  esac
else
  bad "OpenSearch 不可达 ($OS_URL)"
fi

if api GET "/"; then
  osv=$(grep -o '"number" *: *"[^"]*"' <<<"$API_BODY" | head -n1 | sed -E 's/.*"([^"]*)"$/\1/' || true)
  osdv=""
  if osd GET "/api/status"; then
    osdv=$(grep -o '"number" *: *"[^"]*"' <<<"$API_BODY" | head -n1 | sed -E 's/.*"([^"]*)"$/\1/' || true)
  fi
  if [[ "$osv" == "$OPENSEARCH_VERSION" && "$osdv" == "$OPENSEARCH_VERSION" ]]; then
    ok "版本一致: OpenSearch $osv / Dashboards $osdv"
  else
    bad "版本不一致: 期望 ${OPENSEARCH_VERSION}，OpenSearch=${osv:-?} Dashboards=${osdv:-?}"
  fi
fi

section "3. 模板 / ISM / pipeline / 索引模式"
api GET "/_index_template/app-logs" && ok "索引模板 app-logs" || bad "缺少索引模板 app-logs → bootstrap/01-index-template.sh"
api GET "/_plugins/_ism/policies/app-logs-policy" && ok "ISM 策略 app-logs-policy" || bad "缺少 ISM 策略 → bootstrap/02-ism-policy.sh"
api GET "/_ingest/pipeline/app-logs-pipeline" && ok "ingest pipeline app-logs-pipeline" || bad "缺少 pipeline → bootstrap/03-ingest-pipeline.sh"
osd GET "/api/saved_objects/index-pattern/app-logs" && ok "Dashboards 索引模式 app-logs-*" || bad "缺少索引模式 → bootstrap/04-index-pattern.sh"

section "4. 最近 5 分钟写入"
if api POST "/app-logs-*/_count" '{"query":{"range":{"ingested_at":{"gte":"now-5m"}}}}'; then
  n=$(grep -o '"count":[0-9]*' <<<"$API_BODY" | cut -d: -f2 || true)
  (( ${n:-0} > 0 )) && ok "最近 5 分钟新写入 $n 条" || bad "最近 5 分钟没有新文档（排查顺序见 README「查不到数据」）"
else
  bad "统计失败 ($API_CODE)：可能还没有任何 app-logs-* 索引"
fi
if api POST "/app-logs-*/_count" '{"query":{"bool":{"filter":[{"exists":{"field":"parse_error"}},{"range":{"ingested_at":{"gte":"now-1h"}}}]}}}'; then
  e=$(grep -o '"count":[0-9]*' <<<"$API_BODY" | cut -d: -f2 || true)
  (( ${e:-0} == 0 )) && ok "最近 1 小时没有解析失败" || soft "最近 1 小时 $e 条解析失败，Discover 里查 parse_error:*"
fi

section "5. 磁盘"
if [[ -d "$DATA_ROOT" ]]; then
  used=$(df -P "$DATA_ROOT" | awk 'NR==2 {gsub("%","",$5); print $5}')
  if (( used > 80 )); then
    bad "数据盘 $DATA_ROOT 已用 ${used}%（85% 起不再分配新分片，95% 索引只读）"
  else
    ok "数据盘 $DATA_ROOT 已用 ${used}%"
  fi
else
  bad "数据目录 $DATA_ROOT 不存在"
fi

section "6. Fluent Bit 指标"
if metrics=$(curl -sf "$FLB_URL/api/v1/metrics/prometheus" 2>/dev/null); then
  sum() { awk -v m="$1" '$1 ~ "^"m"[{ ]" {s += $NF} END {printf "%d", s}' <<<"$metrics"; }
  retries=$(sum fluentbit_output_retries_total)
  retries_failed=$(sum fluentbit_output_retries_failed_total)
  errors=$(sum fluentbit_output_errors_total)
  dropped=$(sum fluentbit_output_dropped_records_total)
  info "output retries=$retries retries_failed=$retries_failed errors=$errors dropped=${dropped}（进程启动以来累计）"
  (( retries_failed > 0 || dropped > 0 )) && bad "有放弃重试 / 丢弃的记录：journalctl -u fluent-bit | grep -i error" \
    || { (( errors > 0 || retries > 0 )) && soft "有过错误 / 重试（OpenSearch 短暂不可用时正常），持续增长才需要关注" \
         || ok "无重试、无错误"; }
else
  bad "Fluent Bit 指标不可达 ($FLB_URL)"
fi

printf '\n'
if (( FAILS > 0 )); then
  fail "共 $FAILS 项失败，$WARNS 项警告"
  exit 1
fi
ok "全部通过（$WARNS 项警告）"
