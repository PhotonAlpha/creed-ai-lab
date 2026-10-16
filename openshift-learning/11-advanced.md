# 11 进阶（按需选做）

每个小节都是独立的，可以按兴趣挑着做。和本仓库关系最大的是 11.3（mTLS）和 11.5（多服务）。

## 11.1 安全基线：收紧 actuator，加 NetworkPolicy

- **问题**：前面章节为了做实验，通过 Route 对外暴露了 `/actuator/env`。它会泄露所有环境变量，包括 `CREED_JIRA_TOKEN`
  （Boot 会把名字像密钥的值打码显示，但不要依赖这一点）。
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

## 检查点

- [ ] 11.1：外部访问 `/actuator/env` 返回 404，`/actuator/health` 正常
- [ ] 11.3：一个 `reencrypt` 路由能工作，并且 Pod 内的证书来自 service-serving Secret
- [ ] 11.5：env-matrix 前端、后端和 PostgreSQL 三个组件都在 OCP 上运行，页面能正常查询数据
