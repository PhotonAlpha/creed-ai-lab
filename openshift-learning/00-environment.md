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

## 常见坑

- **`oc: command not found`**：没有执行 `eval $(crc oc-env)`。也可以单独安装与集群版本匹配的 `oc`。
- **`crc start` 卡在等待集群稳定的阶段**：通常是内存给得太小，或者证书正在续签，先耐心等。
  超时后执行 `crc stop && crc start`；还不行就用 `crc delete` 重建，重建很快，因为 bundle 已经缓存好了。
- **公司 VPN 或代理**：`*.crc.testing` 的 DNS 会被 VPN 接管，可以试 `crc config set network-mode user`，或者断开 VPN。
- **pull secret 是个人凭据**：不要提交到仓库，也不要贴进文档。

## 参考文档

- OpenShift Local 入门与配置：https://crc.dev/ ，看 *Getting Started Guide*，以及 *Configuring the instance* 和 *Networking* 两节
- OCP 文档里的 *CLI tools → Getting started with the OpenShift CLI*
