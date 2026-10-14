#!/usr/bin/env bash
# 一键回归 Camel 教程的全部 8 个 API。用法：docs/camel-tutorial/scripts/test-apis.sh [https://host:port/context]
# 前提：creed-simple-metrics 已启动（仓库根目录：mvn -pl creed-simple-metrics spring-boot:run
#       -Dspring-boot.run.workingDirectory="$PWD"）。HTTPS 自签证书，所以 curl 带 -k。
set -u
ROOT="${1:-https://localhost:8096/simple}"
BASE="${ROOT}/camel/api/v1/orders"
curl() { command curl -k "$@"; }

hit() {   # hit <标题> <路径> <json>
  printf '\n\033[1;36m===== %s =====\033[0m\n' "$1"
  printf '\033[0;90mPOST %s\n%s\033[0m\n' "$BASE$2" "$3"
  curl -s -X POST "$BASE$2" -H 'Content-Type: application/json' -d "$3"
  printf '\n'
}

hit "API-1  基础链路 <route>/<from>/<to>/<process>" /basic \
  '{"orderId":"A-1001","amount":666,"channel":"WEB"}'

hit "API-2a <choice> 命中 when#1 (simple: amount>10000) + <filter> 放行" /dispatch \
  '{"orderId":"B-2001","amount":20000,"channel":"WEB"}'

hit "API-2b <choice> 命中 when#2 (jsonpath: channel==APP)" /dispatch \
  '{"orderId":"B-2002","amount":500,"channel":"APP"}'

hit "API-2c <choice> 走 otherwise + <filter> 拦截 (amount<100，但路由照常收口)" /dispatch \
  '{"orderId":"B-2003","amount":50,"channel":"WEB"}'

hit "API-3  ProducerTemplate：远程 HTTP + 回调本地 direct 路由" /quote \
  '{"orderId":"C-3001","amount":2000,"currency":"USD"}'

hit "API-4  <multicast> 三路并行 + aggregationStrategy 聚合" /check \
  '{"orderId":"D-4001","amount":3000}'

hit "API-5  <wireTap> 旁路 + <split> 拆单 + <toD> 动态路由" /split \
  '{"orderId":"E-5001","items":[{"sku":"BK-01","type":"BOOK","qty":2},{"sku":"FD-09","type":"FOOD","qty":1},{"sku":"XX-77","type":"TOY","qty":5}]}'

hit "API-6  综合编排：成功路径 (VIP + APPROVED + 完整履约)" /fulfil \
  '{"orderId":"F-6001","amount":20000,"channel":"APP","items":[{"sku":"BK-01","type":"BOOK","qty":2},{"sku":"FD-09","type":"FOOD","qty":1},{"sku":"XX-77","type":"TOY","qty":5}]}'

hit "API-6  综合编排：拒绝路径 (filter 拦截履约，仍返回 200 与完整结构)" /fulfil \
  '{"orderId":"F-6002","amount":90000,"channel":"WEB","items":[{"sku":"BK-02","type":"BOOK","qty":1}]}'

hit "API-7  自定义组件 httpexchange: 调用 @HttpExchange 接口（出入站各一次 ftl 转换）" /accounts \
  '{"orderId":"G-7001","userName":"ethan","channel":"APP","amount":2000}'

hit "API-7b httpexchange:artisanClient/createUser（@RequestBody String，开户）" /accounts/create \
  '{"orderId":"G-7002","userName":"ethan","channel":"WEB","amount":500}'

hit "API-7c httpexchange:artisanClient/getUser（查回 7b 刚开的账户）" /accounts/detail \
  '{"orderId":"G-7003","accountId":"ACC-G-7002"}'

# API-8 不在 /api/v1/orders 下，且是 multipart，不走 hit()
UPLOAD="${ROOT}/camel/api/v1/files/upload"

printf '\n\033[1;36m===== %s =====\033[0m\n' "API-8a 上传：multipart，file 字段是文件内容的 Base64（filter 解码成二进制文件）"
curl -s -X POST "$UPLOAD" -F orderId=H-8001 -F fileName=hello.txt -F contentType=text/plain \
  -F "file=$(printf 'hello camel upload\n' | base64)"
printf '\n'

printf '\n\033[1;36m===== %s =====\033[0m\n' "API-8b 上传：file 是 Base64 data URL（content type 取自前缀；--form-string 防止 curl 把 ; 当选项）"
curl -s -X POST "$UPLOAD" -F orderId=H-8002 -F fileName=hello.txt \
  --form-string "file=data:text/plain;base64,$(printf 'hello camel upload\n' | base64)"
printf '\n'

printf '\n\033[1;32m全部用例执行完毕，请对照应用控制台日志观察每一步 EIP 的执行轨迹。\033[0m\n'
