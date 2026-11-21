#!/usr/bin/env bash
# 公共函数。被各脚本 source，不单独执行。
# 变量只在 install.sh 顶部的 VARS 段定义，这里读那一段 —— 不在别处重复版本号和路径。

LOGSTACK_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# macOS 本地验证环境：数据放用户目录，不碰 /data（INSTALL_ROOT 与服务器一样默认是本目录）
if [[ "$(uname -s)" == "Darwin" ]]; then
  : "${DATA_ROOT:=$HOME/logstack/data}"
fi
eval "$(sed -n '/^# >>> VARS/,/^# <<< VARS/p' "$LOGSTACK_HOME/install.sh")"

OS_URL="${OS_URL:-http://127.0.0.1:9200}"
OSD_URL="${OSD_URL:-http://127.0.0.1:5601}"
FLB_URL="${FLB_URL:-http://127.0.0.1:2020}"

if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_OFF=""
fi
info() { printf '%s[INFO]%s %s\n' "$C_BLUE" "$C_OFF" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_OFF" "$*"; }

# api METHOD PATH [JSON_BODY] —— 调 OpenSearch。结果放在 API_CODE / API_BODY，2xx 返回 0。
api() {
  local method="$1" path="$2" body="${3:-}" out
  out="$(mktemp)"
  if [[ -n "$body" ]]; then
    API_CODE=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' \
      --data-binary "$body" "$OS_URL$path" 2>/dev/null || true)
  else
    API_CODE=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" "$OS_URL$path" 2>/dev/null || true)
  fi
  API_BODY="$(cat "$out")"
  rm -f "$out"
  [[ "$API_CODE" == 2* ]]
}

# osd METHOD PATH [JSON_BODY] —— 调 Dashboards API（需要 osd-xsrf 头）
osd() {
  local method="$1" path="$2" body="${3:-}" out
  out="$(mktemp)"
  API_CODE=$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -H 'osd-xsrf: true' \
    -H 'Content-Type: application/json' ${body:+--data-binary "$body"} "$OSD_URL$path" 2>/dev/null || true)
  API_BODY="$(cat "$out")"
  rm -f "$out"
  [[ "$API_CODE" == 2* ]]
}

# wait_http URL TIMEOUT_SECONDS —— 等到 URL 返回 2xx
wait_http() {
  local url="$1" timeout="$2" waited=0
  until [[ "$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)" == 2* ]]; do
    (( waited >= timeout )) && return 1
    sleep 3
    waited=$(( waited + 3 ))
  done
}

# render SRC DST —— 把 @VAR@ 占位符换成实际值后写到 DST（整文件替换，可重复执行）
render() {
  local src="$1" dst="$2"
  sed -e "s|@INSTALL_ROOT@|${INSTALL_ROOT}|g" \
      -e "s|@DATA_ROOT@|${DATA_ROOT}|g" \
      -e "s|@NODE_NAME@|${NODE_NAME:-logstack-node}|g" \
      -e "s|@HEAP_GB@|${HEAP_GB:-1}|g" \
      -e "s|@TIMEZONE@|${TIMEZONE}|g" \
      -e "s|@HOST_IP@|${HOST_IP}|g" \
      -e "s|@SVC_USER@|${SVC_USER}|g" \
      -e "s|@FLB_BIN@|${FLB_BIN:-/opt/fluent-bit/bin/fluent-bit}|g" \
      -e "s|@FLB_BUFFER_LIMIT@|${FLB_BUFFER_LIMIT}|g" \
      -e "s|@HOME@|${HOME}|g" \
      "$src" > "$dst.tmp"
  mv "$dst.tmp" "$dst"
}
