#!/usr/bin/env bash
# Ingest pipeline app-logs-pipeline：从 log 原始行解析出 trace_id / level / msg，日志时间 → @timestamp。
#
#   ./03-ingest-pipeline.sh             创建 / 更新 pipeline（PUT 覆盖写，可重复执行）
#   ./03-ingest-pipeline.sh --simulate  用三条样例行调 _simulate，看解析结果（不写入任何数据）
#
# 识别的日志格式（creed-ai-lab/logs 下的三种 + 两种通用格式；TODO: 有别的格式就往 patterns 里加一条）：
#   （logback 的 logger 列占满 40 字符时，后面紧跟 [行号] 没有空格）
#   A logback 自定义（veracode）  20-11-2026 09:24:02.299  INFO [creed-resource-veracode,6aff...,c19c...] [http-nio-18097-exec-4] creed.tracker.http.outbound   [82] : msg
#   B logback 自定义（comparison）20-11-2026 00:03:08.324  INFO [http-nio-8600-exec-2][,] c.creed.parent.service.DictLoadService  [41] : msg
#   C Spring Boot 默认           2026-10-17T00:50:05.534+08:00  INFO 84164 --- [creed-author-server] [main] [traceId-spanId] c.c.auth.App : msg
#   D 通用                       2026-11-20 10:15:30.123 INFO [main] [trace_id=4bf9...] com.example.OrderService - msg
#   E 通用                       2026-11-20 10:15:30,123 ERROR something failed traceId=4bf92f35-...
# 都匹配不上的行照样入库，带 parse_error 字段（查询 parse_error:* 找出来）。
# index / host / source 三个字段由 Fluent Bit 加，这里不碰。
# show_source：本条的上下文视图链接（≈ Splunk Show Source），带 source + host 两个生效的过滤条件；
#   predecessorCount:10 —— 上下文视图是倒序（新的在上），上方放 100 条新的会把点的那条挤出第一屏；上方 10 条、下方 100 条（更早的）。
#   放在 grok 前面 —— grok 失败会进 on_failure，后面的处理器不再执行。链接里的 app-logs 是 04 建的索引模式 id。
#   链接里的单引号（rison 字符串）写成 \u0027：整个 JSON 在 bash 单引号里，直接写单引号会被 bash 吃掉。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

PIPELINE_ID="app-logs-pipeline"

PIPELINE='{
  "description": "app-logs: grok trace_id / level / msg, log time -> @timestamp ('"$TIMEZONE"')",
  "processors": [
    { "set": { "field": "ingested_at", "value": "{{_ingest.timestamp}}" } },
    { "set": { "if": "ctx.source != null && ctx.host != null", "field": "show_source",
        "value": "/app/discover#/context/app-logs/{{_id}}?_g=(filters:!())&_a=(columns:!(log),predecessorCount:10,successorCount:100,filters:!((meta:(alias:!n,disabled:!f,index:app-logs,key:source,negate:!f,params:(query:\u0027{{source}}\u0027),type:phrase),query:(match_phrase:(source:\u0027{{source}}\u0027))),(meta:(alias:!n,disabled:!f,index:app-logs,key:host,negate:!f,params:(query:\u0027{{host}}\u0027),type:phrase),query:(match_phrase:(host:\u0027{{host}}\u0027)))))" } },
    { "grok": {
        "field": "log",
        "patterns": [
          "^%{DMY_TIME:log_time}\\s+%{LOGLEVEL:level}\\s+\\[%{DATA:app},(?:%{TRACE_ID:trace_id})?,[^\\]]*\\]\\s+\\[%{DATA:thread}\\]\\s+%{NOTSPACE:logger}\\s*\\[%{NUMBER}\\]\\s*:\\s?%{GREEDYDATA:msg}",
          "^%{DMY_TIME:log_time}\\s+%{LOGLEVEL:level}\\s+\\[%{DATA:thread}\\]\\[(?:%{TRACE_ID:trace_id})?,[^\\]]*\\]\\s+%{NOTSPACE:logger}\\s*\\[%{NUMBER}\\]\\s*:\\s?%{GREEDYDATA:msg}",
          "^%{TIMESTAMP_ISO8601:log_time}\\s+%{LOGLEVEL:level}\\s+%{NUMBER}\\s+---\\s+\\[%{DATA:app}\\]\\s+\\[%{DATA:thread}\\]\\s+\\[\\s*(?:%{TRACE_ID:trace_id}-[0-9a-fA-F]*)?\\s*\\]\\s+%{NOTSPACE:logger}\\s*:\\s?%{GREEDYDATA:msg}",
          "^%{TIMESTAMP_ISO8601:log_time}\\s+%{LOGLEVEL:level}\\s+\\[%{DATA:thread}\\]\\s+\\[(?:trace_id=)?(?:%{TRACE_ID:trace_id})?\\]\\s+%{NOTSPACE:logger}\\s+-\\s+%{GREEDYDATA:msg}",
          "^%{TIMESTAMP_ISO8601:log_time}\\s+%{LOGLEVEL:level}\\s+%{GREEDYDATA:msg}"
        ],
        "pattern_definitions": {
          "DMY_TIME": "\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2}[.,]\\d{3}",
          "TRACE_ID": "(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,32})"
        }
    } },
    { "grok": {
        "description": "格式不固定时，从整行任意位置找 trace_id=xxx / traceId: xxx",
        "if": "ctx.trace_id == null",
        "field": "log",
        "patterns": [ "(?i)trace[_-]?id[\"=:\\s]+%{TRACE_ID:trace_id}" ],
        "pattern_definitions": {
          "TRACE_ID": "(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,32})"
        },
        "ignore_failure": true
    } },
    { "date": {
        "if": "ctx.log_time != null",
        "field": "log_time",
        "target_field": "@timestamp",
        "formats": ["dd-MM-yyyy HH:mm:ss.SSS", "dd-MM-yyyy HH:mm:ss,SSS", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss,SSS", "yyyy-MM-dd HH:mm:ss", "ISO8601"],
        "timezone": "'"$TIMEZONE"'"
    } },
    { "uppercase": { "field": "level", "ignore_missing": true } },
    { "remove": { "field": "log_time", "ignore_missing": true } }
  ],
  "on_failure": [
    { "set": { "field": "parse_error", "value": "{{ _ingest.on_failure_processor_type }}: {{ _ingest.on_failure_message }}" } }
  ]
}'

