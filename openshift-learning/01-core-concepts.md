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

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。先用 `oc new-app` 重新部署一次 hello，做完再删。

### 实验 1：控制循环——删掉 Pod，谁把它补回来

```bash
oc get pod -l deployment=hello -w &          # 后台持续观察
oc delete pod -l deployment=hello
# 几秒后看到：旧 Pod Terminating，新 Pod 从 Pending → ContainerCreating → Running
kill %1
```

**解释**：没有任何命令「重启」了 Pod。ReplicaSet 控制器一直在比较「期望 1 个」和「实际 0 个」，发现差异就创建一个新 Pod。
**Kubernetes 的所有对象都是这样工作的**：你写期望状态，控制器不断把实际状态往期望状态推。第 08 章 Argo CD 的 selfHeal 是同一个思路，只是把期望状态放到了 Git 里。

### 实验 2：ownerReferences——对象之间的父子链

```bash
RS=$(oc get rs -l deployment=hello -o name | head -1)
oc get $RS -o jsonpath='{.metadata.ownerReferences[0].kind}/{.metadata.ownerReferences[0].name}'; echo   # Deployment/hello
oc get pod -l deployment=hello -o jsonpath='{.items[0].metadata.ownerReferences[0].kind}'; echo       # ReplicaSet
oc get pod -l deployment=hello --show-labels      # 注意 pod-template-hash：它区分同一个 Deployment 的不同 ReplicaSet

# 孤儿实验：只删 ReplicaSet，不删它的 Pod
oc delete $RS --cascade=orphan
oc get rs,pod -l deployment=hello
```

**观察**：
- 删掉 RS 之后，Deployment 马上又创建了一个 RS。新 RS 的 `pod-template-hash` 和旧的一样，于是它直接「认领」了还活着的 Pod，Pod 并没有重建。
- **认领靠的是 label selector，不是名字**。这就是第 05 章实验 3 改 label 会让 Pod 变成孤儿的原因。
- 默认的级联删除由垃圾回收器按 ownerReferences 完成，`--cascade=orphan` 只是让它跳过这一步。

### 实验 3：SCC 在 Pod 创建时改写了什么

**要弄清的问题**：镜像没写 UID，YAML 也没写 securityContext，随机 UID 是谁塞进去的？

```bash
POD=$(oc get pod -l deployment=hello -o name | head -1)
oc get $POD -o jsonpath='{.metadata.annotations.openshift\.io/scc}'; echo          # restricted-v2
oc get $POD -o jsonpath='{.spec.containers[0].securityContext}' | python3 -m json.tool
oc get ns creed-lab -o yaml | grep 'sa.scc'                                          # 这个项目的 UID 区间
```

**观察**：
- Pod 的 `securityContext` 里多了 `runAsUser`、`capabilities.drop: [ALL]`、`allowPrivilegeEscalation: false`、`seccompProfile` 等字段，
  而你的 YAML 里一个都没写。
- `runAsUser` 是项目注解 `openshift.io/sa.scc.uid-range` 的起始值。
- 这是 **admission（准入）阶段的改写**：API Server 在把对象存进 etcd 之前，由 SCC 准入插件按选中的 SCC 补全并校验。

**反向验证**：请求一个区间之外的 UID，会被准入拒绝。

```bash
oc run uid-test --image=registry.access.redhat.com/ubi9/ubi-minimal --restart=Never \
  --overrides='{"spec":{"securityContext":{"runAsUser":1000}}}' -- id
oc get events --sort-by=.lastTimestamp | tail -3     # 预期看到 unable to validate against any security context constraint
oc delete pod uid-test --ignore-not-found
```

### 实验 4：RBAC——为什么 developer 能做这些、不能做那些

```bash
oc auth can-i create deployments -n creed-lab         # yes
oc auth can-i get nodes                               # no
oc auth can-i --list -n creed-lab | head -20
oc get rolebinding -n creed-lab -o wide               # new-project 给创建者绑定的 admin 角色就在这里
```

**解释**：权限 = Role/ClusterRole（能做什么）+ RoleBinding（谁、在哪个命名空间）。`admin` 是一个 ClusterRole，
`oc new-project` 用 RoleBinding 把它限定在当前项目里。这就是「项目管理员」和「集群管理员」的区别。

### 实验 5：Pod 里的 DNS 是怎么配的

```bash
oc rsh deploy/hello cat /etc/resolv.conf
oc rsh deploy/hello getent hosts hello                       # 短名
oc rsh deploy/hello getent hosts hello.creed-lab.svc         # 带命名空间
oc rsh deploy/hello getent hosts kubernetes.default.svc      # 跨命名空间必须带命名空间
```

**观察**：
- `search` 一行有 `creed-lab.svc.cluster.local svc.cluster.local cluster.local`，`options ndots:5`。
- 所以在同一个项目里写 `hello` 就够了；跨项目要写 `hello.<ns>.svc`。
- `ndots:5` 意味着名字里点少于 5 个时，会先逐个尝试 search 后缀。所以访问外部域名时，会先产生几次注定失败的集群内查询。

### 实验 6：Ingress 会被翻译成 Route

```bash
oc create ingress hello --rule="hello-ing.apps-crc.testing/*=hello:8080"
oc get route                                          # 多了一个名字带随机后缀的 Route
oc get route -o jsonpath='{.items[?(@.spec.host=="hello-ing.apps-crc.testing")].metadata.ownerReferences[0].kind}'; echo   # Ingress
oc delete ingress hello; oc get route                 # Route 跟着被回收
```

**解释**：OpenShift 的路由器只认 Route。有一个控制器负责把 Ingress 转换成 Route，并把 Ingress 设为 Route 的 owner。
所以用 Ingress 写的清单在 OpenShift 上也能用，只是能用的特性少于 Route 注解。

### 检查点（机制）

- [ ] 能用「控制循环」解释实验 1，并说出它和 Argo CD selfHeal 的共同点
- [ ] 做完孤儿实验，能解释为什么 Pod 没有重建
- [ ] 找到了 SCC 改写进 Pod 的 `runAsUser`，并能说出它取自哪个项目注解
- [ ] 能说出 `hello`、`hello.creed-lab.svc` 两种写法分别在什么时候可用

## 常见坑

- **Docker Hub 镜像经常以 root 运行或监听 80 端口**，在 `restricted-v2` 下会 CrashLoop。
  优先使用 UBI 或 `*-unprivileged` 镜像，或者自己构建镜像（第 02 章）。
- 用 `developer` 账号执行 `oc get nodes` 会被拒绝，这是 RBAC 在起作用，不是集群坏了。

## 参考文档

- OCP 文档：*Architecture → OpenShift Container Platform architecture*
- OCP 文档：*Authentication and authorization → Managing security context constraints*
- OCP 文档：*Building applications → Projects*，以及 *Creating applications → Creating applications using the CLI*（`oc new-app`）
- Kubernetes 文档：*Concepts → Workloads / Services, Load Balancing, and Networking*
