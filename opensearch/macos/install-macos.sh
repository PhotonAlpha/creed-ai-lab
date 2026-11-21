#!/usr/bin/env bash
# macOS 本地验证环境：brew 安装 OpenSearch / Dashboards / Fluent Bit，
# OpenSearch 与 Dashboards 用 brew services 管理，Fluent Bit 用 LaunchAgent。
# 与服务器版的差异：全部只监听 127.0.0.1、不锁内存、默认堆 2g、数据在 ~/logstack/data。可重复执行。
#
#   ./install.sh            （在 macOS 上会转到这里）
#   MAC_HEAP_GB=4 ./install.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEAP_GB="${MAC_HEAP_GB:-2}"
# shellcheck source=../lib/common.sh
source "$HERE/lib/common.sh"

command -v brew >/dev/null 2>&1 || { fail "需要 Homebrew: https://brew.sh"; exit 1; }
BREW="$(brew --prefix)"
NODE_NAME="$(hostname -s)"
FLB_BIN="$BREW/bin/fluent-bit"
OS_CONF="$BREW/etc/opensearch"
OSD_CONF="$BREW/etc/opensearch-dashboards"
AGENT="$HOME/Library/LaunchAgents/com.logstack.fluent-bit.plist"

for f in opensearch opensearch-dashboards fluent-bit; do
  brew list --formula "$f" >/dev/null 2>&1 || brew install "$f"
done

# brew 只提供当前最新版，不能锁定到 OPENSEARCH_VERSION；至少保证两个组件彼此一致
osv=$(brew list --versions opensearch | awk '{print $2}')
osdv=$(brew list --versions opensearch-dashboards | awk '{print $2}')
[[ "$osv" == "$osdv" ]] || { fail "brew 里 OpenSearch $osv 与 Dashboards $osdv 版本不一致，Dashboards 会拒绝连接"; exit 1; }
if [[ "$osv" == "$OPENSEARCH_VERSION" ]]; then
  ok "OpenSearch / Dashboards $osv"
else
  warn "brew 版本 $osv ≠ 服务器版本 ${OPENSEARCH_VERSION}：本地只用来验证配置和查询，版本差异以服务器为准"
fi

mkdir -p "$INSTALL_ROOT/fluent-bit" "$DATA_ROOT"/{data,logs,dashboards,fluent-bit/buffer} "$HOME/Library/LaunchAgents"

# brew 自带配置只备份一次
for f in "$OS_CONF/opensearch.yml" "$OSD_CONF/opensearch_dashboards.yml"; do
  if [[ -f "$f" && ! -f "$f.brew-orig" ]]; then cp "$f" "$f.brew-orig"; fi
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# OpenSearch：只听本机；macOS 不支持 mlockall；brew 版不含 security 插件时删掉该设置（未知设置会导致起不来）
render "$HERE/config/opensearch.yml" "$tmp/opensearch.yml"
sec_filter='/^plugins.security.disabled/!p'
[[ -d "$BREW/opt/opensearch/libexec/plugins/opensearch-security" ]] && sec_filter='p'
sed -n -e 's/^network\.host: .*/network.host: 127.0.0.1/' \
       -e 's/^bootstrap\.memory_lock: true/bootstrap.memory_lock: false/' \
       -e "$sec_filter" "$tmp/opensearch.yml" > "$OS_CONF/opensearch.yml"
mkdir -p "$OS_CONF/jvm.options.d"
render "$HERE/config/jvm.options" "$OS_CONF/jvm.options.d/logstack.options"
[[ -f "$OS_CONF/jvm.options" ]] && sed -i '' -E 's|^([^#].*-Xlog:gc.*)$|# disabled by logstack: \1|' "$OS_CONF/jvm.options"
ok "OpenSearch 配置 → ${OS_CONF}（堆 ${HEAP_GB}g）"

render "$HERE/config/opensearch_dashboards.yml" "$tmp/osd.yml"
sed -e 's/^server\.host: .*/server.host: "127.0.0.1"/' "$tmp/osd.yml" > "$OSD_CONF/opensearch_dashboards.yml"
osd_plugin_dir="$BREW/opt/opensearch-dashboards/libexec/plugins/securityDashboards"
if [[ -d "$osd_plugin_dir" ]]; then
  "$BREW/opt/opensearch-dashboards/bin/opensearch-dashboards-plugin" remove securityDashboards || rm -rf "$osd_plugin_dir"
fi
ok "Dashboards 配置 → $OSD_CONF"

render "$HERE/config/fluent-bit.yaml" "$tmp/fluent-bit.yaml"
sed -e 's/^\( *listen: *\)0\.0\.0\.0/\1127.0.0.1/' "$tmp/fluent-bit.yaml" > "$INSTALL_ROOT/fluent-bit/fluent-bit.yaml"
render "$HERE/config/parsers.yaml" "$INSTALL_ROOT/fluent-bit/parsers.yaml"
render "$HERE/macos/com.logstack.fluent-bit.plist" "$AGENT"
ok "Fluent Bit 配置 → $INSTALL_ROOT/fluent-bit，LaunchAgent → $AGENT"

brew services restart opensearch
wait_http "$OS_URL/_cluster/health?wait_for_status=yellow&timeout=5s" 180 \
  || { fail "OpenSearch 未就绪: tail -n 100 $DATA_ROOT/logs/logstack.log"; exit 1; }
bash "$HERE/bootstrap/01-index-template.sh"
bash "$HERE/bootstrap/02-ism-policy.sh"
bash "$HERE/bootstrap/03-ingest-pipeline.sh"

brew services restart opensearch-dashboards
bash "$HERE/bootstrap/04-index-pattern.sh"

launchctl bootout "gui/$(id -u)/com.logstack.fluent-bit" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$AGENT"
ok "Fluent Bit 已启动（LaunchAgent）"

info "打开 http://127.0.0.1:5601 ；体检: $HERE/healthcheck.sh"
