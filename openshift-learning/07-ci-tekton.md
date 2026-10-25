# 07 CI：OpenShift Pipelines（Tekton）

## 目标

- 安装 OpenShift Pipelines Operator。
- 写一条流水线：`git clone → mvn test → 打可执行 jar → buildah 构建镜像 → 推送到内置仓库（标签用 Git SHA）`，并且带 Maven 缓存。
- 用 Tekton Triggers 加 EventListener 接收「webhook」（在 CRC 上用 curl 模拟）。

## 知识点

| 对象 | 说明 |
|---|---|
| **Step** | 一个容器里执行的一段脚本 |
| **Task** | 一组按顺序执行的 Step，**在同一个 Pod 里运行**，共享 workspace |
| **Pipeline** | 由多个 Task 组成的 DAG（用 `runAfter` 排顺序），Task 之间通过 **workspace**（PVC）传递文件，通过 **results** 传递小的值 |
| **PipelineRun / TaskRun** | 一次具体的执行；运行记录本身就是 Kubernetes 对象 |
| **Resolver** | 引用 Task 的方式。新版本已经**移除了 ClusterTask**，改用 `resolver: cluster` 引用 `openshift-pipelines` 命名空间里自带的 Task，或者用 `resolver: hub` / `git` / `bundles` |
| **Triggers** | `EventListener`（一个接收 HTTP 请求的 Pod + Service）→ `TriggerBinding`（从请求体中取字段）→ `TriggerTemplate`（生成 PipelineRun） |
| **`pipeline` ServiceAccount** | Operator 在每个项目里自动创建，拥有向本项目 ImageStream 推送镜像的权限 |

和 BuildConfig 相比，Tekton 能编排测试、多步骤构建、条件判断（`when`）、审批和失败处理（`finally`）。
BuildConfig 只负责「源码 → 镜像」这一步。

## 步骤

### 1. 安装（kubeadmin）

Web Console → Administrator → **OperatorHub**，搜索 *Red Hat OpenShift Pipelines*，安装到 All namespaces。
CLI `tkn`：Console 右上角 `?` → *Command Line Tools* 下载，或者 `brew install tektoncd-cli`。

```bash
oc get csv -n openshift-operators | grep -i pipelines        # Succeeded
oc get task -n openshift-pipelines                           # 看实际可用的 Task 名字（例如 git-clone、maven、buildah、openshift-client，可能带版本后缀）
tkn version
```

### 2. Workspaces 和凭据

```bash
oc project creed-lab
# 共享工作区：源码 + Maven 本地仓库缓存（第二次运行明显更快）
cat <<'EOF' | oc apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: veracode-pipeline-ws }
spec:
  accessModes: [ReadWriteOnce]
  resources: { requests: { storage: 5Gi } }
EOF
# 私有仓库：沿用第 04 章的 github-read，加上 Tekton 的注解，并关联到 pipeline ServiceAccount
oc annotate secret github-read tekton.dev/git-0=https://github.com --overwrite
oc secrets link pipeline github-read
```

### 3. Pipeline（保存为 `openshift-learning/manifests/pipeline/pipeline.yaml`）

```yaml
apiVersion: tekton.dev/v1
kind: Pipeline
metadata:
  name: veracode-ci
spec:
  params:
    - { name: git-url, default: https://github.com/PhotonAlpha/creed-ai-lab.git }
    - { name: git-revision, default: master-spring-boot-3 }
    - { name: image, default: image-registry.openshift-image-registry.svc:5000/creed-lab/veracode }
  workspaces:
    - name: ws
  tasks:
    - name: clone
      taskRef:
        resolver: cluster
        params:
          - { name: kind, value: task }
          - { name: name, value: git-clone }            # 以 oc get task -n openshift-pipelines 的结果为准
          - { name: namespace, value: openshift-pipelines }
      params:
        - { name: URL, value: $(params.git-url) }       # 参数名以该 Task 的定义为准：tkn task describe -n openshift-pipelines git-clone
        - { name: REVISION, value: $(params.git-revision) }
      workspaces:
        - { name: output, workspace: ws, subPath: src }

    - name: test-and-package
      runAfter: [clone]
      workspaces:
        - { name: source, workspace: ws }
      taskSpec:                                        # 内联 Task：仓库的两步打包规则写在这里最直观
        workspaces: [{ name: source }]
        steps:
          - name: mvn
            image: docker.io/library/maven:3.9-eclipse-temurin-25
            workingDir: $(workspaces.source.path)/src
            env:
              - { name: MAVEN_OPTS, value: "-Dmaven.repo.local=$(workspaces.source.path)/m2" }
            script: |
              set -e
              mvn -B -q -pl creed-resource/creed-resource-veracode -am install
              mvn -B -q -pl creed-resource/creed-resource-veracode -DskipTests package spring-boot:repackage
              mkdir -p ../ctx
              cp creed-resource/creed-resource-veracode/target/creed-resource-veracode-*.jar ../ctx/app.jar
              cp openshift-learning/manifests/veracode/Dockerfile ../ctx/Dockerfile

    - name: build-image
      runAfter: [test-and-package]
      taskRef:
        resolver: cluster
        params:
          - { name: kind, value: task }
          - { name: name, value: buildah }
          - { name: namespace, value: openshift-pipelines }
      params:
        - { name: IMAGE, value: "$(params.image):$(tasks.clone.results.commit)" }
        - { name: CONTEXT, value: ctx }
      workspaces:
        - { name: source, workspace: ws }
```

