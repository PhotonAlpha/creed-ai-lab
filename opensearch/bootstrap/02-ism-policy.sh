#!/usr/bin/env bash
# ISM 生命周期策略 app-logs-policy：hot 保留 RETENTION_DAYS 天后删除，自动套用到新建的 app-logs-*。
#
# 为什么没有 rollover 动作：Fluent Bit 用 Logstash_Format 按天直接写 app-logs-YYYY.MM.DD，没有写别名。
# ISM 的 rollover 必须有 rollover_alias，给按天索引加 rollover 会让每个索引的 ISM 卡在 failed，
# 后面的 delete 也就永远不执行。按天索引本身就是"每天滚动一次"；单日超过 10GB 的切分方案见 README。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

POLICY_ID="app-logs-policy"
POLICY='{
  "policy": {
    "description": "app-logs: hot '"$RETENTION_DAYS"'d, then delete",
    "default_state": "hot",
    "states": [
      { "name": "hot",
        "actions": [],
        "transitions": [ { "state_name": "delete", "conditions": { "min_index_age": "'"$RETENTION_DAYS"'d" } } ] },
      { "name": "delete",
        "actions": [ { "delete": {} } ],
        "transitions": [] }
    ],
    "ism_template": [ { "index_patterns": ["app-logs-*"], "priority": 100 } ]
  }
}'

# 已存在时更新必须带 if_seq_no / if_primary_term，否则 409
if api GET "/_plugins/_ism/policies/$POLICY_ID"; then
  seq=$(grep -o '"_seq_no":[0-9]*' <<<"$API_BODY" | head -n1 | cut -d: -f2)
  term=$(grep -o '"_primary_term":[0-9]*' <<<"$API_BODY" | head -n1 | cut -d: -f2)
  api PUT "/_plugins/_ism/policies/$POLICY_ID?if_seq_no=$seq&if_primary_term=$term" "$POLICY" \
    || { fail "ISM 策略更新失败 ($API_CODE): $API_BODY"; exit 1; }
  ok "ISM 策略 $POLICY_ID 已更新（hot ${RETENTION_DAYS}d → delete）"
else
  api PUT "/_plugins/_ism/policies/$POLICY_ID" "$POLICY" \
    || { fail "ISM 策略创建失败 ($API_CODE): $API_BODY"; exit 1; }
  ok "ISM 策略 $POLICY_ID 已创建（hot ${RETENTION_DAYS}d → delete）"
fi

# ism_template 只管之后新建的索引；已有但还没挂策略的 app-logs-* 这里补挂（已挂的会被跳过，不算错误）
if api GET "/_cat/indices/app-logs-*?h=index"; then
  if [[ -n "$API_BODY" ]]; then
    api POST "/_plugins/_ism/add/app-logs-*" '{"policy_id":"'"$POLICY_ID"'"}' || true
    info "已有索引补挂策略: $(grep -o '"updated_indices":[0-9]*' <<<"$API_BODY" || echo 'updated_indices:0')"
  fi
fi
