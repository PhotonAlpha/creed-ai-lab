# 08 CD：OpenShift GitOps（Argo CD）

## 目标

- 把前面手工 `oc apply` 的 YAML 整理成 **Kustomize 的 base 加 dev/prod 两个 overlay**，放进 `openshift-learning/manifests/`。
- 安装 OpenShift GitOps，用 Argo CD Application 把 Git 中的状态同步到 `creed-lab-dev` 和 `creed-lab-prod` 两个项目。
- 打通 CI 到 CD：流水线产出新镜像后**更新 Git 中的镜像标签**，Argo CD 再把它同步到集群。

## 知识点

- **GitOps 的四条原则**：声明式；Git 是唯一的真相来源；变更通过 Git（PR）完成；由控制器持续对比并纠正偏差。
- **Argo CD Application**：`source`（仓库、路径、版本）和 `destination`（集群、命名空间）。同步策略：
  - `automated`：发现差异就自动同步；
  - `prune`：Git 里删除的资源，集群里也删除；
  - `selfHeal`：有人在集群里手动修改了资源，会被改回 Git 中的状态。
- **OpenShift GitOps 的默认实例**位于 `openshift-gitops` 命名空间。它要管理别的命名空间时，
  必须给那个命名空间打上标签 `argocd.argoproj.io/managed-by=openshift-gitops`，否则会因为没有权限而同步失败。
- **镜像标签怎么流转**（二选一，推荐第一种）：
  1. CI 在最后一步执行 `kustomize edit set image`，提交并推送到 Git，Argo CD 检测到变化后同步。整个过程可审计、可回滚，是标准的 GitOps 做法。
  2. Deployment 使用 ImageStream 触发器（第 04 章）。这样更简单，但「集群里实际跑的版本」不在 Git 中记录，违背了 GitOps 的原则。
- **CI 与 CD 分离**：流水线只负责构建和更新清单，不直接修改集群。集群的变更只有 Argo CD 一个入口。

## 步骤

### 1. 整理清单

```
openshift-learning/manifests/veracode/
├── Dockerfile
├── base/
│   ├── kustomization.yaml       # resources: deployment.yaml, service.yaml, route.yaml, configmap.yaml
│   ├── deployment.yaml          # 第 02、03 章的成果：探针、资源、envFrom、ConfigMap 卷
│   ├── configmap.yaml           # 第 03 章练习 2：veracode-app-config（挂成 /etc/veracode/config）
│   ├── service.yaml
│   └── route.yaml
└── overlays/
    ├── dev/kustomization.yaml   # namespace: creed-lab-dev，replicas 1，CREED_VERACODE_MOCK=true
    └── prod/kustomization.yaml  # namespace: creed-lab-prod，replicas 3，更大的 resources，Route 注解 timeout=120s
```

```yaml
# overlays/dev/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: creed-lab-dev
resources: [../../base]
images:
  - name: image-registry.openshift-image-registry.svc:5000/creed-lab/veracode
    newTag: latest               # 由 CI 改写成 commit SHA
configMapGenerator:
  - name: veracode-config
    literals: [CREED_VERACODE_MOCK=true, CREED_JIRA_PROJECT=CREED]
```

```bash
oc kustomize openshift-learning/manifests/veracode/overlays/dev | less    # 本地渲染，先看一遍结果
git add openshift-learning/manifests && git commit -m "ocp: veracode kustomize base + overlays" && git push
```

> `configMapGenerator` 生成的 ConfigMap 名字带内容哈希，内容一变名字就变，Deployment 随之滚动。
> 这正好解决了第 03 章「修改 ConfigMap 不会重启 Pod」的问题。

### 2. 安装 OpenShift GitOps 并准备命名空间（kubeadmin）

OperatorHub 搜索 *Red Hat OpenShift GitOps* 并安装。

```bash
oc new-project creed-lab-dev; oc new-project creed-lab-prod
oc label ns creed-lab-dev creed-lab-prod argocd.argoproj.io/managed-by=openshift-gitops
# 两个环境都从 creed-lab 项目的 ImageStream 拉取镜像
oc policy add-role-to-group system:image-puller system:serviceaccounts:creed-lab-dev  -n creed-lab
oc policy add-role-to-group system:image-puller system:serviceaccounts:creed-lab-prod -n creed-lab
# Argo CD 的 Web 界面
oc get route openshift-gitops-server -n openshift-gitops
```