```bash
oc apply -f openshift-learning/manifests/pipeline/pipeline.yaml
tkn pipeline start veracode-ci -w name=ws,claimName=veracode-pipeline-ws --use-param-defaults --showlog
tkn pipelinerun list
oc get istag | grep veracode            # 多了一个以 commit SHA 为标签的镜像
```

### 4. Triggers：模拟 GitHub push webhook

```yaml
# openshift-learning/manifests/pipeline/triggers.yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerBinding
metadata: { name: github-push }
spec:
  params:
    - { name: git-revision, value: $(body.after) }
---
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerTemplate
metadata: { name: veracode-ci }
spec:
  params: [{ name: git-revision }]
  resourcetemplates:
    - apiVersion: tekton.dev/v1
      kind: PipelineRun
      metadata: { generateName: veracode-ci- }
      spec:
        pipelineRef: { name: veracode-ci }
        params: [{ name: git-revision, value: $(tt.params.git-revision) }]
        workspaces: [{ name: ws, persistentVolumeClaim: { claimName: veracode-pipeline-ws } }]
---
apiVersion: triggers.tekton.dev/v1beta1
kind: EventListener
metadata: { name: veracode }
spec:
  serviceAccountName: pipeline
  triggers:
    - bindings: [{ ref: github-push }]
      template: { ref: veracode-ci }
```

```bash
oc apply -f openshift-learning/manifests/pipeline/triggers.yaml
oc expose svc el-veracode
EL=$(oc get route el-veracode -o jsonpath='{.spec.host}')
SHA=$(git rev-parse HEAD)
curl -s -XPOST http://$EL -H 'Content-Type: application/json' -H 'X-GitHub-Event: push' -d "{\"after\":\"$SHA\"}"
tkn pipelinerun logs -f --last
```

## 检查点

- [ ] 流水线运行成功，`oc get istag` 里有以 commit SHA 为标签的 veracode 镜像
- [ ] 第二次运行时，`test-and-package` 明显变快（Maven 缓存生效）
- [ ] 故意改坏一个测试，确认流水线在 `test-and-package` 失败，并且**没有**产出镜像
- [ ] curl 模拟的 webhook 触发了一次 PipelineRun
- [ ] 能解释 workspace、results、params 三种传值方式分别适合传什么

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。先跑完一次流水线，再拿它的运行记录做解剖。

```bash
PR=$(tkn pipelinerun list -o jsonpath='{.items[0].metadata.name}')
```

### 实验 1：一个 Task = 一个 Pod，一个 Step = 一个容器

```bash
oc get taskrun -l tekton.dev/pipelineRun=$PR
TR_POD=$(oc get pod -l tekton.dev/pipelineRun=$PR,tekton.dev/pipelineTask=test-and-package -o name)
oc get $TR_POD -o jsonpath='init: {.spec.initContainers[*].name}{"\n"}steps: {.spec.containers[*].name}{"\n"}'
oc get $TR_POD -o jsonpath='{.spec.containers[0].command}{"\n"}{.spec.containers[0].args}{"\n"}'
```

**观察 / 解释**：
- 每个 Step 都是 Pod 里的一个容器，名字是 `step-<名字>`；init 容器负责准备入口程序和脚本。
- 容器的 `command` 不是你写的脚本，而是 **`/tekton/bin/entrypoint`**，参数里带着 `-wait_file` 和 `-post_file`。
- Kubernetes 会**同时**启动一个 Pod 里的所有容器，Tekton 却需要按顺序执行。它的办法是：
  每个 step 先等上一个 step 写出的文件（`/tekton/run/N/out`）出现，再开始执行自己的脚本。
  「顺序执行」是 Tekton 自己用文件实现的，不是 Kubernetes 提供的能力。

### 实验 2：results 是怎么从 Task 传出来的

```bash
oc get taskrun -l tekton.dev/pipelineRun=$PR,tekton.dev/pipelineTask=clone -o jsonpath='{.items[0].status.results}' | python3 -m json.tool
CLONE_POD=$(oc get pod -l tekton.dev/pipelineRun=$PR,tekton.dev/pipelineTask=clone -o name)
oc get $CLONE_POD -o jsonpath='{.status.containerStatuses[*].state.terminated.message}' | head -c 400; echo
```

**解释**：
- step 把结果写到 `/tekton/results/<名字>`，entrypoint 在容器退出时把它放进**容器的终止消息**（termination message）。
- 控制器从 Pod 的 status 里读出终止消息，写进 TaskRun 的 `status.results`，下游 Task 用 `$(tasks.clone.results.commit)` 引用。
- 终止消息有大小上限（每个容器 4 KiB 左右，整个 Pod 12 KiB）。所以 results 只适合传 commit SHA、digest 这类小值，文件要走 workspace。

