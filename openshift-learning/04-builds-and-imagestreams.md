# 04 集群内构建：BuildConfig 与 ImageStream

## 目标

- 分清 OpenShift 的三种构建方式，知道各自适合什么场景。
- 用 ImageStream 触发器实现「镜像一更新，Deployment 自动滚动」。
- 理解为什么第 07 章要换成 Tekton：BuildConfig 只负责构建镜像，不管测试、审批和多环境发布。

## 知识点

| 方式 | 输入 | 适合 | 这个仓库的注意点 |
|---|---|---|---|
| **Docker strategy** | Git 仓库或上传目录里的 Dockerfile | 已经有 Dockerfile | 需要先产出可执行 jar（见第 02 章），要么在 Dockerfile 里做多阶段构建 |
| **Binary（`--from-dir` / `--from-file`）** | 本机上传的构建上下文 | 本地已经构建好产物，只差打镜像 | 第 02 章用的就是这种；上传时注意排除大目录 |
| **S2I（Source-to-Image）** | Git 源码 + builder 镜像（如 `ubi9/openjdk-21`） | 标准的 Maven 单模块工程 | 本仓库是**多模块**工程且需要 `repackage`，要用 `MAVEN_ARGS` 和 `ARTIFACT_DIR` 等变量调整，否则会拿到瘦 jar |

- **ImageStream / ImageStreamTag**：`veracode:latest` 是集群内的一个指针，指向某个镜像摘要（digest）。
  Deployment 加上注解 `image.openshift.io/triggers` 之后，指针一变就会自动更新镜像并触发滚动发布。
- **Build 的触发器**：`ConfigChange`、`ImageChange`（基础镜像更新时重新构建）、GitHub/Generic webhook。
  在 CRC 上，**GitHub 访问不到你本机的集群**，所以 webhook 只能用 curl 在本地模拟。
- **多阶段 Dockerfile**：在构建阶段运行 Maven 并执行 `repackage`，运行阶段只拷贝 jar。
  这样镜像构建就不再依赖本机环境，但每次都要下载依赖。缓存问题在第 07 章用 PVC 解决。

## 步骤

### 1. ImageStream 触发自动发布

```bash
# 让 Deployment 跟随 veracode:latest
oc set triggers deploy/veracode --from-image=veracode:latest -c veracode
oc get deploy veracode -o jsonpath='{.metadata.annotations.image\.openshift\.io/triggers}'; echo

# 改一行代码或改一下 Dockerfile 里的 ENV，然后重新执行一次 Binary 构建
oc start-build veracode --from-dir=tmp/ocp-ctx --follow
oc rollout status deploy/veracode        # 构建完成后自动滚动，不需要手动 apply
oc get istag veracode:latest             # 摘要变了
```

### 2. 多阶段 Dockerfile + Git 源（可选练习）

```dockerfile
# openshift-learning/manifests/veracode/Dockerfile.multistage
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN mvn -q -pl creed-resource/creed-resource-veracode -am -DskipTests install \
 && mvn -q -pl creed-resource/creed-resource-veracode -DskipTests package spring-boot:repackage \
 && cp creed-resource/creed-resource-veracode/target/creed-resource-veracode-*.jar /app.jar

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime
COPY --from=build /app.jar /deployments/app.jar
ENV LOGGING_FILE_PATH=/tmp/logs OTEL_SDK_DISABLED=true JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75"
EXPOSE 18097
```

```bash
# 仓库是私有的话，先建一个 Git 凭据 Secret（GitHub Personal Access Token，只给只读权限）
oc create secret generic github-read --type=kubernetes.io/basic-auth \
  --from-literal=username=<github-user> --from-literal=password=<PAT>
oc new-build https://github.com/PhotonAlpha/creed-ai-lab.git#master-spring-boot-3 \
  --name veracode-git --strategy docker \
  --source-secret=github-read
oc patch bc/veracode-git --type=merge \
  -p '{"spec":{"strategy":{"dockerStrategy":{"dockerfilePath":"openshift-learning/manifests/veracode/Dockerfile.multistage"}}}}'
oc start-build veracode-git --follow
```

> 根目录的 `.dockerignore`（或在 BuildConfig 里设置 `contextDir`）决定上传多少内容；
> 至少要排除 `**/node_modules`、`**/target`、`tmp/`、`logs/`。

### 3. 模拟 Generic webhook

```bash
oc describe bc/veracode-git | grep -A1 -i webhook     # 显示 URL 和 secret 的位置
# 按输出把 <secret> 替换进去，然后在本机调用：
curl -sk -X POST "https://api.crc.testing:6443/apis/build.openshift.io/v1/namespaces/creed-lab/buildconfigs/veracode-git/webhooks/<secret>/generic"
oc get builds -w
```

## 检查点

- [ ] 能说出 Build、BuildConfig、ImageStream、ImageStreamTag 四者的关系
- [ ] 重新执行 `start-build` 后，Deployment **不需要任何手动操作**就完成了滚动，`oc rollout history deploy/veracode` 多了一个版本
- [ ] `oc logs build/veracode-git-1` 里能看到 Maven 的输出和 `repackage`
- [ ] 能解释：为什么 GitHub 的 webhook 在 CRC 上用不了，生产集群又需要满足什么条件

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。

### 实验 1：一次 Build 在集群里是什么

```bash
oc start-build veracode --from-dir=tmp/ocp-ctx
oc get pod -l openshift.io/build.name -w                      # 构建 Pod：veracode-N-build
oc get build -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,DIGEST:.status.output.to.imageDigest
oc logs build/veracode-N | head -20                           # 把 N 换成最新序号
```

