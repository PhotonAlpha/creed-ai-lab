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
│   ├── kustomization.yaml       # resources: deployment.yaml, service.yaml, route.yaml
│   ├── deployment.yaml          # 第 02、03 章的成果：探针、资源、envFrom
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
