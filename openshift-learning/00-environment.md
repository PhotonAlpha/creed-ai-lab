# 00 环境准备：OpenShift Local（CRC）

## 目标

- CRC 虚拟机的资源能撑住后面的 Pipelines 和 GitOps 两个 Operator（以及可选的监控）。
- 能用 `developer` 和 `kubeadmin` 两种身份登录 Web Console 和 `oc`。
- 知道 CRC 和真实 OCP 集群有哪些不同，以免把 CRC 的限制当成 OpenShift 的行为。

## 知识点

- **CRC 是单节点 OpenShift**：同一个节点既是 control plane 也是 worker。没有多节点调度，`podAntiAffinity` 的效果看不出来。
  Pod 之间的负载均衡照样成立，因为负载均衡看的是 Pod，不是节点。
- **本机现状**：CRC 2.64.0、OpenShift 4.22.14，bundle 是 `crc_vfkit_4.22.14_arm64`。
  **arm64 集群只能跑 arm64（或多架构）镜像**；在别处构建的 amd64 镜像拉下来会报 `exec format error`。
- **两个默认用户**：
  - `developer`：普通用户，相当于业务开发者的权限；
  - `kubeadmin`：集群管理员，安装 Operator 和修改集群配置时用。
- **固定域名**：
  - API 地址：`https://api.crc.testing:6443`；
  - 应用通配域名：`*.apps-crc.testing`，Route 的默认主机名就在这个域名下；
  - 这两个域名由 `crc setup` 配置的本机 DNS 解析。
- **资源默认值偏小**：默认 4 核、约 10.5 GiB 内存、31 GiB 磁盘，**集群监控默认关闭**。
  监控关闭时，`oc adm top`、HPA 的 CPU 指标、Console 里的监控图都没有数据。
- **证书轮换**：集群证书有有效期，虚拟机长时间不启动后，下一次 `crc start` 会先续签证书，耗时会明显变长，这是正常的。

### 资源预算（宿主机 24 GiB 内存、10 核）

| 组件 | 约需内存 |
|---|---|
| CRC 基础集群 | 9–10 GiB |
| OpenShift Pipelines Operator 和流水线 Pod | +1–2 GiB |
| OpenShift GitOps（Argo CD） | +1 GiB |
| 集群监控（可选，第 03、10 章用） | +3–4 GiB |

建议给虚拟机 **16 GiB 内存、6 核、80 GiB 磁盘**；宿主机还要跑 IDE 时，监控按需再开。

## 步骤

```bash
# 1. 调资源。必须在 start 之前设置；对已经创建的虚拟机改内存或 CPU，要先 crc stop
crc config set memory 16384
crc config set cpus 6
crc config set disk-size 80
# 可选：开启集群监控（需要 ≥14 GiB 内存）。第 03 章的 HPA 和第 10 章的监控会用到
crc config set enable-cluster-monitoring true
crc config view

# 2. 首次启动需要 pull secret：到 https://console.redhat.com/openshift/create/local 下载，
#    保存到仓库外，例如 ~/.crc/pull-secret.txt。不要放进仓库
crc setup
crc start -p ~/.crc/pull-secret.txt      # 之后再启动只需要 crc start

# 3. 把 oc 放进 PATH（本机目前没有独立安装的 oc，用 CRC 自带的那个）
eval $(crc oc-env)                       # 可以写进 ~/.zshrc
oc version

# 4. 登录
crc console --credentials                # 打印 developer 和 kubeadmin 两个账号的登录命令
oc login -u developer https://api.crc.testing:6443
crc console                              # 在浏览器打开 Web Console

# 5. 建一个学习用的项目
oc new-project creed-lab
```

日常启停：

```bash
crc stop       # 关机，数据保留
crc start
crc status
crc delete     # 删除虚拟机，所有数据清空（cache 里的 bundle 保留）
```

## 检查点

- [ ] `crc status` 显示 OpenShift Running
- [ ] 能以 `developer` 身份执行 `oc whoami`，也能以 `kubeadmin` 身份执行 `oc get nodes`（只有 1 个节点）
- [ ] Web Console 能打开，并能在 Administrator 和 Developer 两个视图之间切换
- [ ] `oc get clusterversion` 显示版本 4.22.14
- [ ] （开启了监控时）`oc adm top nodes` 有数据

## 深入练习：看清机制

前面的步骤只回答「能不能用」，这一节回答「它是怎么工作的」。每个实验的格式都是：**要弄清的问题 → 命令 → 观察到什么、为什么**。
实验结果没有在本机集群上逐条跑过，以实际输出为准。如果和描述不一致，正好说明版本行为变了，把差异记到检查点里。

### 实验 1：`oc login` 之后，本机到底存了什么

**要弄清的问题**：`oc` 怎么知道连哪个集群、用什么身份？

```bash
oc config get-contexts                # 每次 login 都会新增一个 context：<project>/<server>/<user>
oc config view --minify               # 当前 context 用到的 cluster、user；token 显示为 REDACTED
oc whoami -t | cut -c1-12; echo ...   # developer 的 token 前缀（sha256~...），它就是每个请求的 Bearer 头
oc get pods -v=8 2>&1 | grep -E 'GET https|Authorization' | head -3
```

