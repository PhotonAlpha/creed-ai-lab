# 05 负载均衡 I：Service 层

## 目标

- 亲眼看到同一个 Service 背后多个 Pod 在分担请求，并且能判断出每个请求落在了哪个 Pod 上。
- 理解 **Service 是按连接做负载均衡，不是按请求**，以及这对 HTTP keep-alive 意味着什么。
- 用 readiness 控制一个 Pod 是否接收流量。

## 知识点

- **Service 的负载均衡由谁实现**：OCP 4.x 默认的网络插件是 **OVN-Kubernetes**。ClusterIP 是 OVN 里的一个负载均衡器，
  在**建立连接时**挑选一个后端，挑选范围就是 EndpointSlice 里就绪的 Pod。
- **按连接而非按请求**：客户端保持长连接（HTTP keep-alive、连接池）时，这条连接上的所有请求都会落到同一个 Pod。
  这和本仓库 simple-metrics 的 HttpClient 连接池经验一致：没有设置 TTL 的长连接会一直粘在第一次选中的实例上。
  要按请求分摊，需要 L7 负载均衡（下一章的 Route/HAProxy、service mesh），或者在客户端定期回收连接。
- **readiness 就是负载均衡的开关**：Pod 不就绪时会从 EndpointSlice 中移除，不再有新连接进来；已经建立的连接不受影响。
- **`sessionAffinity: ClientIP`**：同一个源 IP 固定落到同一个 Pod（有超时）。在集群内部通常不需要；
  而且经过 Route 进来的流量，源 IP 都是路由器的 IP，这时 ClientIP 亲和没有意义。
- **Headless Service（`clusterIP: None`）**：DNS 直接返回所有 Pod 的 IP，由客户端自己选择，常用于 StatefulSet 和客户端负载均衡。
- **怎么看出是哪个 Pod 在响应**：veracode 的 actuator 暴露了全部端点，`/actuator/env/HOSTNAME` 返回的就是 Pod 名。
  （这也说明了一个安全问题：生产环境里 `env` 端点不应该对外暴露，第 11 章会处理。）

## 步骤

```bash
oc scale deploy/veracode --replicas=3
oc rollout status deploy/veracode
oc get endpointslices -l kubernetes.io/service-name=veracode -o wide

# 在集群内部用一个临时 Pod 调用 Service（不经过 Route）
oc run curl --rm -it --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never -- \
  sh -c 'for i in $(seq 1 12); do curl -s http://veracode:8080/actuator/env/HOSTNAME | sed -E "s/.*\"value\":\"([^\"]+)\".*/\1/"; echo; done'
```

### 实验 1：按连接，不是按请求

```bash
# 每次调用 curl 都会新建连接 → 请求分散到各个 Pod（上面已经看到）
# 同一个 curl 进程里连续请求同一个地址 → 复用同一条 keep-alive 连接 → 全部落在同一个 Pod
oc run curl2 --rm -it --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never -- \
  sh -c 'curl -s http://veracode:8080/actuator/env/HOSTNAME http://veracode:8080/actuator/env/HOSTNAME http://veracode:8080/actuator/env/HOSTNAME | grep -o "veracode-[a-z0-9-]*"'
```

### 实验 2：上传接口在多副本下失效（应用层的会话状态问题）

```bash
oc run curl3 --rm -it --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never -- sh -c '
printf "CVE,Title,Affected Version\nCVE-2022-42889,Text4Shell,2.3.0\n" > /tmp/r.csv
ID=$(curl -s -F file=@/tmp/r.csv http://veracode:8080/api/veracode/analysis/upload | sed -E "s/.*\"id\":\"([0-9a-f-]{36})\".*/\1/")
for i in 1 2 3 4 5 6; do
  curl -s -XPOST http://veracode:8080/api/veracode/analysis -H "Content-Type: application/json" -d "{\"uploadId\":\"$ID\"}" | grep -o "\"error\":\"[a-z_]*\"\|\"total\":[0-9]*"
done'
```

预期会混着出现 `"total":1` 和 `"error":"upload_expired"`：上传只存在于接收它的那个 Pod 的内存里（见
`creed-resource/creed-resource-veracode/HANDOFF.md` 的 Landmines）。解决办法有三种，下一章实践第一种：
- 入口做会话粘滞（Route 的 cookie）；
- 把上传存到共享存储，例如 Redis、数据库或对象存储；
- 只跑 1 个副本。这是最差的办法，等于放弃了高可用。

### 实验 3：用 readiness 摘除一个 Pod

```bash
POD=$(oc get pod -l app=veracode -o jsonpath='{.items[0].metadata.name}')
# 最直接的摘除办法：改掉它的 label，让 Service（和 ReplicaSet）的 selector 不再选中它。
# 另一种更真实的办法是让 readinessProbe 失败（例如临时把探针路径改成不存在的地址），然后观察 READY 变成 0/1
oc label pod $POD app=veracode-debug --overwrite
oc get endpointslices -l kubernetes.io/service-name=veracode -o wide   # 少了一个 IP
oc get pod -l app=veracode                                            # ReplicaSet 又补了一个新 Pod
oc delete pod $POD
```

## 检查点

- [ ] 12 次请求分散到了 3 个不同的 Pod 名上（分布不必均匀）
- [ ] 能解释实验 1 里为什么 3 次请求都落在同一个 Pod 上
- [ ] 复现了实验 2 的 `upload_expired`，并能说出三种解决思路
- [ ] 能说出 EndpointSlice 里的 `ready`、`serving`、`terminating` 三个条件分别是什么意思

## 常见坑

- **用 Route 测 Service 层的负载均衡**：经过 HAProxy 之后看到的是路由器的算法和粘滞效果，而不是 Service 的。本章的实验一律在集群内部直连 Service。
- **把 label 改错导致 Pod「消失」**：ReplicaSet 只认 selector。修改 label 后，这个 Pod 就成了孤儿，要记得删掉。

## 参考文档

- Kubernetes 文档：*Service*、*EndpointSlices*、*Virtual IPs and Service Proxies*
- OCP 文档：*Networking → OVN-Kubernetes network plugin*
