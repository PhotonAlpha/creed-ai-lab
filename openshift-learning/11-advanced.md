# 11 进阶（按需选做）

每个小节都是独立的，可以按兴趣挑着做。和本仓库关系最大的是 11.3（mTLS）和 11.5（多服务）。

## 11.1 安全基线：收紧 actuator，加 NetworkPolicy

- **问题**：前面章节为了做实验，通过 Route 对外暴露了 `/actuator/env`。它会列出所有环境变量和配置来源。
  veracode 设置了 `show-values: always`，Boot 本身**不做任何打码**；目前只有第 03 章练习 3 的 `SanitizingFunction`
  按键名把 token 和密码遮住了。键名不符合规则的密钥（例如叫 `CREED_JIRA_PAT`）照样会明文泄露，所以不要依赖它。
- **做法**：
  - 设置 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus`；
  - 或者把 management 端口和业务端口分开（`MANAGEMENT_SERVER_PORT=8081`），Route 只指向业务端口。
- **NetworkPolicy**：默认拒绝所有入站流量，只放行三类来源：
  - 路由器（`policy-group.network.openshift.io/ingress: ""` 命名空间标签）；
  - 监控（`openshift-user-workload-monitoring`）；
  - 同一个项目内部。
- 参考：OCP 文档 *Networking → Network security → Network policy*

## 11.2 配额与默认限制

- `ResourceQuota` 限制一个项目的资源总量；`LimitRange` 给没有写 requests/limits 的容器设置默认值。
- 练习：给 `creed-lab-dev` 加上配额，然后把副本数扩到超出配额，观察 ReplicaSet 的事件 `exceeded quota`。
- 参考：OCP 文档 *Building applications → Quotas*

## 11.3 TLS 进阶：service-serving 证书 + reencrypt，mTLS + passthrough

- **service-serving 证书**：给 Service 加注解 `service.beta.openshift.io/serving-cert-secret-name: veracode-tls`，
  OpenShift 会自动签发一张证书（由集群服务 CA 签发，SAN 为 `veracode.<ns>.svc`），写进一个 Secret，并且到期前自动轮换。
  - 应用挂载这张证书启用 HTTPS（Spring Boot SSL bundle 支持 PEM 格式）；
  - Route 改用 `reencrypt`，**路由器默认信任服务 CA**，不需要另外配置 `destinationCACertificate`。
- **本仓库的 mTLS 服务**（例如 creed-resource-catalog，见 `creed-platform` skill 和 `.support/scripts/CA-Generation.sh`）：
  - 把 PKCS12 keystore/truststore 放进 Secret，挂载到 `.support/scripts/pki` 对应的路径，或者用环境变量改写 `creed.rootPath`；
  - Route 用 `passthrough`，让 Pod 自己完成客户端证书校验；
  - 注意 passthrough 默认按 `source` 做负载均衡，并且**没有 cookie 粘滞**。
- **陷阱**：Boot 的 `SslMeterBinder` 会在启动时打开所有已声明的 SSL bundle（见根目录 CLAUDE.md 的 Cross-cutting landmines），
  Secret 漏挂载一个文件就会启动失败。
- 参考：OCP 文档 *Security and compliance → Configuring certificates → Securing service traffic using service serving certificate secrets*

## 11.4 Secret 管理

- 不要把明文 Secret 提交到 Git。可选的方案：
  - **Sealed Secrets**：用集群公钥加密后提交；
  - **External Secrets Operator**：从 Vault 等外部系统同步；
  - **Argo CD Vault Plugin**。
- 与本仓库 `{cipher}` 配置和 `ENC(...)` 写法的对比：它们都是「在仓库里只存密文」，区别在于解密发生在哪里（应用内还是集群里）。

## 11.5 多服务：env-matrix 全栈上 OCP

- PostgreSQL：用 `registry.redhat.io/rhel9/postgresql-16`（需要 pull secret，CRC 已经带了）或者社区镜像，配一个 PVC，作为 StatefulSet 运行。
- `creed-resource-env-matrix` 使用 `dev` profile（HTTP 3001，不需要 PKI），把 `ENV_MATRIX_DB_URL` 指向 `postgresql.<ns>.svc`。
- 前端：`npm run build` 生成 `dist/`，用 `nginxinc/nginx-unprivileged`（监听 8080）提供静态文件，并把 `/api` 反向代理到后端 Service；
  也可以复用 `creed-gateway-proxy` 这个部署形态（见其 HANDOFF）。
- 知识点：StatefulSet、PVC、Service 之间通过 DNS 名发现彼此；同一个 Route 主机名下按路径路由到不同的 Service（`spec.path`）。

## 11.6 打包与分发：Helm / OpenShift Template

- 把 veracode 的 base 改写成 Helm chart，与 Kustomize 对比两者的思路：模板渲染 vs 打补丁叠加。
- Console 的 Developer → Helm 页面可以直接安装 chart。
- 参考：OCP 文档 *Building applications → Working with Helm charts*

## 11.7 Operator 视角

- 理解 OLM 的几个对象：`Subscription`、`InstallPlan`、`ClusterServiceVersion`。前面安装 Pipelines 和 GitOps 时，背后发生的就是这一套流程。
- 参考：OCP 文档 *Operators → Understanding Operators*

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。每个实验对应上面的一个小节。

### 实验 1（11.1）：NetworkPolicy 是「白名单叠加」

```bash
oc run net -n creed-lab-dev --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never --command -- sleep 3600
HOST=$(oc get route veracode -n creed-lab -o jsonpath='{.spec.host}')
probe() { oc exec net -n creed-lab-dev -- curl -s -m 3 -o /dev/null -w '%{http_code}\n' http://veracode.creed-lab.svc:8080/actuator/health; }
probe                                                   # 200：默认不隔离

cat <<'EOF2' | oc apply -n creed-lab -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-ingress }
spec: { podSelector: {}, policyTypes: [Ingress] }
EOF2
probe                                                   # 超时（000）
curl -sk -o /dev/null -w '%{http_code}\n' https://$HOST/actuator/health    # 经 Route 也不通了：路由器同样被挡住