私有仓库需要在 Argo CD 里添加仓库凭据：Web 界面 → Settings → Repositories，或者创建一个带 `argocd.argoproj.io/secret-type=repository` 标签的 Secret。

### 3. Application（保存为 `openshift-learning/manifests/argocd/veracode-dev.yaml`）

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: veracode-dev
  namespace: openshift-gitops
spec:
  project: default
  source:
    repoURL: https://github.com/PhotonAlpha/creed-ai-lab.git
    targetRevision: master-spring-boot-3
    path: openshift-learning/manifests/veracode/overlays/dev
  destination:
    server: https://kubernetes.default.svc
    namespace: creed-lab-dev
  syncPolicy:
    automated: { prune: true, selfHeal: true }
```

prod 环境照此写一份，但**不开启 `automated`**，改为手动点 Sync。这相当于一道人工审批门。

### 4. CI 到 CD

在第 07 章流水线的末尾加一个 Task，用 git 写权限的凭据执行：

```bash
cd openshift-learning/manifests/veracode/overlays/dev
kustomize edit set image image-registry.openshift-image-registry.svc:5000/creed-lab/veracode=image-registry.openshift-image-registry.svc:5000/creed-lab/veracode:$(params.commit)
git commit -am "ci: veracode dev -> $(params.commit)" && git push
```

## 检查点

- [ ] `oc kustomize overlays/dev` 和 `overlays/prod` 渲染出的副本数、命名空间、配置都不一样
- [ ] Argo CD 中 `veracode-dev` 显示 Synced / Healthy
- [ ] 手动执行 `oc scale deploy/veracode --replicas=5 -n creed-lab-dev`，几秒后被 selfHeal 改回 Git 中的值
- [ ] 在 Git 里删掉 Route 并推送，集群里的 Route 也被删除（prune 生效）
- [ ] 完整跑通一次：push 代码 → 流水线 → Git 中的标签更新 → dev 环境滚动发布到新的 SHA

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。

### 实验 1：Argo CD 怎么认出「这是我管的资源」

```bash
oc get deploy veracode -n creed-lab-dev -o yaml | grep -iE 'argocd|app.kubernetes.io/instance' 
oc get cm argocd-cm -n openshift-gitops -o jsonpath='{.data.application\.resourceTrackingMethod}'; echo
oc get argocd openshift-gitops -n openshift-gitops -o jsonpath='{.spec.resourceTrackingMethod}'; echo
```

**观察 / 解释**：
- 同步出来的每个资源上都有一个**跟踪标记**：一个 label（`app.kubernetes.io/instance`），或者一个 annotation（`argocd.argoproj.io/tracking-id`），
  取决于实例配置的 tracking method。
- prune 只会删除「带着本 Application 标记、但 Git 里已经没有」的资源，手工创建的资源不会被误删。
- 用 label 跟踪时，如果你自己的 Helm/Kustomize 也写了 `app.kubernetes.io/instance`，就会产生冲突。这是新版本默认改用 annotation 的原因之一。

### 实验 2：多久能发现 Git 的变化

```bash
oc get cm argocd-cm -n openshift-gitops -o jsonpath='{.data.timeout\.reconciliation}'; echo     # 空 = 默认 180s
# 改一下 overlays/dev 的副本数并 push，然后计时
date; git commit -am "test: replicas" && git push
oc get deploy veracode -n creed-lab-dev -w
```

**解释**：
- Argo CD 默认**每 3 分钟轮询一次** Git（并带有随机抖动），所以 push 后要等几十秒到三分钟左右。
- 生产环境给 Git 仓库配置 webhook 指向 Argo CD，可以做到秒级。CRC 收不到 GitHub 的 webhook（第 07 章同样的原因），所以这里只能等轮询。
- 对比 selfHeal：集群里的手工修改由 Kubernetes 的 watch 实时发现，**几秒内就会被改回去**。两个方向的延迟差别很大。

### 实验 3：selfHeal 和 HPA 打架

```bash
oc autoscale deploy/veracode --min=2 --max=4 --cpu-percent=70 -n creed-lab-dev
oc get deploy veracode -n creed-lab-dev -w      # 副本数在 HPA 的值和 Git 的值之间来回跳
```

**解释与修复**：
- HPA 改 `spec.replicas`，Argo CD 发现与 Git 不一致就改回去，HPA 再改……两个控制器在争夺同一个字段。
- 修复：在 Application 里声明让 Argo CD 忽略这个字段，并且 overlay 里**不要再写死 replicas**：

```yaml
spec:
  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers: [/spec/replicas]
  syncPolicy:
    syncOptions: [RespectIgnoreDifferences=true]
