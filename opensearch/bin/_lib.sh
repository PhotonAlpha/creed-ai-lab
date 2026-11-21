#!/usr/bin/env bash
# 手动启停脚本（bin/start-*.sh / bin/stop-*.sh）的公共部分，被 source，不单独执行。
# 与 install.sh / lib/common.sh 无关：这里只是把 MANUAL.md 里的 nohup 命令包装起来。
#
# 路径都有默认值，按需在 bin/env.local.sh 里覆盖（不进 git，样例见 bin/env.example.sh）：
#   OS_HOME    OpenSearch 解压目录          默认：本目录下的 opensearch-<版本>/
#   OSD_HOME   Dashboards 解压目录          默认：本目录下的 opensearch-dashboards-<版本>/
#   RUN_DIR    两者的 pid 和控制台日志       默认：/data/opensearch（存在且可写时），否则本目录下的 run/
#   FB         fluent-bit 可执行文件         默认：/opt/fluent-bit/bin/fluent-bit → /usr/local/bin/fluent-bit → fluent-bit-pkg/ 里解开的
#   FB_DIR     aggregator.yaml / forwarder.yaml 所在目录（pid、日志、state/ 也在这里） 默认：本目录下的 fluent-bit-manual/

LOGSTACK_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
[[ -f "$LOGSTACK_HOME/bin/env.local.sh" ]] && . "$LOGSTACK_HOME/bin/env.local.sh"

if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_OFF=""
fi
info() { printf '%s[INFO]%s %s\n' "$C_BLUE" "$C_OFF" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }

# 第一个存在的目录 / 可执行文件（参数里的通配符没匹配上时原样传进来，-d / -x 会判它不存在）
first_dir()  { local d; for d in "$@"; do [[ -d "$d" ]] && { echo "${d%/}"; return; }; done; }
first_exec() { local f; for f in "$@"; do [[ -x "$f" ]] && { echo "$f"; return; }; done; }

: "${OS_HOME:=$(first_dir "$LOGSTACK_HOME"/opensearch-[0-9]*/)}"
: "${OSD_HOME:=$(first_dir "$LOGSTACK_HOME"/opensearch-dashboards-[0-9]*/)}"
if [[ -z "${RUN_DIR:-}" ]]; then
  if [[ -d /data/opensearch && -w /data/opensearch ]]; then RUN_DIR=/data/opensearch; else RUN_DIR="$LOGSTACK_HOME/run"; fi
fi
: "${FB:=$(first_exec /opt/fluent-bit/bin/fluent-bit /usr/local/bin/fluent-bit \
  "$LOGSTACK_HOME"/fluent-bit-pkg/*/Payload/usr/local/bin/fluent-bit)}"
: "${FB_DIR:=$LOGSTACK_HOME/fluent-bit-manual}"
: "${WAIT_TIMEOUT:=}"          # 等待就绪的秒数，空 = 各脚本自己的默认值

FORCE=0
NO_WAIT=0
parse_flags() {
  local a
  for a in "$@"; do
    case "$a" in
      -f|--force)   FORCE=1 ;;
      --no-wait)    NO_WAIT=1 ;;
      -h|--help)    sed -n '2,/^$/s/^# \{0,1\}//p' "$0"; exit 0 ;;
      *)            fail "不认识的参数：${a}（-h 看用法）"; exit 2 ;;
    esac
  done
}

# 下面两个总是返回 0：脚本是 set -e + pipefail，x="$(...)" 里返回非 0 会让整个脚本无声退出
pid_of()    { [[ -f "$1" ]] && cat "$1" || true; }
alive()     { [[ -n "${1:-}" ]] && kill -0 "$1" 2>/dev/null; }
# 端口是否有人在听（bash 内置的 /dev/tcp，Linux / macOS 都不用额外工具）
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# 命令行匹配 PATTERN 的进程（不是这个脚本启动的、没有 pid 文件的那种）
others()    { { pgrep -f "$1" 2>/dev/null || true; } | { grep -vx "$$" || true; } | tr '\n' ' ' | sed 's/ $//'; }

# 已在运行：ALREADY_RUNNING=1，不重复启动（调用方照样等它就绪 —— 可能是上一次启动还没起来）；
# 有同类进程但不是 pid 文件里那个 / 端口被占：退出 1
ALREADY_RUNNING=0
check_not_running() {   # NAME PIDFILE PATTERN [PORT]
  local name="$1" pidfile="$2" pattern="$3" port="${4:-}" pid strays
  pid="$(pid_of "$pidfile")"
  if alive "$pid"; then
    ok "$name 已经在运行（pid ${pid}，${pidfile}），不重复启动"
    ALREADY_RUNNING=1
    return 0
  fi
  [[ -n "$pid" ]] && { warn "$pidfile 里的 pid $pid 已经不在了，删掉旧 pid 文件"; rm -f "$pidfile"; }
  strays="$(others "$pattern")"
  if [[ -n "$strays" ]]; then
    fail "$name 已经有进程在跑（pid ${strays}），但不是本脚本启动的（不在 $pidfile 里）"
    fail "  先在它的终端里 Ctrl+C / kill ${strays}，或者让本脚本接管：echo <pid> > $pidfile"
    exit 1
  fi
  if [[ -n "$port" ]] && port_open "$port"; then
    fail "端口 $port 已经被占用，$name 起不来。lsof -nP -iTCP:$port -sTCP:LISTEN 看是谁"
    exit 1
  fi
}

