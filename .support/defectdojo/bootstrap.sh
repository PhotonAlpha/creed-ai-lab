#!/usr/bin/env bash
# 可选：等本地 DefectDojo 就绪，用 admin 账号换取 API v2 key，写入 <repo>/tmp/defectdojo.env（git-ignored），
# 供 curl 走 API 导入（UI 导入不需要它）:
#
#   .support/defectdojo/bootstrap.sh
#   set -a; . tmp/defectdojo.env; set +a
#   curl -X POST "$DD_URL/api/v2/reimport-scan/" -H "Authorization: Token $DD_API_KEY" \
#     -F scan_type="Veracode Scan" -F file=@.support/veracode/veracode-detailedreport-example.xml \
#     -F product_type_name=Veracode -F product_name=creed-shop -F engagement_name="Veracode 2.4.0" \
#     -F test_title="Veracode SCA" -F auto_create_context=true
#
# 同一用户的 key 是固定的（重复执行拿到同一个），重新生成在 UI: 右上角用户 -> API v2 Key。
set -euo pipefail

DD_URL="${DD_URL:-http://localhost:8380}"
DD_USER="${DD_USER:-admin}"
DD_PASSWORD="${DD_PASSWORD:-defectdojo_pw}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO_ROOT/tmp/defectdojo.env"

echo "waiting for $DD_URL ..."
for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$DD_URL/api/v2/api-token-auth/" -X POST \
    -H 'Content-Type: application/json' -d '{}' || true)
  # 400 = the endpoint answers (empty credentials rejected): uwsgi and nginx are up
  [ "$code" = "400" ] && break
  sleep 2
done

token=$(curl -sf "$DD_URL/api/v2/api-token-auth/" -X POST -H 'Content-Type: application/json' \
  -d "{\"username\":\"$DD_USER\",\"password\":\"$DD_PASSWORD\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

# the key really works
curl -sf -o /dev/null "$DD_URL/api/v2/users/?limit=1" -H "Authorization: Token $token"

mkdir -p "$REPO_ROOT/tmp"
umask 077
cat > "$OUT" <<ENV
DD_URL=$DD_URL
DD_API_KEY=$token
ENV
echo "DefectDojo ready: $DD_URL (user $DD_USER); API key written to $OUT"