```

**原则**：**一个字段只能有一个主人**。同样的冲突也会出现在 Service mesh 注入的 sidecar、Operator 写入的默认值上。

### 实验 4：configMapGenerator 的哈希和滚动发布

```bash
cd openshift-learning/manifests/veracode/overlays/dev
oc kustomize . | grep -n 'veracode-config'           # ConfigMap 自己的名字，以及 Deployment 里引用它的地方
# 改一个 literal 的值，再渲染一次，对比哈希后缀
oc kustomize . | grep -o 'veracode-config-[a-z0-9]*' | sort -u
```

**观察**：
- ConfigMap 的名字变成了 `veracode-config-<哈希>`，Deployment 里 `envFrom` 引用的名字被**同步改写**。
- 内容一变，哈希就变 → Deployment 模板变了 → 滚动发布。这是对第 03 章「改 ConfigMap 不会重启 Pod」最干净的解决方案。
- 第 03 章练习 2 的 `veracode-app-config`（文件形式）也可以改成 `configMapGenerator` 的 `files:`，同样能获得这个效果。
- 旧的 ConfigMap 会留在集群里，直到 prune 清理掉它。这在回滚时反而有用：旧的 ReplicaSet 引用的还是旧名字。

### 实验 5：同步顺序——sync wave

```yaml
# 给 ConfigMap 加注解，让它先于 Deployment 创建
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
```

**解释**：
- 默认情况下，Argo CD 在同一轮同步里按资源类型排序（Namespace → ConfigMap/Secret → Deployment ……）。
- 需要更明确的顺序时用 sync wave，数字小的先执行，每一波都等前一波健康之后才继续。
- 数据库迁移之类「必须先跑完」的事情，用 `PreSync` hook。

### 实验 6：健康状态是怎么算的

```bash
oc set probe deploy/veracode --readiness --get-url=http://:18097/nope -n creed-lab-dev
# Argo CD 界面：veracode-dev 从 Healthy 变成 Progressing，超过 progressDeadlineSeconds（默认 600s）后变成 Degraded
oc get deploy veracode -n creed-lab-dev -o jsonpath='{.status.conditions}' | python3 -m json.tool
```

**解释**：
- 这次手工修改会被 selfHeal 改回去。如果想多观察一会儿，先在 Application 上暂停自动同步。
- Argo CD 对 Deployment 的健康判断依据就是 Deployment 自己的 `status.conditions`（`Progressing`、`Available`），
  它没有发明新标准。这和 `oc rollout status` 用的是同一份数据。

### 检查点（机制）

- [ ] 找到了资源上的 Argo CD 跟踪标记，并说出 prune 依据的是它
- [ ] 测出 push 到同步的实际延迟，并和 selfHeal 的延迟对比
- [ ] 复现并修好了 HPA 与 selfHeal 的冲突
- [ ] 看到 `configMapGenerator` 的哈希名被改写进 Deployment

## 常见坑

- **`namespace creed-lab-dev is not managed`**，或同步时权限不足：命名空间漏打了 `managed-by` 标签。
- **ImagePullBackOff，提示 unauthorized**：dev 和 prod 项目没有从 `creed-lab` 拉取镜像的权限（步骤 2 里的 `image-puller`）。
- **CI 推送触发 CI，造成无限循环**：清单变更的提交又触发了流水线。在 TriggerBinding 或 `when` 条件里忽略只修改了 `manifests/` 的提交，
  或者把清单放到单独的仓库里。
- **Secret 不要明文提交到 Git**：可以用 Sealed Secrets、External Secrets，或者 Argo CD Vault 插件（第 11 章）。

## 参考文档

- Red Hat OpenShift GitOps 文档：*Installing GitOps*、*Argo CD instance*、*Managing namespaces*
- Argo CD 文档：*Core Concepts*、*Automated Sync Policy*、*Private Repositories*（https://argo-cd.readthedocs.io/）
- Kustomize 文档：https://kubectl.docs.kubernetes.io/references/kustomize/
