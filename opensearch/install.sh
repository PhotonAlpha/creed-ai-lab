#!/usr/bin/env bash
# 一键安装：建用户、建目录、系统参数、解压离线 tar 包、安装 Fluent Bit RPM、渲染配置、装 systemd 单元、
# 按顺序启动并初始化（模板 → ISM → pipeline → Dashboards 索引模式）。可重复执行。
#
#   sudo ./install.sh               # 全流程
#   sudo ./install.sh --no-start    # 只安装 + 渲染配置，不启动
#   sudo ./install.sh --config-only # 改了 config/ 下的文件后，只重新渲染配置（之后手动 restart）
#
# macOS 上执行会转去 macos/install-macos.sh。
set -euo pipefail

# 脚本所在目录。lib/common.sh 也定义同名变量后再读 VARS 段，所以 VARS 里可以引用它
LOGSTACK_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# >>> VARS —— 所有版本号与环境变量只在这里定义，其他脚本通过 lib/common.sh 读取这一段
OPENSEARCH_VERSION="${OPENSEARCH_VERSION:-3.10.0}"        # OpenSearch 与 Dashboards 必须同版本
FLUENTBIT_VERSION="${FLUENTBIT_VERSION:-5.2.0}"           # TODO: 与你下载的 Fluent Bit RPM 一致（不是 fluent-bit-browser-experimental，那是浏览器 WASM SDK）
INSTALL_ROOT="${INSTALL_ROOT:-${LOGSTACK_HOME}}"           # 默认 = 本目录（install.sh 所在目录），放哪装哪
DATA_ROOT="${DATA_ROOT:-/data/opensearch}"
PACKAGE_DIR="${PACKAGE_DIR:-${INSTALL_ROOT}/packages}"   # 离线包放这里（见 README「安装步骤」）
HOST_IP="${HOST_IP:-10.0.0.50}"
OFFICE_CIDR="${OFFICE_CIDR:-10.0.0.0/24}"                 # 允许访问 5601 的办公网段
FORWARDER_IPS="${FORWARDER_IPS:-}"                        # TODO: 业务服务器 IP，逗号分隔；为空时 24224 不放行
RETENTION_DAYS="${RETENTION_DAYS:-7}"
TIMEZONE="${TIMEZONE:-Asia/Singapore}"
HEAP_GB="${HEAP_GB:-}"                                    # 空 = 物理内存一半，上限 31
FLB_BUFFER_LIMIT="${FLB_BUFFER_LIMIT:-20G}"               # TODO: Fluent Bit 磁盘缓冲上限，按磁盘余量调整
SVC_USER="${SVC_USER:-logstack}"
# <<< VARS

HERE="$LOGSTACK_HOME"

if [[ "$(uname -s)" == "Darwin" ]]; then
  exec "$HERE/macos/install-macos.sh" "$@"
fi

# shellcheck source=lib/common.sh
source "$HERE/lib/common.sh"

MODE="full"
case "${1:-}" in
  --no-start) MODE="no-start" ;;
  --config-only) MODE="config-only" ;;
  "") ;;
  *) echo "用法: $0 [--no-start|--config-only]"; exit 2 ;;
esac

[[ $EUID -eq 0 ]] || { fail "请用 root 执行（sudo $0）"; exit 1; }
[[ "$HERE" == "$INSTALL_ROOT" ]] || warn "脚本所在目录 $HERE 与 INSTALL_ROOT=$INSTALL_ROOT 不一致，systemd 单元会指向 $INSTALL_ROOT"

OS_HOME="$INSTALL_ROOT/opensearch"
OSD_HOME="$INSTALL_ROOT/opensearch-dashboards"
FLB_BIN="/opt/fluent-bit/bin/fluent-bit"
NODE_NAME="$(hostname -s)"

# ---------- 系统识别 ----------
detect_os() {
  # shellcheck disable=SC1091
  . /etc/os-release
  local major="${VERSION_ID%%.*}"
  if [[ " ${ID} ${ID_LIKE:-} " == *" rhel "* || "$ID" =~ ^(rhel|rocky|almalinux|centos|ol)$ ]]; then
    info "系统: ${PRETTY_NAME}（RHEL 系 ${major}）"
    [[ "$major" == "8" || "$major" == "9" ]] || warn "只在 RHEL 8/9 上验证过，当前大版本 ${major}"
  else
    warn "非 RHEL 系（${PRETTY_NAME}），继续执行，但 Fluent Bit 安装步骤需要你手动处理"
  fi
  case "$(uname -m)" in
    x86_64) ARCH="x64" ;;
    aarch64) ARCH="arm64" ;;
    *) fail "不支持的架构 $(uname -m)"; exit 1 ;;
  esac
}