**观察 / 解释**：
- 每次 Build 就是一个普通的 Pod，名字是 `<build>-build`，用 Buildah 在里面构建镜像，然后推送到内置仓库。
- `--from-dir` 会把目录打成 tar 包，经 API Server 传进构建 Pod。构建日志开头能看到接收源码的输出。这也是上传整个仓库会很慢的原因。
- 构建成功后，`status.output.to.imageDigest` 记录了**推送上去的那个 digest**。之后所有「指向哪个镜像」的问题，都以 digest 为准。

### 实验 2：ImageStreamTag 是一个可以移动的指针

```bash
oc describe is veracode | sed -n '/latest/,/^$/p'      # tag 历史：每次构建一条，最上面是当前
OLD=$(oc get is veracode -o jsonpath='{.status.tags[?(@.tag=="latest")].items[1].image}')
echo $OLD                                               # 上一次构建的 digest
oc tag veracode@$OLD veracode:latest                    # 把指针拨回上一个镜像
oc rollout status deploy/veracode                       # 触发器跟着滚动了：这就是一次「镜像级回滚」
```

**解释**：
- 镜像本身不可变，`latest` 只是 ImageStream 里一个随时可以挪的名字，`oc tag` 就是挪指针。
- 有了触发器以后，挪指针就等于发布。所以生产环境要用 RBAC 控制谁能执行 `oc tag`。

### 实验 3：触发器到底改了 Deployment 的什么

```bash
oc get deploy veracode -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
oc get deploy veracode -o jsonpath='{.metadata.annotations.image\.openshift\.io/triggers}' | python3 -m json.tool
```

**观察**：
- Deployment 里的 `image` 被改成了 `image-registry.openshift-image-registry.svc:5000/creed-lab/veracode@sha256:...`，
  是 **digest**，而不是你写的 `:latest`。
- 触发器控制器监听 ImageStreamTag，变化时把解析出的 digest 写回 Pod 模板，模板变了就滚动。
- 这也顺带解决了第 02 章实验 4 的问题：所有 Pod 都钉在同一个 digest 上。

### 实验 4：为什么短镜像名会 ImagePullBackOff，怎么让它能用

```bash
oc set image-lookup veracode                            # 打开 lookupPolicy.local
oc get is veracode -o jsonpath='{.spec.lookupPolicy}'; echo
oc run lookup-test --image=veracode:latest --restart=Never --command -- sleep 30
oc get pod lookup-test -o jsonpath='{.spec.containers[0].image}'; echo   # 已被改写成完整地址 + digest
oc delete pod lookup-test
```

**解释**：
- 容器运行时只认完整的镜像地址，短名 `veracode` 默认会被当成 `docker.io/library/veracode`。
- 打开 `lookupPolicy.local` 后，在 Pod **准入**阶段，OpenShift 会把同项目里 ImageStream 的短名改写成内置仓库的完整地址。
  这和第 01 章 SCC 改写 securityContext 是同一个时机。

### 实验 5：BuildConfig 的 ConfigChange / ImageChange 触发

```bash
oc get bc veracode -o jsonpath='{.spec.triggers}'; echo
oc set triggers bc/veracode --from-image=openjdk-21-runtime:latest   # 需要先 oc import-image 这个基础镜像
oc import-image openjdk-21-runtime:latest --from=registry.access.redhat.com/ubi9/openjdk-21-runtime --confirm
oc get builds -w                                                   # 基础镜像更新时会自动重新构建
```

**解释**：
- 形成了一条链：**基础镜像更新 → 重新构建 → 新的 veracode:latest → Deployment 滚动**，全程不需要人工操作。
- 这条链就是「安全补丁自动生效」的实现方式，同时也是「上游一变，生产跟着变」的风险来源。
- 用 `oc import-image --scheduled` 可以定期检查上游镜像。

### 检查点（机制）

- [ ] 找到了构建 Pod，并说出 `imageDigest` 记录在哪个对象的哪个字段
- [ ] 用 `oc tag` 拨回了上一个 digest，Deployment 自动回滚
- [ ] 看到触发器把 Deployment 的 `image` 改成了 `@sha256:` 形式
- [ ] 能解释 `lookupPolicy.local` 是在哪个阶段改写镜像名的

## 常见坑

- **多阶段构建里的 Maven 每次都重新下载依赖**：BuildConfig 没有好用的缓存机制，这是第 07 章改用 Tekton 加 PVC 的理由之一。
- **Docker Hub 拉取限流**：`maven:...` 来自 docker.io，匿名拉取有次数限制。可以换成 `registry.access.redhat.com/ubi9/openjdk-21`
  这类 builder 镜像，但要自己装 Maven 或改用 S2I。
- **镜像标签一直用 `latest`**：触发器能工作，但回滚和审计时说不清当时跑的是哪个版本。第 07 章起改用 Git SHA 作为标签。
- 使用 `--strategy docker` 时 OCP 用 Buildah 构建镜像，而且是**在集群里构建**：不需要本机 docker，镜像架构天然与集群一致（arm64）。

## 参考文档

- OCP 文档：*Builds using BuildConfig*，重点看 *Understanding image builds*、*Build strategies*、*Triggering and modifying builds*
- OCP 文档：*Images → Managing image streams*，以及 *Using image streams with Kubernetes resources*
- Source-to-Image 项目：https://github.com/openshift/source-to-image
