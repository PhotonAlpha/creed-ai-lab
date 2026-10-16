# 01 核心概念：Kubernetes 对象 + OpenShift 增量

## 目标

- 不看文档也能画出这条链路：一个请求从浏览器进来，经过 Route、Service 到达 Pod。
- 能说出 OpenShift 在 Kubernetes 之上**多了什么**，又**限制了什么**。

## 知识点

### Kubernetes 本身的对象

| 对象 | 一句话 |
|---|---|
| Pod | 一个或多个共享网络和存储的容器，是调度的最小单位，**随时可能被替换** |
| Deployment → ReplicaSet → Pod | 声明「我要 N 个这样的 Pod」；改模板会产生一个新的 ReplicaSet，这就是滚动发布 |
| Service | 给一组 Pod 一个稳定的虚拟 IP 和 DNS 名（`<svc>.<ns>.svc`），按 label selector 选中 Pod |
| EndpointSlice | Service 当前**就绪**的 Pod IP 列表，负载均衡真正的目标就是它 |
| ConfigMap / Secret | 配置和敏感配置，以环境变量或文件的形式注入 Pod |
| PersistentVolumeClaim | 持久存储（CRC 自带一个 hostpath 类型的存储供给器） |
| Namespace | 隔离边界，OpenShift 里叫 **Project** |

### OpenShift 增加的东西

| 增量 | 说明 |
|---|---|
| **Project** | Namespace 加上默认 RBAC 和配额；`oc new-project` 会顺带给创建者 admin 权限 |
| **Route** | OpenShift 原生的「外部入口」，比 Ingress 更早出现。由 **Ingress Controller（HAProxy）** 实现；创建的 Ingress 也会被转换成 Route |
| **BuildConfig / Build** | 在集群里构建镜像（Docker、S2I、Binary 等方式），见第 04 章 |
| **ImageStream** | 给镜像起一个集群内的别名，并且能在镜像更新时自动触发部署 |
| **内置镜像仓库** | `image-registry.openshift-image-registry.svc:5000/<project>/<name>` |
| **SCC（Security Context Constraints）** | 比 Kubernetes 的 Pod Security 更严格的默认策略，见下文 |
| **OperatorHub / OLM** | 安装和升级 Operator（Pipelines、GitOps 都是 Operator） |
| **Web Console 的两个视图** | Developer 视图（拓扑、构建、流水线）与 Administrator 视图（集群和节点管理） |

### SCC：最常见的「本地能跑、上 OCP 就挂」原因

默认的 `restricted-v2` SCC 有以下限制：
- **以一个随机 UID 运行容器**（取自项目的 UID 区间），**GID 固定为 0**，忽略镜像 `USER` 指令里写的具体 UID；
- 不能以 root 运行、不能使用特权、不能挂载 hostPath；
- 不能监听 1024 以下的端口。

结论：镜像里需要写的目录，必须**属于 root 组（GID 0）并且组可写**（`chgrp -R 0 dir && chmod -R g=u dir`）；
应用只监听 8080 这类非特权端口。第 02 章会实际遇到这个问题。

### `oc` 和 `kubectl`

`oc` 包含 `kubectl` 的全部能力，另外加了 `new-app`、`new-build`、`start-build`、`expose`、`rsh`、`debug`、
`set route-backends`、`rollout`、`adm` 等命令。这个仓库的本机环境也装了 `kubectl`，两者可以混用。

## 步骤（热身：不写任何 YAML，部署一个现成镜像）

```bash
oc project creed-lab

# 一个官方的、能以随机 UID 运行的示例镜像（多架构，arm64 可用）
oc new-app --name hello --image=registry.access.redhat.com/ubi9/httpd-24
oc get all -l app=hello            # 看 new-app 替你创建了哪些对象
oc expose service/hello            # 创建 Route
oc get route hello                 # 主机名形如 hello-creed-lab.apps-crc.testing
curl -s http://$(oc get route hello -o jsonpath='{.spec.host}') | head

# 把对象导出成 YAML 看一遍，这是最好的学习材料
oc get deploy hello -o yaml | less
oc explain deployment.spec.template.spec.containers.readinessProbe

oc delete all -l app=hello
```

## 检查点

- [ ] 能说出 `oc new-app` 创建了哪几类对象，以及它们之间靠哪些 label 和 selector 关联
- [ ] 能说清 Route → Service → EndpointSlice → Pod 这条链上，每一跳分别由谁实现：HAProxy、OVN-Kubernetes、kubelet
- [ ] 在 Pod 里执行 `oc rsh deploy/hello id`，能看到一个随机 UID、`gid=0(root)`
- [ ] 在 Web Console 的 Developer → Topology 里找到同一个应用

## 常见坑

- **Docker Hub 镜像经常以 root 运行或监听 80 端口**，在 `restricted-v2` 下会 CrashLoop。
  优先使用 UBI 或 `*-unprivileged` 镜像，或者自己构建镜像（第 02 章）。
- 用 `developer` 账号执行 `oc get nodes` 会被拒绝，这是 RBAC 在起作用，不是集群坏了。

## 参考文档

- OCP 文档：*Architecture → OpenShift Container Platform architecture*
- OCP 文档：*Authentication and authorization → Managing security context constraints*
- OCP 文档：*Building applications → Projects*，以及 *Creating applications → Creating applications using the CLI*（`oc new-app`）
- Kubernetes 文档：*Concepts → Workloads / Services, Load Balancing, and Networking*