cat <<'EOF2' | oc apply -n creed-lab -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-from-router }
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels: { policy-group.network.openshift.io/ingress: "" }
EOF2
curl -sk -o /dev/null -w '%{http_code}\n' https://$HOST/actuator/health    # 200
probe                                                   # 仍然超时
oc delete networkpolicy default-deny-ingress allow-from-router -n creed-lab; oc delete pod net -n creed-lab-dev
```

**解释**：
- 一个 Pod 只要被任意一条带 `Ingress` 类型的策略选中，就进入「默认拒绝」状态。之后每条策略都只能**再放行**一些来源，
  不存在「拒绝」规则，多条策略之间是并集关系。
- 超时而不是「连接被拒绝」：数据包被直接丢弃，客户端什么也收不到。排查网络策略时，超时是一个很典型的特征。
- 路由器 Pod 也是 NetworkPolicy 的「来源」之一。忘了放行它，就会出现「集群里一切正常，外部全部 503」。

### 实验 2（11.2）：LimitRange 和 ResourceQuota 都在准入阶段生效

```bash
cat <<'EOF2' | oc apply -n creed-lab-dev -f -
apiVersion: v1
kind: LimitRange
metadata: { name: defaults }
spec:
  limits:
    - type: Container
      default:        { memory: 512Mi }
      defaultRequest: { cpu: 50m, memory: 256Mi }
---
apiVersion: v1
kind: ResourceQuota
metadata: { name: small }
spec:
  hard: { requests.memory: 1Gi, pods: "4" }
