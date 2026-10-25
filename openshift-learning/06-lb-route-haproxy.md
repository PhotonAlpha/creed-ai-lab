# 06 负载均衡 II：Route 与 Ingress Controller（HAProxy）

## 目标

- 掌握 Route 的三种 TLS 终止方式，知道各自什么时候用。
- 用注解控制 HAProxy 的负载均衡算法、会话粘滞和超时，并**直接查看路由器生成的 haproxy.config** 来验证。
- 用 Route 粘滞解决第 05 章 veracode 多副本下的 `upload_expired` 问题；用权重分流做金丝雀发布的雏形。

## 知识点

### 链路

```
浏览器 ──TLS──► router-default（openshift-ingress 命名空间里的 HAProxy Pod）
                      │  按 Host/SNI 匹配 Route
                      ▼
               直接连接 Pod IP（取自 EndpointSlice，不经过 Service 的 ClusterIP）
```

- 路由器**直接把请求转发到 Pod**，Service 只用来提供端点列表。所以在 Route 这一层，负载均衡由 HAProxy 决定，
  而 OVN 的连接级负载均衡（第 05 章）不参与。
- 除 passthrough 外，HAProxy 工作在 **HTTP 模式，按请求做负载均衡**（不受浏览器 keep-alive 影响）；passthrough 是 TCP 模式，按连接。

### TLS 三种终止方式

| termination | TLS 在哪里终止 | 路由器到 Pod | 什么时候用 |
|---|---|---|---|
| `edge` | 路由器 | 明文 HTTP | 应用本身只说 HTTP，例如 veracode |
| `passthrough` | **Pod** | 原样转发 TLS 字节（按 SNI 路由） | 应用要自己做 TLS 或 **mTLS 客户端证书校验**（本仓库各 HTTPS 服务的模式，见第 11 章） |
| `reencrypt` | 路由器终止，再新建一段 TLS | HTTPS | 内外两段都要加密；配合 service-serving 证书 |

### 常用注解（`haproxy.router.openshift.io/*`）

| 注解 | 作用 |
|---|---|
| `balance` | `roundrobin` / `leastconn` / `source` / `random`。**默认值随版本变化**：新版本里非 passthrough 路由默认 `random`，passthrough 默认 `source`。以本机路由器生成的配置为准（见步骤 3） |
| `disable_cookies: "true"` | 关闭会话粘滞的 cookie |
| （`router.openshift.io/cookie_name`） | 自定义粘滞 cookie 的名字（默认是一串哈希） |
| `timeout` | 后端超时，默认 30s。veracode 导出大 Excel 时可以调大 |
| `rate-limit-connections*` | 按源 IP 限流 |
| `ip_whitelist` | 源 IP 白名单 |

- **会话粘滞默认是开着的**（http、edge、reencrypt 路由）：路由器在响应里写一个 cookie，带着这个 cookie 的后续请求会回到同一个 Pod。
  **浏览器自动带 cookie，curl 默认不带**，所以「浏览器里一切正常、脚本测试时报错」是这个机制最典型的表现。
- **权重分流**：`spec.to` 加上 `spec.alternateBackends`，最多 4 个后端，按权重分配流量。用 `oc set route-backends` 修改，第 09 章会用到。

## 步骤

### 1. 用 cookie 粘滞修好上传问题

```bash
oc scale deploy/veracode --replicas=3
HOST=$(oc get route veracode -o jsonpath='{.spec.host}')
printf 'CVE,Title,Affected Version\nCVE-2022-42889,Text4Shell,2.3.0\n' > tmp/r.csv

# 不带 cookie：每个请求各自负载均衡，会随机出现 upload_expired
ID=$(curl -sk -F file=@tmp/r.csv https://$HOST/api/veracode/analysis/upload | python3 -c 'import json,sys;print(json.load(sys.stdin)["page"]["id"])')
for i in $(seq 6); do curl -sk -XPOST https://$HOST/api/veracode/analysis -H 'Content-Type: application/json' -d "{\"uploadId\":\"$ID\"}" | head -c 60; echo; done

# 带 cookie jar：上传时拿到粘滞 cookie，后续请求都回到同一个 Pod
rm -f tmp/jar
ID=$(curl -sk -c tmp/jar -b tmp/jar -F file=@tmp/r.csv https://$HOST/api/veracode/analysis/upload | python3 -c 'import json,sys;print(json.load(sys.stdin)["page"]["id"])')
for i in $(seq 6); do curl -sk -c tmp/jar -b tmp/jar -XPOST https://$HOST/api/veracode/analysis -H 'Content-Type: application/json' -d "{\"uploadId\":\"$ID\"}" | head -c 60; echo; done
cat tmp/jar        # 看路由器写的 cookie
```