compute_heap() {
  if [[ -z "$HEAP_GB" ]]; then
    local mem_kb
    mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)
    HEAP_GB=$(( mem_kb / 1024 / 1024 / 2 ))
    (( HEAP_GB < 1 )) && HEAP_GB=1
  fi
  if (( HEAP_GB > 31 )); then
    warn "HEAP_GB=$HEAP_GB 超过 31，已降到 31（超过约 32GB 会失去压缩指针，反而更费内存）"
    HEAP_GB=31
  fi
  info "JVM 堆: ${HEAP_GB}g（Xms = Xmx）"
}

# ---------- 用户与目录 ----------
setup_user_dirs() {
  if ! id -u "$SVC_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$INSTALL_ROOT" --no-create-home --shell /sbin/nologin "$SVC_USER"
    ok "已创建系统用户 $SVC_USER"
  fi
  install -d -m 0755 "$INSTALL_ROOT" "$PACKAGE_DIR" "$INSTALL_ROOT/fluent-bit"
  # INSTALL_ROOT 默认是脚本所在目录：放在 /root 或别人的 home 下时，服务用户进不去，三个服务都会起不来
  if ! runuser -u "$SVC_USER" -- test -x "$INSTALL_ROOT"; then
    fail "用户 $SVC_USER 进不了 ${INSTALL_ROOT}：某一级上级目录没有 o+x（/root、/home/<用户> 默认就是这样）"
    fail "把整个目录移到 /opt 下（例如 /opt/logstack）再执行，或者 INSTALL_ROOT=/opt/logstack 指定别的位置"
    exit 1
  fi
  install -d -m 0750 -o "$SVC_USER" -g "$SVC_USER" \
    "$DATA_ROOT" "$DATA_ROOT/data" "$DATA_ROOT/logs" "$DATA_ROOT/dashboards" \
    "$DATA_ROOT/fluent-bit" "$DATA_ROOT/fluent-bit/buffer"
  ok "目录就绪: $DATA_ROOT/{data,logs,dashboards,fluent-bit/buffer}"
}

# ---------- 系统级前置 ----------
setup_kernel() {
  cat > /etc/sysctl.d/99-logstack.conf <<'EOF'
# logstack: OpenSearch 需要大量 mmap；swappiness=1 让内核尽量不换出 JVM 堆
vm.max_map_count = 262144
vm.swappiness = 1
EOF
  sysctl -q -p /etc/sysctl.d/99-logstack.conf
  ok "sysctl: vm.max_map_count=$(sysctl -n vm.max_map_count) vm.swappiness=$(sysctl -n vm.swappiness)"

  cat > /etc/security/limits.d/99-logstack.conf <<EOF
# logstack: 交互式登录的 $SVC_USER 生效；systemd 服务以单元里的 Limit* 为准（见 systemd/*.service）
$SVC_USER - nofile 65536
$SVC_USER - memlock unlimited
EOF
  ok "limits.d: nofile 65536 / memlock unlimited"

  if [[ -n "$(swapon --show --noheadings 2>/dev/null || true)" ]]; then
    warn "swap 处于开启状态；已把 swappiness 调到 1。要彻底关闭: swapoff -a 并注释 /etc/fstab 里的 swap 行"
  fi
}

check_disk() {
  local avail_pct
  avail_pct=$(df -P "$DATA_ROOT" | awk 'NR==2 {gsub("%","",$5); print 100-$5}')
  if (( avail_pct < 20 )); then
    warn "数据盘 $DATA_ROOT 剩余仅 ${avail_pct}%。OpenSearch 磁盘水位线:"
    warn "  85% (low)   不再往本节点分配新分片 —— 单节点下新的日索引会建不出来（集群变 yellow/red）"
    warn "  90% (high)  尝试把分片迁走 —— 单节点无处可迁"
    warn "  95% (flood) 所有索引被置为只读 (read_only_allow_delete)，写入全部失败，Fluent Bit 开始堆积磁盘缓冲"
  else
    ok "数据盘剩余 ${avail_pct}%"
  fi
}