### 实验 3：workspace 背后的 PVC 和 affinity assistant

```bash
oc get pod | grep -i affinity-assistant          # 流水线运行期间才有
oc get pod -l tekton.dev/pipelineRun=$PR -o wide | awk '{print $1, $7}'   # 各个 Task Pod 落在哪个节点
oc get pvc veracode-pipeline-ws -o jsonpath='{.spec.accessModes}'; echo
```

**解释**：
- ReadWriteOnce 的 PVC 只能被**一个节点**挂载。
- 流水线的多个 Task Pod 共用这个 PVC 时，Tekton 会先起一个 affinity assistant Pod，再让所有 Task Pod 用 Pod 亲和调度到它所在的节点上。
- 在单节点的 CRC 上看不出区别，但在多节点集群上，这就是「为什么我的流水线 Pod 全挤在一个节点上」的答案。

### 实验 4：参数替换发生在哪里

```bash
oc get taskrun -l tekton.dev/pipelineRun=$PR,tekton.dev/pipelineTask=build-image -o jsonpath='{.items[0].spec.params}' | python3 -m json.tool
oc get taskrun -l tekton.dev/pipelineRun=$PR,tekton.dev/pipelineTask=build-image -o jsonpath='{.items[0].status.taskSpec.steps[0].script}' | head -5
```

**观察**：
- TaskRun 的 `spec.params` 里，`IMAGE` 已经是带 commit SHA 的完整地址了。
- `$(...)` 是 Tekton 控制器在**创建 Pod 之前**做的字符串替换，不是 shell 变量。所以 `$(params.x)` 不能写成 `${params.x}`；
  脚本里真正的 shell 变量要写成 `$VAR`，并避开 `$(...)` 这种语法。

### 实验 5：Task 是从哪里「解析」来的

```bash
oc get resolutionrequests -n creed-lab | tail -3
oc get resolutionrequest -n creed-lab -o jsonpath='{.items[-1:].spec.params}'; echo
```

**解释**：
- `resolver: cluster` 并不是把 Task 复制到你的项目里，而是每次运行时由解析器去 `openshift-pipelines` 命名空间读取定义，结果嵌进 TaskRun 的 `status.taskSpec`。
- 所以 Operator 升级、更新自带的 Task 之后，**下一次运行就会用上新版本**，参数名也可能跟着变（见「常见坑」）。

### 实验 6：`finally` 和失败

```bash
oc patch pipeline veracode-ci --type=json -p '[{"op":"add","path":"/spec/finally","value":[{"name":"report","taskSpec":{"steps":[{"name":"echo","image":"registry.access.redhat.com/ubi9/ubi-minimal","script":"echo pipeline status: $(tasks.status)"}]}}]}]'
tkn pipeline start veracode-ci -w name=ws,claimName=veracode-pipeline-ws --use-param-defaults -p git-revision=does-not-exist --showlog
```

**观察**：
- `clone` 失败，后面的 Task 都被跳过（Skipped），但 `finally` 里的 `report` 仍然执行，并打印出 `Failed`。
- `finally` 是放通知、清理、回写状态的地方。`$(tasks.status)` 是 Tekton 提供的聚合状态变量。

### 检查点（机制）

- [ ] 能解释 Tekton 是怎么让同一个 Pod 里的多个容器按顺序执行的
- [ ] 在 Pod 的终止消息里找到了 `commit` 这个 result
- [ ] 能说出 results 和 workspace 各自的容量边界
- [ ] 看到参数替换后的 TaskRun spec，并能解释为什么 `$(params.x)` 不是 shell 语法
- [ ] `finally` 在主流程失败时依然执行

## 常见坑

- **流水线 Pod 内存不够**：Maven 构建加上 buildah 很吃内存，CRC 内存小的时候 Pod 会 OOMKilled 或一直 Pending。回到第 00 章加内存，或者先关掉监控。
- **PVC 是 ReadWriteOnce**：同一时间只能有一个 PipelineRun 使用它。CRC 只有一个节点，问题不大；生产环境要用 `volumeClaimTemplate`，每次运行单独一个 PVC。
- **流水线里没有 JDK 25**：本仓库在本机用 JDK 25 构建，流水线也用 `temurin-25` 镜像，与本机一致。
- **Task 参数名对不上**：自带 Task 的参数名在不同版本之间会变化，以 `tkn task describe -n openshift-pipelines <name>` 为准。
- **真实的 GitHub webhook**：GitHub 访问不到 `*.apps-crc.testing`。需要一个公网可达的集群，或者一个隧道（例如 smee.io）。
  另外，正式使用时要在 EventListener 上配置 GitHub interceptor，校验 webhook secret。

## 参考文档

- Red Hat OpenShift Pipelines 文档：*Installing*、*Creating CI/CD solutions for applications*、*Triggers*
- Tekton 文档：*Tasks*、*Pipelines*、*Workspaces*、*Resolvers*、*Triggers*（https://tekton.dev/docs/）
- Tekton Hub / Artifact Hub（社区 Task）：https://artifacthub.io/