### 2. 改算法、关粘滞，观察分布

```bash
oc annotate route veracode haproxy.router.openshift.io/disable_cookies=true --overwrite
oc annotate route veracode haproxy.router.openshift.io/balance=roundrobin --overwrite
for i in $(seq 9); do curl -sk https://$HOST/actuator/env/HOSTNAME | grep -o 'veracode-[a-z0-9-]*'; done | sort | uniq -c

oc annotate route veracode haproxy.router.openshift.io/balance=source --overwrite   # 同一个源 IP 固定落到一个 Pod
oc annotate route veracode haproxy.router.openshift.io/timeout=120s --overwrite
```

### 3. 直接查看 HAProxy 配置（需要 kubeadmin）

```bash
oc login -u kubeadmin https://api.crc.testing:6443
ROUTER=$(oc -n openshift-ingress get pod -o name | head -1)
oc -n openshift-ingress exec $ROUTER -- grep -A25 'backend be_edge_http:creed-lab:veracode' /var/lib/haproxy/conf/haproxy.config
# 能看到：balance 用的算法、cookie 设置、timeout server，以及每个 Pod IP 对应的一行 server
oc login -u developer https://api.crc.testing:6443
```

### 4. 权重分流（金丝雀发布的雏形）

```bash
# 部署第二个版本（这里简单复制一份同镜像的 Deployment 和 Service 作为 v2）
oc create deployment veracode-v2 --image=image-registry.openshift-image-registry.svc:5000/creed-lab/veracode:latest --port=18097
oc set env deploy/veracode-v2 LOGGING_FILE_PATH=/tmp/logs OTEL_SDK_DISABLED=true
oc expose deploy/veracode-v2 --port=8080 --target-port=18097 --name=veracode-v2
oc set route-backends veracode veracode=90 veracode-v2=10
oc set route-backends veracode          # 查看当前的权重分配
for i in $(seq 20); do curl -sk https://$HOST/actuator/env/HOSTNAME | grep -o 'veracode-v2' ; done | wc -l   # 大约 2 次
oc set route-backends veracode veracode=100 veracode-v2=0
```

## 检查点

- [ ] 能复述三种 TLS 终止方式的区别，并说出本仓库哪些服务适合用 passthrough
- [ ] 不带 cookie 时复现了 `upload_expired`；带上 cookie jar 后 6 次全部成功
- [ ] 在 haproxy.config 里找到了 veracode 的 backend，并能对照注解，解释其中的 `balance` 和 `cookie` 两行
- [ ] 关闭粘滞并使用 `roundrobin` 后，9 次请求在 3 个 Pod 上的分布是 3/3/3
- [ ] 权重设为 90/10 时，20 次请求里 v2 出现了 1–4 次

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。实验 1、2、5 需要 kubeadmin（读路由器 Pod）。

```bash
ROUTER=$(oc -n openshift-ingress get pod -o name | head -1)
rexec() { oc -n openshift-ingress exec $ROUTER -- "$@"; }
```

### 实验 1：路由器靠什么把一个主机名对应到 backend

```bash
rexec ls /var/lib/haproxy/conf/ | grep '\.map$'
rexec grep -h veracode /var/lib/haproxy/conf/os_edge_reencrypt_be.map /var/lib/haproxy/conf/os_http_be.map 2>/dev/null
rexec grep -n 'backend be_edge_http:creed-lab:veracode' /var/lib/haproxy/conf/haproxy.config
```