# nohup 启动：WORKDIR LOGFILE PIDFILE CMD...（日志追加写，每次启动前写一行分隔）
start_bg() {
  local workdir="$1" log="$2" pidfile="$3"; shift 3
  mkdir -p "$(dirname "$log")" "$(dirname "$pidfile")"
  printf '\n===== %s start: %s =====\n' "$(date '+%F %T')" "$*" >> "$log"
  # cd 和 nohup 分开写：cd && nohup ... & 会把整个列表放进一个后台子 shell，$! 就成了那个 bash 而不是服务本身。
  # nohup 直接 exec 服务（opensearch / opensearch-dashboards 的启动脚本最后也是 exec），pid 文件里就是服务的 pid
  ( cd "$workdir" || exit 1; nohup "$@" >> "$log" 2>&1 & echo $! > "$pidfile" )
  info "已启动 pid $(cat "$pidfile")，日志 $log"
}

# 等 URL 返回 2xx；进程中途退出就立刻失败。URL TIMEOUT PIDFILE LOGFILE NAME
wait_ready() {
  local url="$1" timeout="${WAIT_TIMEOUT:-$2}" pidfile="$3" log="$4" name="$5" waited=0
  (( NO_WAIT )) && { info "--no-wait：不等 $name 就绪。之后自己看：curl -s $url"; return 0; }
  info "等 $name 就绪（${url}，最多 ${timeout}s）..."
  until [[ "$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)" == 2* ]]; do
    if ! alive "$(pid_of "$pidfile")"; then
      fail "$name 启动后退出了。日志最后 30 行（${log}）："
      tail -n 30 "$log" >&2
      rm -f "$pidfile"
      return 1
    fi
    if (( waited >= timeout )); then
      fail "$name ${timeout}s 内没有就绪（进程还在，pid $(pid_of "$pidfile")）。看日志：tail -f $log"
      return 1
    fi
    sleep 3; waited=$(( waited + 3 ))
  done
  ok "$name 就绪（${waited}s）"
}

# SIGTERM 正常关闭，等 TIMEOUT 秒；还在的话 --force 才 kill -9。NAME PIDFILE PATTERN TIMEOUT
stop_by_pidfile() {
  local name="$1" pidfile="$2" pattern="$3" timeout="$4" pid strays waited=0
  pid="$(pid_of "$pidfile")"
  if ! alive "$pid"; then
    [[ -n "$pid" ]] && rm -f "$pidfile"
    strays="$(others "$pattern")"
    if [[ -n "$strays" ]]; then
      warn "$name 没有 pid 文件（或里面的进程已不在），但有同类进程在跑：pid $strays —— 不是本脚本启动的，不替你停"
      warn "  确认是它的话：kill ${strays}；或者 echo <pid> > $pidfile 后再执行本脚本"
      return 1
    fi
    ok "$name 没有在运行"
    return 0
  fi
  info "停止 ${name}（pid ${pid}，SIGTERM，最多等 ${timeout}s）..."
  kill "$pid"
  while alive "$pid"; do
    if (( waited >= timeout )); then
      if (( FORCE )); then
        warn "$name ${timeout}s 还没退出，kill -9"
        kill -9 "$pid" 2>/dev/null || true
        sleep 1
        break
      fi
      fail "$name ${timeout}s 还没退出（pid ${pid}）。再等一会儿重试，或加 --force 强制 kill -9"
      return 1
    fi
    sleep 1; waited=$(( waited + 1 ))
  done
  rm -f "$pidfile"
  ok "$name 已停止"
}

need_not_root() {
  if [[ "$(id -u)" == 0 ]]; then
    fail "$1 不能用 root 启动（MANUAL 0.3）。换普通用户：sudo su - logstack"
    exit 1
  fi
}

need_fb() {
  if [[ -z "$FB" || ! -x "$FB" ]]; then
    fail "找不到 fluent-bit 可执行文件。装 RPM（/opt/fluent-bit/bin/fluent-bit）、pkg，或在 bin/env.local.sh 里写 FB=<路径>"
    exit 1
  fi
}
