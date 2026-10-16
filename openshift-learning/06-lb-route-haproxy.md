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

## 常见坑

- **粘滞只是缓解了问题，没有根治**：Pod 被重启或缩容时，粘在它上面的会话仍然会丢失。根治办法是让应用无状态，或者使用共享存储。
- **passthrough 路由看不到 HTTP**：它没有 cookie 粘滞，路径也不起作用（只能按主机名路由），也加不了 HTTP 头。
- **`timeout` 不够长**：大文件导出或者慢接口会被路由器在 30s 时切断，表现为 504，但应用日志里看不到任何错误。
- **改注解之后立刻测试**：路由器重新加载配置需要一两秒；看到旧的行为时先确认 haproxy.config 已经更新。

## 参考文档

- OCP 文档：*Networking → Configuring routes*，重点看 *Route configuration*、*Route-specific annotations*、*Secured routes*
- OCP 文档：*Networking → Ingress Operator*（IngressController 的全局设置）
- HAProxy 文档（`balance`、`cookie` 指令）：https://docs.haproxy.org/