**观察**：
- 路由器把 Route 翻译成两部分：
  - **map 文件**：「主机名 + 路径 → backend 名」的映射表，按 TLS 类型分成好几个文件；
  - **haproxy.config 里的 backend 段**：负载均衡算法、cookie、超时，以及每个 Pod 一行 `server`。
- 前端统一接入流量后查 map，决定交给哪个 backend。
- backend 名的格式是 `be_<类型>:<命名空间>:<route名>`，排查时用 Route 名直接 grep。

### 实验 2：路由器是怎么跟上 Pod 变化的

```bash
rexec grep -c 'server pod:veracode' /var/lib/haproxy/conf/haproxy.config      # 当前 Pod 数
oc scale deploy/veracode --replicas=4 -n creed-lab; sleep 10
rexec grep 'server pod:veracode' /var/lib/haproxy/conf/haproxy.config | awk '{print $2, $3}'
oc -n openshift-ingress logs $ROUTER --since=2m | grep -iE 'reload|router reloaded' | tail -3
```

**观察 / 解释**：
- 路由器进程监听 Route、Service 和 EndpointSlice。端点一变，它就重写配置并让 HAProxy 重新加载（或通过运行时 API 动态更新 server）。
- `server` 行里是 **Pod IP:18097**，没有 Service 的 ClusterIP。这再次证明 Route 的流量不经过 OVN 的 Service 负载均衡。
- 改注解后「过一两秒才生效」，就是这个重写和重新加载的延迟。

### 实验 3：粘滞 cookie 里存的是什么

```bash
rm -f tmp/jar; curl -sk -c tmp/jar https://$HOST/api/veracode/settings -o /dev/null
cat tmp/jar | grep -v '^#'                                          # cookie 名和值
rexec grep -A30 'backend be_edge_http:creed-lab:veracode' /var/lib/haproxy/conf/haproxy.config | grep -E 'cookie|server pod'
```

**观察**：
- backend 里有一行 `cookie <名字> insert indirect nocache httponly`，每一行 `server` 末尾都带着 `cookie <值>`。
- 你拿到的 cookie 值和其中某一行的值一模一样。

**动手改 cookie，验证机制**：

```bash
OTHER=$(rexec grep -A30 'backend be_edge_http:creed-lab:veracode' /var/lib/haproxy/conf/haproxy.config | grep 'server pod' | awk '{for(i=1;i<=NF;i++) if($i=="cookie") print $(i+1)}' | tail -1)
NAME=$(grep -v '^#' tmp/jar | awk 'NF{print $6}' | tail -1)
for i in 1 2 3; do curl -sk -b "$NAME=$OTHER" https://$HOST/actuator/env/HOSTNAME | grep -o 'veracode-[a-z0-9-]*' | head -1; done
```

三次都落在同一个 Pod：**粘滞完全由客户端带回来的那个值决定**。删掉那个 Pod 再请求一次，
HAProxy 发现 cookie 指向的 server 已不存在，就会重新选一个（redispatch），并下发新的 cookie。

### 实验 4：三种 TLS 终止，客户端看到的证书各是哪一张

先完成第 03 章练习 1（veracode 用自己的证书跑 HTTPS），再依次把 Route 换成三种终止方式：

```bash
show_cert() { openssl s_client -connect $HOST:443 -servername $HOST </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer; }

# edge：路由器的默认通配证书；路由器到 Pod 是明文。Pod 已经改成 HTTPS 了，
#       所以应用返回 400 This combination of host and port requires TLS（第 03 章练习 1 实测过同样的响应）
oc create route edge veracode-edge --service=veracode --port=http; HOST=$(oc get route veracode-edge -o jsonpath='{.spec.host}'); show_cert
curl -sk https://$HOST/actuator/health

# reencrypt：外面还是路由器的证书，路由器再用 ca-chain.crt 校验 Pod 的证书
oc create route reencrypt veracode-re --service=veracode --port=http \
  --dest-ca-cert=.support/scripts/pki/ca-chain.crt
HOST=$(oc get route veracode-re -o jsonpath='{.spec.host}'); show_cert; curl -sk https://$HOST/actuator/health

# passthrough：客户端直接看到 Pod 的证书 CN=veracode
oc create route passthrough veracode-pt --service=veracode --port=http
HOST=$(oc get route veracode-pt -o jsonpath='{.spec.host}'); show_cert
oc delete route veracode-edge veracode-re veracode-pt
```