EOF2
oc run lr-test -n creed-lab-dev --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never --command -- sleep 60
oc get pod lr-test -n creed-lab-dev -o jsonpath='{.spec.containers[0].resources}'; echo   # 没写，但被补上了
oc describe quota small -n creed-lab-dev                                                 # Used 增加了 256Mi
oc scale deploy/veracode -n creed-lab-dev --replicas=6
oc get events -n creed-lab-dev --sort-by=.lastTimestamp | grep -i quota | tail -2         # exceeded quota
```

**解释**：
- `lr-test` 的 `resources` 不是你写的，是 LimitRange 准入插件在创建时补上的。这和 SCC 补 `runAsUser`（第 01 章）、ImageStream 改写镜像名（第 04 章）是同一类机制。
- 配额是在**创建 Pod 时**检查的。超出配额的 Pod 根本创建不出来，所以 `oc get pod` 里看不到它，错误只出现在 ReplicaSet 的事件里。
- 配额里写了 `requests.memory`，那么每个 Pod 都必须声明内存 requests，否则会被拒绝。这也是 LimitRange 通常和 ResourceQuota 一起使用的原因。

### 实验 3（11.3）：service-serving 证书的签发与轮换

```bash
oc annotate svc veracode service.beta.openshift.io/serving-cert-secret-name=veracode-serving-cert
sleep 5; oc get secret veracode-serving-cert -o jsonpath='{.data.tls\.crt}' | base64 -d \
  | openssl x509 -noout -subject -issuer -ext subjectAltName -enddate
oc create configmap service-ca
oc annotate configmap service-ca service.beta.openshift.io/inject-cabundle=true
sleep 5; oc get configmap service-ca -o jsonpath='{.data.service-ca\.crt}' | head -2

# 删掉 Secret，看它是否被重新签发
oc delete secret veracode-serving-cert; sleep 10
oc get secret veracode-serving-cert -o jsonpath='{.metadata.creationTimestamp}'; echo
```

**观察 / 解释**：
- 签发者是 `openshift-service-serving-signer@...`，SAN 是 `veracode.creed-lab.svc` 和 `veracode.creed-lab.svc.cluster.local`。
- 往一个空 ConfigMap 上加 `inject-cabundle` 注解，它就会被自动填入服务 CA 的根证书。集群里的客户端用它来信任这类证书。
- Secret 被删除后会被重新签发。到期前也会自动轮换。
- 拿它和第 03 章练习 1 对比：
  - 那里要自己签证书、自己管理 keystore 密码；
  - 这里证书是 PEM 格式，`spring.ssl.bundle.pem.*` 可以直接读取，**没有密码**，到期也不用人管。
  - 代价是这张证书只被集群内部信任，外部访问仍然要经过 Route。

### 实验 4（11.7）：安装一个 Operator 时，OLM 创建了什么

```bash
oc get subscription,installplan,csv -n openshift-operators
oc get subscription -n openshift-operators -o custom-columns=NAME:.metadata.name,CHANNEL:.spec.channel,APPROVAL:.spec.installPlanApproval,CSV:.status.installedCSV
oc get csv -n openshift-operators -o jsonpath='{.items[0].spec.install.spec.deployments[*].name}'; echo
```

**解释**：
- **Subscription**：「我想要哪个 channel 的哪个 Operator」。
- **InstallPlan**：「为了满足这个订阅，要创建哪些资源」。`installPlanApproval: Manual` 时，需要人工批准它，升级才会进行。
- **CSV**（ClusterServiceVersion）：「某个版本的 Operator 长什么样」，包括它的 Deployment、权限和 CRD。
- Operator 升级就是：OLM 发现 channel 里有了新 CSV → 生成新的 InstallPlan → 批准后替换。
  第 07 章「Operator 升级后 Task 参数名变了」的根源就在这里。生产环境通常把 approval 设为 Manual，按自己的节奏升级。

### 检查点（机制）

- [ ] 复现了「默认拒绝之后连路由器也进不来」，并用一条策略放行了路由器
- [ ] 看到 LimitRange 给没写 resources 的 Pod 补上了默认值
- [ ] 在 ReplicaSet 的事件里找到了 `exceeded quota`
- [ ] 删掉 serving-cert Secret 后它被重新签发
- [ ] 能说出 Subscription、InstallPlan、CSV 三者的关系

## 检查点

- [ ] 11.1：外部访问 `/actuator/env` 返回 404，`/actuator/health` 正常
- [ ] 11.3：一个 `reencrypt` 路由能工作，并且 Pod 内的证书来自 service-serving Secret
- [ ] 11.5：env-matrix 前端、后端和 PostgreSQL 三个组件都在 OCP 上运行，页面能正常查询数据
