#!/usr/bin/env bash
# 通过 Dashboards API 创建索引模式 app-logs-*（时间字段 @timestamp）并设为默认，再设上下文视图的排序字段。overwrite=true，可重复执行。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

wait_http "$OSD_URL/api/status" 300 || { fail "Dashboards 未就绪: $OSD_URL/api/status"; exit 1; }

# 字段列表要一起写进去：overwrite 会把已保存的字段列表清空，而 Dashboards 3.x 不会自己补回来 ——
# 没有字段列表时 fieldFormatMap 不生效，show_source 只显示成一长串 URL，不是 Show Source 链接。
# 字段列表取自 _fields_for_wildcard（即 Management 里 Reload field list 用的接口），转成 JSON 字符串塞进 fields。
FIELDS='[]'
if osd GET "/api/index_patterns/_fields_for_wildcard?pattern=app-logs-*&meta_fields=_source&meta_fields=_id&meta_fields=_type&meta_fields=_index&meta_fields=_score"; then
  FIELDS="${API_BODY#\{\"fields\":}"; FIELDS="${FIELDS%\}}"
else
  warn "还没有 app-logs-* 索引，字段列表留空：数据进来以后再执行一次本脚本，Show Source 链接才会出现"
fi
FIELDS_ESCAPED="$(printf '%s' "$FIELDS" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"

osd POST "/api/saved_objects/index-pattern/app-logs?overwrite=true" '{
  "attributes": { "title": "app-logs-*", "timeFieldName": "@timestamp",
    "fields": "'"$FIELDS_ESCAPED"'",
    "fieldFormatMap": "{\"show_source\":{\"id\":\"url\",\"params\":{\"labelTemplate\":\"Show Source\"}}}" }
}' || { fail "索引模式创建失败 ($API_CODE): $API_BODY"; exit 1; }
ok "索引模式 app-logs-*（id app-logs，时间字段 @timestamp；show_source 显示成 Show Source 链接）"

osd POST "/api/opensearch-dashboards/settings" '{ "changes": { "defaultIndex": "app-logs" } }' \
  || { fail "设置默认索引模式失败 ($API_CODE): $API_BODY"; exit 1; }
ok "默认索引模式 = app-logs-*"

# 上下文视图（View surrounding documents，对应 Splunk 的 Show Source）：
#   排序 —— 先 @timestamp，同一毫秒再按 offset（forwarder 的 offset_key，日志在文件里的位置）；默认的 _doc 是写入顺序，和文件顺序不一定一致
#   条数 —— 打开时前后各 100 条，Load newer / older 每次再加 100（默认都是 5）
# defaultColumns ["log","show_source"] = Discover 默认显示原文 + Show Source 链接两列（点链接直接看上下文）；
#   Show Source 打开的上下文视图由链接自己指定只显示 log 一列，看起来像日志文件。
# truncate:maxHeight 0 = 长堆栈不折叠。
osd POST "/api/opensearch-dashboards/settings" '{ "changes": {
  "context:tieBreakerFields": ["offset"], "context:defaultSize": 100, "context:step": 100,
  "defaultColumns": ["log", "show_source"], "truncate:maxHeight": 0 } }' \
  || { fail "设置上下文视图失败 ($API_CODE): $API_BODY"; exit 1; }
ok "上下文视图：按 @timestamp, offset 排序，前后各 100 条；Discover 默认显示 log + show_source 列，长日志不折叠"