**观察 / 解释**：
- edge 和 reencrypt 显示的是 `*.apps-crc.testing` 的证书（由 ingress operator 签发）；passthrough 显示的是 `CN=veracode`，
  而且浏览器会报主机名不匹配，因为证书的 SAN 里没有 Route 的主机名。
- reencrypt 时，路由器会用 `destinationCACertificate` 校验后端证书，并按 **Service 的 DNS 名**
  （`veracode.creed-lab.svc`）校验主机名。所以第 03 章签证书时 SAN 里必须有它。
  把 `--dest-ca-cert` 换成一张无关的 CA 证书，会得到 503。
- passthrough 下路由器只看 SNI，看不到 HTTP：cookie、路径、HTTP 头注解全部失效（见「常见坑」）。

### 实验 5：路由器给请求加了哪些头

veracode 没有回显请求头的接口，所以临时部署一个会回显的小服务：

```bash
oc create deployment echo --image=registry.access.redhat.com/ubi9/python-312 -- \
  python3 -c 'import http.server as h
class H(h.BaseHTTPRequestHandler):
    def do_GET(s):
        s.send_response(200); s.end_headers(); s.wfile.write(str(s.headers).encode())
h.HTTPServer(("",8080),H).serve_forever()'
oc expose deploy/echo --port=8080
oc create route edge echo --service=echo
curl -sk https://$(oc get route echo -o jsonpath='{.spec.host}')/ | grep -iE 'forwarded|x-forwarded'
oc annotate route echo haproxy.router.openshift.io/set-forwarded-headers=never --overwrite; sleep 3
curl -sk https://$(oc get route echo -o jsonpath='{.spec.host}')/ | grep -iE 'forwarded|x-forwarded'
oc delete deploy/echo svc/echo route/echo
```

**观察**：
- 默认会加上 `X-Forwarded-For`、`X-Forwarded-Host`、`X-Forwarded-Port`、`X-Forwarded-Proto: https` 和 `Forwarded`。
- 应用拿到的「客户端 IP」实际上是路由器的 IP，真实客户端 IP 在 `X-Forwarded-For` 里。
  Spring Boot 要配置 `server.forward-headers-strategy=native`（或 framework）才会把它当成 `request.getRemoteAddr()`，
  生成重定向 URL 时也才会用 https。
- 这也解释了第 05 章的说法：经过 Route 进来的流量，按源 IP 做亲和没有意义。

### 检查点（机制）

- [ ] 在 map 文件里找到了 veracode 主机名对应的 backend 名
- [ ] 扩容后，haproxy.config 里的 `server` 行跟着变多，地址是 Pod IP
- [ ] 手工伪造 cookie 值，把请求固定到了指定的 Pod
- [ ] 三种 TLS 终止各自看到的证书和你的预期一致，并能解释 reencrypt 为什么要校验 Service 的 DNS 名
- [ ] 看到了路由器注入的 `X-Forwarded-*` 头

## 常见坑

- **粘滞只是缓解了问题，没有根治**：Pod 被重启或缩容时，粘在它上面的会话仍然会丢失。根治办法是让应用无状态，或者使用共享存储。
- **passthrough 路由看不到 HTTP**：它没有 cookie 粘滞，路径也不起作用（只能按主机名路由），也加不了 HTTP 头。
- **`timeout` 不够长**：大文件导出或者慢接口会被路由器在 30s 时切断，表现为 504，但应用日志里看不到任何错误。
- **改注解之后立刻测试**：路由器重新加载配置需要一两秒；看到旧的行为时先确认 haproxy.config 已经更新。

## 参考文档

- OCP 文档：*Networking → Configuring routes*，重点看 *Route configuration*、*Route-specific annotations*、*Secured routes*
- OCP 文档：*Networking → Ingress Operator*（IngressController 的全局设置）
- HAProxy 文档（`balance`、`cookie` 指令）：https://docs.haproxy.org/
