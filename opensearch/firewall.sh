#!/usr/bin/env bash
# 防火墙：9200 / 9600 / 9300 只允许本机；24224 只允许 FORWARDER_IPS；5601 只允许 OFFICE_CIDR。
# 自动识别 firewalld（RHEL）/ ufw。可重复执行：已有规则跳过，24224 中不在 FORWARDER_IPS 里的旧规则会被删掉。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

if [[ "$(uname -s)" == "Darwin" ]]; then
  info "macOS 本地环境：所有服务只监听 127.0.0.1，不需要防火墙规则"
  exit 0
fi
[[ $EUID -eq 0 ]] || { fail "请用 root 执行"; exit 1; }

IFS=', ' read -r -a IPS <<<"$FORWARDER_IPS"
if (( ${#IPS[@]} == 0 )); then
  warn "FORWARDER_IPS 为空：24224 不放行任何来源。填好 install.sh 里的 FORWARDER_IPS 后重跑本脚本"
fi

in_list() { local x; for x in "${IPS[@]}"; do [[ "$x" == "$1" ]] && return 0; done; return 1; }

setup_firewalld() {
  local zone p rule addr
  zone=$(firewall-cmd --get-default-zone)
  info "firewalld，默认 zone: $zone"

  # 端口全部用来源受限的 rich rule 放行；普通的 --add-port 会对所有来源开放，发现就撤掉
  for p in 9200 9300 9600 5601 24224 2020; do
    if firewall-cmd --permanent --zone="$zone" --query-port="$p/tcp" >/dev/null 2>&1; then
      firewall-cmd --permanent --zone="$zone" --remove-port="$p/tcp" >/dev/null
      warn "已撤掉对所有来源开放的 $p/tcp"
    fi
  done

  add_rich() {
    if firewall-cmd --permanent --zone="$zone" --query-rich-rule="$1" >/dev/null 2>&1; then
      ok "已存在: $1"
    else
      firewall-cmd --permanent --zone="$zone" --add-rich-rule="$1" >/dev/null
      ok "已添加: $1"
    fi
  }

  add_rich "rule family=\"ipv4\" source address=\"$OFFICE_CIDR\" port port=\"5601\" protocol=\"tcp\" accept"
  for addr in "${IPS[@]}"; do
    add_rich "rule family=\"ipv4\" source address=\"$addr\" port port=\"24224\" protocol=\"tcp\" accept"
  done

  # 同步：删掉不在 FORWARDER_IPS 里的 24224 规则
  while IFS= read -r rule; do
    [[ "$rule" == *'port="24224"'* ]] || continue
    addr=$(sed -E 's/.*source address="([^"]+)".*/\1/' <<<"$rule")
    if ! in_list "$addr"; then
      firewall-cmd --permanent --zone="$zone" --remove-rich-rule="$rule" >/dev/null
      warn "已删除不在 FORWARDER_IPS 中的规则: $rule"
    fi
  done < <(firewall-cmd --permanent --zone="$zone" --list-rich-rules)

  firewall-cmd --reload >/dev/null
  ok "firewalld 已重载。9200/9300/9600/2020 无放行规则 = 只有本机（loopback 不受 firewalld 限制）"
}

setup_ufw() {
  local addr
  info "ufw"
  ufw allow from "$OFFICE_CIDR" to any port 5601 proto tcp
  for addr in "${IPS[@]}"; do
    ufw allow from "$addr" to any port 24224 proto tcp
  done
  for p in 9200 9300 9600 2020; do ufw deny "$p/tcp"; done
  ufw --force enable
  ok "ufw 规则已应用（ufw 会跳过已存在的规则）。注意: ufw 不会自动删除旧的 24224 规则，用 ufw status numbered 检查"
}

if systemctl is-active --quiet firewalld 2>/dev/null; then
  setup_firewalld
elif command -v ufw >/dev/null 2>&1; then
  setup_ufw
else
  fail "没有运行中的 firewalld，也没有 ufw。RHEL 上: systemctl enable --now firewalld 后重跑"
  exit 1
fi