if [[ "${1:-}" == "--simulate" ]]; then
  api POST "/_ingest/pipeline/$PIPELINE_ID/_simulate" '{
    "docs": [
      { "_source": { "log": "20-11-2026 09:24:02.299  INFO [creed-resource-veracode,6affa131fbb0b2f38e6e5933eb6f59be,c19c3a420890a012] [http-nio-18097-exec-4] creed.tracker.http.outbound             [82] : sid=b0e4 direction=outbound status=403", "index": "sg-sit", "host": "app-01", "source": "/opt/app/logs/creed-resource-veracode.log" } },
      { "_source": { "log": "20-11-2026 00:24:41.067  INFO [creed-resource-veracode,,] [RMI TCP Connection(4)-192.168.5.11] o.a.c.c.C.[Tomcat].[localhost].[/]      [168] : Initializing Spring DispatcherServlet", "index": "sg-sit", "host": "app-01", "source": "/opt/app/logs/creed-resource-veracode.log" } },
      { "_source": { "log": "20-11-2026 00:03:08.324  INFO [http-nio-8600-exec-2][,] c.creed.parent.service.DictLoadService  [41] : rootPath:./config walkPathType:COMMON", "index": "sg-sit", "host": "app-02", "source": "/opt/app/logs/comparison-console.log" } },
      { "_source": { "log": "2026-10-17T00:50:05.534+08:00  INFO 84164 --- [creed-author-server] [main] [                                                 ] c.c.auth.CreedAuthorServerApplication    : Starting CreedAuthorServerApplication", "index": "sg-sit", "host": "app-03", "source": "/opt/app/logs/creed-author-server.log" } },
      { "_source": { "log": "2026-11-20T10:15:30.123+08:00 ERROR 84164 --- [creed-author-server] [http-nio-9000-exec-1] [4bf92f3577b34da6a3ce929d0e0e4736-a3ce929d0e0e4736] c.c.auth.Foo : boom", "index": "sg-sit", "host": "app-03", "source": "/opt/app/logs/creed-author-server.log" } },
      { "_source": { "log": "2026-11-20 10:15:31,456 ERROR payment failed traceId=4bf92f35-77b3-4da6-a3ce-929d0e0e4736", "index": "sg-sit", "host": "app-04", "source": "/var/log/myapp/app.log" } },
      { "_source": { "log": "this line has no timestamp", "index": "sg-sit", "host": "app-05", "source": "/var/log/myapp/app.log" } }
    ]
  }' || { fail "_simulate 失败 ($API_CODE)，先执行一次本脚本创建 pipeline: $API_BODY"; exit 1; }
  printf '%s\n' "$API_BODY"
  exit 0
fi

api PUT "/_ingest/pipeline/$PIPELINE_ID" "$PIPELINE" \
  || { fail "pipeline 创建失败 ($API_CODE): $API_BODY"; exit 1; }
ok "ingest pipeline ${PIPELINE_ID}（时区 ${TIMEZONE}）"
info "调试解析: $0 --simulate"