# ---------- 解压 ----------
# 离线包先找 PACKAGE_DIR，再找安装根目录本身（直接把包和脚本放在同一目录也行）
find_pkg() { # file-name
  local d
  for d in "$PACKAGE_DIR" "$INSTALL_ROOT"; do
    [[ -f "$d/$1" ]] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# 已解压目录可能是在别的机器上解压后拷过来的：架构不对时 java / node 起不来（Exec format error），先拦下
check_arch() { # dir
  local have
  have=$(awk '/^ *architecture:/ {print $2; exit}' "$1/manifest.yml" 2>/dev/null || true)
  if [[ -n "$have" && "$have" != "$ARCH" ]]; then
    fail "$1 是 ${have} 架构，本机是 ${ARCH}（uname -m = $(uname -m)）。删掉该目录，换 linux-${ARCH} 的 tar 包后重跑"
    exit 1
  fi
}

extract() { # name
  local name="$1" file="$1-${OPENSEARCH_VERSION}-linux-${ARCH}.tar.gz" tarball
  local target="$INSTALL_ROOT/$1-${OPENSEARCH_VERSION}"
  if [[ ! -d "$target" ]]; then
    if ! tarball=$(find_pkg "$file"); then
      fail "缺少离线包 ${file}（找过 $PACKAGE_DIR 和 ${INSTALL_ROOT}）"
      local other
      other=$(ls "$PACKAGE_DIR" "$INSTALL_ROOT" 2>/dev/null | grep -E "^$1-${OPENSEARCH_VERSION}-linux-.*\.tar\.gz$" | head -n1 || true)
      [[ -n "$other" ]] && fail "找到的是 ${other}：架构与本机 ${ARCH} 不符，请下载 linux-${ARCH} 版本"
      exit 1
    fi
    if [[ -f "$tarball.sha512" ]]; then
      (cd "$(dirname "$tarball")" && sha512sum -c "$(basename "$tarball").sha512" >/dev/null) || { fail "$tarball 校验失败"; exit 1; }
      ok "$(basename "$tarball") sha512 校验通过"
    fi
    tar -xzf "$tarball" -C "$INSTALL_ROOT"
    [[ -d "$target" ]] || { fail "解压后没有 ${target}，tar 包顶层目录是 $(tar -tzf "$tarball" | head -n1)"; exit 1; }
    ok "已解压 $target"
  fi
  check_arch "$target"
  chown -R "$SVC_USER:$SVC_USER" "$target"
  ln -sfn "$target" "$INSTALL_ROOT/$name"
}

install_fluentbit() {
  if rpm -q fluent-bit >/dev/null 2>&1; then
    local have
    have=$(rpm -q --qf '%{VERSION}' fluent-bit)
    [[ "$have" == "$FLUENTBIT_VERSION" ]] && ok "Fluent Bit $have 已安装" \
      || warn "已安装 Fluent Bit ${have}，与 FLUENTBIT_VERSION=$FLUENTBIT_VERSION 不一致（不自动升级）"
    return
  fi
  local rpm_file
  rpm_file=$(ls "$PACKAGE_DIR"/fluent-bit-"${FLUENTBIT_VERSION}"-*.rpm "$INSTALL_ROOT"/fluent-bit-"${FLUENTBIT_VERSION}"-*.rpm 2>/dev/null | head -n1 || true)
  if [[ -z "$rpm_file" ]]; then
    fail "缺少 fluent-bit-${FLUENTBIT_VERSION}-*.rpm（找过 $PACKAGE_DIR 和 ${INSTALL_ROOT}）"
    [[ -d "$INSTALL_ROOT/fluent-bit-browser-experimental" ]] && fail "fluent-bit-browser-experimental 是浏览器用的 WASM SDK，不能当服务器端 Fluent Bit 用"
    fail "在能上网的机器下载: https://packages.fluentbit.io/centos/<8|9>/  （与本机 RHEL 大版本一致）"
    exit 1
  fi
  dnf install -y "$rpm_file"
  # RPM 自带的 fluent-bit.service 读 /etc/fluent-bit/；我们的同名单元放在 /etc/systemd/system，优先级更高
  ok "已安装 $(basename "$rpm_file")"
}

remove_security_dashboards() {
  # 3.10.0 的 tar 包没带 config/opensearch-performance-analyzer/，这个插件启动时报 FileNotFoundException
  # plugin-stats-metadata。日志平台用不到它（9600 端口的性能指标），直接移除
  if [[ -d "$OS_HOME/plugins/opensearch-performance-analyzer" ]]; then
    runuser -u "$SVC_USER" -- "$OS_HOME/bin/opensearch-plugin" remove opensearch-performance-analyzer
    ok "已移除 opensearch-performance-analyzer 插件（包里缺它的配置目录）"
  fi
  if [[ -d "$OSD_HOME/plugins/securityDashboards" ]]; then
    runuser -u "$SVC_USER" -- "$OSD_HOME/bin/opensearch-dashboards-plugin" remove securityDashboards
    ok "已移除 securityDashboards 插件（后端关了 security，前端插件不移除会导致 Dashboards 起不来）"
  fi
}

# ---------- 配置 ----------
render_configs() {
  render "$HERE/config/opensearch.yml" "$OS_HOME/config/opensearch.yml"
  # 不覆盖发行版自带的 jvm.options（随版本变化），只用 jvm.options.d 覆盖堆和 GC 日志
  install -d -o "$SVC_USER" -g "$SVC_USER" "$OS_HOME/config/jvm.options.d"
  render "$HERE/config/jvm.options" "$OS_HOME/config/jvm.options.d/logstack.options"
  # 自带的 GC 日志写到 $OS_HOME/logs；注释掉，只保留我们写到数据目录 logs/ 的那条
  sed -i -E 's|^([^#].*-Xlog:gc.*)$|# disabled by logstack: \1|' "$OS_HOME/config/jvm.options"
  render "$HERE/config/opensearch_dashboards.yml" "$OSD_HOME/config/opensearch_dashboards.yml"
  render "$HERE/config/fluent-bit.yaml" "$INSTALL_ROOT/fluent-bit/fluent-bit.yaml"
  render "$HERE/config/parsers.yaml" "$INSTALL_ROOT/fluent-bit/parsers.yaml"
  # 旧版本留下的 classic 格式配置，免得有人改错文件
  rm -f "$INSTALL_ROOT/fluent-bit/fluent-bit.conf" "$INSTALL_ROOT/fluent-bit/parsers.conf"
  chown "$SVC_USER:$SVC_USER" "$OS_HOME/config/opensearch.yml" "$OS_HOME/config/jvm.options.d/logstack.options" \
    "$OSD_HOME/config/opensearch_dashboards.yml"
  ok "配置已渲染到 $OS_HOME/config、$OSD_HOME/config、$INSTALL_ROOT/fluent-bit"
}

# 渲染后的配置先让 Fluent Bit 自己检查一遍（语法 / 插件名 / 参数），新大版本改了配置格式时在这里暴露，而不是启动后反复重启
check_fluentbit_config() {
  if [[ -x "$FLB_BIN" ]]; then
    if "$FLB_BIN" -c "$INSTALL_ROOT/fluent-bit/fluent-bit.yaml" --dry-run >/dev/null 2>&1; then
      ok "Fluent Bit 配置检查通过（--dry-run）"
    else
      fail "Fluent Bit 配置检查失败，原因:"
      "$FLB_BIN" -c "$INSTALL_ROOT/fluent-bit/fluent-bit.yaml" --dry-run 2>&1 | tail -n 20 || true
      exit 1
    fi
  fi
}

install_units() {
  local u
  for u in opensearch opensearch-dashboards fluent-bit; do
    render "$HERE/systemd/$u.service" "/etc/systemd/system/$u.service"
    chmod 0644 "/etc/systemd/system/$u.service"
  done
  systemctl daemon-reload
  systemctl enable opensearch opensearch-dashboards fluent-bit >/dev/null 2>&1
  ok "systemd 单元已安装并 enable"
}

start_all() {
  systemctl restart opensearch
  wait_http "$OS_URL/_cluster/health?wait_for_status=yellow&timeout=5s" 180 || { fail "OpenSearch 未就绪: journalctl -u opensearch -n 100"; exit 1; }
  ok "OpenSearch 已启动"
  bash "$HERE/bootstrap/01-index-template.sh"
  bash "$HERE/bootstrap/02-ism-policy.sh"
  bash "$HERE/bootstrap/03-ingest-pipeline.sh"
  systemctl restart opensearch-dashboards
  wait_http "$OSD_URL/api/status" 300 || { fail "Dashboards 未就绪: journalctl -u opensearch-dashboards -n 100"; exit 1; }
  ok "Dashboards 已启动"
  bash "$HERE/bootstrap/04-index-pattern.sh"
  # 模板 / pipeline 就绪后才放数据进来，第一个日索引才会套上模板
  systemctl restart fluent-bit
  ok "Fluent Bit 已启动"
}

main() {
  detect_os
  compute_heap
  if [[ "$MODE" == "config-only" ]]; then
    render_configs
    check_fluentbit_config
    install_units
    info "配置已更新，按需重启: systemctl restart opensearch opensearch-dashboards fluent-bit"
    return
  fi
  setup_user_dirs
  setup_kernel
  check_disk
  extract opensearch
  extract opensearch-dashboards
  install_fluentbit
  remove_security_dashboards
  render_configs
  check_fluentbit_config
  install_units
  if [[ "$MODE" == "full" ]]; then
    start_all
    info "下一步: sudo $HERE/firewall.sh，然后 $HERE/healthcheck.sh"
  else
    info "已跳过启动。启动顺序见 README「启停命令」"
  fi
}

main
