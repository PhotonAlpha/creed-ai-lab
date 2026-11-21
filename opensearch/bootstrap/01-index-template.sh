#!/usr/bin/env bash
# 索引模板 app-logs（匹配 app-logs-*），外加一个让所有新索引副本为 0 的兜底模板。PUT 覆盖写，可重复执行。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

# 兜底：单节点上任何带副本的索引都会让集群 yellow。priority 0 —— 只要有更具体的模板就让给它。
api PUT /_index_template/logstack-default-replicas '{
  "index_patterns": ["*"],
  "priority": 0,
  "template": { "settings": { "number_of_replicas": 0 } },
  "_meta": { "managed_by": "logstack" }
}' || { fail "兜底模板创建失败 ($API_CODE): $API_BODY"; exit 1; }
ok "索引模板 logstack-default-replicas (* → replicas 0)"

api PUT /_index_template/app-logs '{
  "index_patterns": ["app-logs-*"],
  "priority": 200,
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "refresh_interval": "5s",
      "index.default_pipeline": "app-logs-pipeline",
      "analysis": {
        "normalizer": {
          "lowercase_normalizer": { "type": "custom", "filter": ["lowercase"] }
        }
      }
    },
    "mappings": {
      "dynamic_templates": [
        { "strings_as_keyword": {
            "match_mapping_type": "string",
            "mapping": { "type": "keyword", "ignore_above": 1024 } } }
      ],
      "properties": {
        "@timestamp":  { "type": "date" },
        "ingested_at": { "type": "date" },
        "trace_id":    { "type": "keyword", "normalizer": "lowercase_normalizer" },
        "level":       { "type": "keyword" },
        "index":       { "type": "keyword" },
        "host":        { "type": "keyword" },
        "source":      { "type": "keyword" },
        "offset":      { "type": "long" },
        "show_source": { "type": "keyword", "index": false, "doc_values": false },
        "app":         { "type": "keyword" },
        "log":         { "type": "text" },
        "msg":         { "type": "text" },
        "thread":      { "type": "keyword" },
        "logger":      { "type": "keyword" },
        "parse_error": { "type": "keyword" }
      }
    }
  },
  "_meta": { "managed_by": "logstack" }
}' || { fail "索引模板 app-logs 创建失败 ($API_CODE): $API_BODY"; exit 1; }
ok "索引模板 app-logs (app-logs-*, shards 1, replicas 0, default_pipeline app-logs-pipeline)"
info "模板只对之后新建的索引生效；已有的日索引不变，次日的新索引开始套用"