**观察**：
- `-v=8` 能看到真实的 HTTP 请求：`GET https://api.crc.testing:6443/api/v1/namespaces/creed-lab/pods`，
  请求头里带着 `Authorization: Bearer <masked>`。
- **`oc` 只是一个 REST 客户端**，所有命令最后都会变成对 API Server 的 HTTP 调用。
  后面章节遇到「命令做了什么」的疑问，都可以用 `-v=8` 自己查。

### 实验 2：developer 和 kubeadmin 这两个身份是从哪来的

**要弄清的问题**：OpenShift 的用户不是 Kubernetes 对象吗？密码存在哪？

```bash
oc login -u kubeadmin https://api.crc.testing:6443
oc get oauth cluster -o jsonpath='{.spec.identityProviders[*].type}'; echo   # CRC 用 HTPasswd
oc get users; oc get identities                                              # 登录过的用户才会出现
oc get secret -n openshift-config | grep -i htpass                           # 密码哈希存在这个 Secret 里
```

**观察**：
- 密码不在 User 对象里，而在 OAuth 配置引用的 htpasswd Secret 里。
- 用户第一次登录时，OAuth Server 才创建 `User` 和 `Identity` 两个对象，并把它们关联起来。
- `kubeadmin` 是一个特殊的临时管理员（`kube-system/kubeadmin` Secret），生产集群配好正式的身份源后应该删掉它。

### 实验 3：集群是由哪些「组件」组成的，它们健康吗

```bash
oc get clusteroperators                 # 每一行是一个平台组件：AVAILABLE / PROGRESSING / DEGRADED
oc get clusterversion -o jsonpath='{.items[0].status.desired.version}'; echo
oc api-resources --api-group=route.openshift.io       # OpenShift 加进来的 API 组之一
oc api-resources | wc -l                              # 和原生 Kubernetes 对比，多出很多 *.openshift.io
```

**观察**：
- OpenShift 的每个平台能力（ingress、DNS、镜像仓库、OAuth、监控……）都由一个 Operator 管理，`clusteroperators` 就是它们的状态汇总。
- CRC 为了省资源关掉了一部分组件（例如监控）。打开 `enable-cluster-monitoring` 后再看一次，`monitoring` 的状态会变化。

### 实验 4：进到唯一的节点里看一看（kubeadmin）

**要弄清的问题**：Pod 最终是被谁运行起来的？

```bash
NODE=$(oc get nodes -o name)
oc debug $NODE -- chroot /host sh -c '
  crictl ps | head -5;                       # CRI-O 管理的容器，和 oc get pod -A 一一对应
  ls /etc/kubernetes/manifests/;             # 静态 Pod：etcd、kube-apiserver 等控制面组件
  systemctl is-active kubelet crio'
```

**观察**：
- `oc debug node/...` 在节点上起一个特权 Pod，`chroot /host` 后就进入了 RHCOS 主机的文件系统。
- 链路是 **kubelet → CRI-O → runc**。控制面组件本身也是 Pod，而且是由 kubelet 直接读本地文件启动的「静态 Pod」。
- 后面第 10 章排查「容器起不来」时，会再用到这个入口。

### 实验 5：`*.apps-crc.testing` 是怎么解析到本机虚拟机的

```bash
crc ip                                       # 虚拟机地址（user 网络模式下可能是 127.0.0.1）
ls /etc/resolver/ 2>/dev/null && cat /etc/resolver/testing 2>/dev/null
grep -i crc.testing /etc/hosts
dscacheutil -q host -a name hello-creed-lab.apps-crc.testing
```

**观察**：
- macOS 上 `crc setup` 用两种方式之一让 `*.crc.testing` 可解析：`/etc/resolver/testing` 指向虚拟机里的 DNS，或者往 `/etc/hosts` 写条目。
  看看你的机器用的是哪一种。
- `/etc/hosts` 不支持通配符。如果是这种方式，新 Route 的主机名能不能解析，就取决于 CRC 有没有替你加条目。这也是 VPN 一开、DNS 就失效的根源。

### 检查点（机制）

- [ ] 用 `-v=8` 找到了 `oc get pods` 对应的 REST 路径
- [ ] 能说出 developer 的密码存在哪个 Secret 里，以及 User/Identity 是什么时候创建的
- [ ] 在节点上用 `crictl ps` 找到了自己项目里的一个容器
- [ ] 能说出本机解析 `*.apps-crc.testing` 用的是 resolver 还是 hosts

## 常见坑

- **`oc: command not found`**：没有执行 `eval $(crc oc-env)`。也可以单独安装与集群版本匹配的 `oc`。
- **`crc start` 卡在等待集群稳定的阶段**：通常是内存给得太小，或者证书正在续签，先耐心等。
  超时后执行 `crc stop && crc start`；还不行就用 `crc delete` 重建，重建很快，因为 bundle 已经缓存好了。
- **公司 VPN 或代理**：`*.crc.testing` 的 DNS 会被 VPN 接管，可以试 `crc config set network-mode user`，或者断开 VPN。
- **pull secret 是个人凭据**：不要提交到仓库，也不要贴进文档。

## 参考文档

- OpenShift Local 入门与配置：https://crc.dev/ ，看 *Getting Started Guide*，以及 *Configuring the instance* 和 *Networking* 两节
- OCP 文档里的 *CLI tools → Getting started with the OpenShift CLI*
