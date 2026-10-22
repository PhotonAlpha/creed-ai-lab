# 02 容器化并部署第一个应用：creed-resource-veracode

## 目标

- 给 `creed-resource-veracode` 写一个能在 `restricted-v2`（随机 UID）下运行的镜像。
- 把镜像放进集群内置仓库，用**手写 YAML**（Deployment + Service + Route）部署，并从浏览器访问。

## 知识点

- **可执行 jar（这个仓库特有）**：根 pom 没有继承 `spring-boot-starter-parent`，`spring-boot-maven-plugin`
  也没有绑定 `repackage`，所以普通的 `mvn package` 只产出不含依赖的瘦 jar。要分两步构建（已在本机验证）：
  1. `install` 依赖的模块；
  2. 只对 veracode 模块执行 `package spring-boot:repackage`。

  第二步**不能带 `-am`**：带上后会把 `creed-common-metrics` 这个库也打包成可执行 jar，因为它没有 main class 而报错。
- **基础镜像**：选 `registry.access.redhat.com/ubi9/openjdk-21-runtime`。它是多架构镜像，arm64 可用，
  并且本来就是按 OpenShift 的随机 UID 设计的。工程源码按 Java 21 编译（本机用 JDK 25 构建），在 Java 21 运行时上运行没有问题。
- **镜像进集群的三种方式**：
  - (a) 本机 `docker build`，再 push 到内置仓库对外暴露的 route；
  - (b) `oc start-build --from-dir` 做 Binary 构建，本章用这种；
  - (c) 在流水线里构建，见第 07 章。
- **这个应用上 OCP 必须改的两处配置**：
  - `creed-common-metrics` 的 logback 默认写到 `./logs`，用 `LOGGING_FILE_PATH=/tmp/logs` 改到一定可写的目录；
  - OTel 默认把数据导出到 `localhost:4318`，集群里没有 collector，用 `OTEL_SDK_DISABLED=true` 关掉（第 10 章再接监控）。
- **mock 模式在集群里照样能用**：veracode 的 Confluence 和 Jira base URL 默认指向 `http://localhost:${server.port}/mock/...`，
  也就是 Pod 自己调用自己，不需要任何外部系统。

### Deployment、Service、Route：分别是什么，怎么连起来

第 4 步要手写这三个对象。它们各管一件事，缺一个浏览器都访问不到应用：

| 对象 | 回答的问题 | 本章里的作用 |
|---|---|---|
| **Deployment** | 「跑什么、跑几份」 | 按 Pod 模板（镜像、端口、环境变量）维持 `replicas` 个 Pod；Pod 挂了重建，改模板就滚动替换 |
| **Service** | 「集群里怎么稳定地找到这些 Pod」 | Pod 会重建、IP 会变；Service 给它们一个固定的名字 `veracode.creed-lab.svc` 和虚拟 IP，并在就绪的 Pod 之间做负载均衡。只在**集群内部**可达 |
| **Route** | 「集群外面怎么进来」 | OpenShift 特有。在路由器（HAProxy）上登记一个外部主机名，如 `veracode-creed-lab.apps-crc.testing`，可以在这里终止 TLS，再把流量转给 Service 背后的 Pod |

一句话：**Deployment 负责让 Pod 活着，Service 负责让 Pod 能被找到，Route 负责让外部能访问到。**

三者之间**没有父子关系**，谁也不「包含」谁，靠两种引用松散地连在一起：

```text
浏览器 ──https──▶ Route ──(spec.to.name)──▶ Service ──(selector 匹配 label)──▶ Pod ◀──(创建/维护)── Deployment
                 veracode                    veracode     app=veracode          app=veracode           veracode
```

1. **Service → Pod：靠 label 选择器，不是靠名字。** Service 的 `selector: { app: veracode }` 去匹配所有带
   `app=veracode` 标签的 Pod，而这个标签来自 Deployment 的 `spec.template.metadata.labels`。
   Service 根本不知道 Deployment 的存在：手动起一个带同样标签的 Pod，也会被它选中。
   Deployment 自己的 `spec.selector.matchLabels` 是另一回事，它只用来认领「哪些 Pod 归我管」，必须和模板里的标签一致。
2. **Route → Service：靠名字。** `spec.to: { kind: Service, name: veracode }` 直接写死 Service 的名字。

端口也是一条链，用端口**名字** `http` 串起来，比写死数字更不容易改漏：

| 位置 | 字段 | 值 | 含义 |
|---|---|---|---|
| Deployment | `containerPort` / `name` | `18097` / `http` | 应用在容器里真正监听的端口，并起名 `http` |
| Service | `port` | `8080` | 集群内调用方访问的端口：`http://veracode:8080` |
| Service | `targetPort` | `http` | 转发到 Pod 上名为 `http` 的端口，即 18097 |
| Route | `port.targetPort` | `http` | 选用 Service 里名为 `http` 的端口 |

> 一个细节：HAProxy 路由器**不经过** Service 的虚拟 IP，而是直接读取 Service 对应的 EndpointSlice，把请求发给 Pod IP。
> 所以 Route 的流量不会用到 Service 的 `port: 8080`；Service 在这里的作用是「圈定哪些 Pod、用哪个端口」。
> 集群内其他 Pod 调 `veracode:8080` 时才走 Service 的虚拟 IP。第 05、06 章细讲两种负载均衡的区别。

因此排查时也按这条链从后往前看：Pod 是否 Running 且就绪 → EndpointSlice 里有没有 Pod IP（selector、端口名对不对）→
Route 的 `to.name` 是否指向正确的 Service。见下文「常见坑」里的 503。

## 步骤

### 1. 打可执行 jar（在仓库根目录执行）

```bash
mvn -q -pl creed-resource/creed-resource-veracode -am -DskipTests install
mvn -q -pl creed-resource/creed-resource-veracode -DskipTests package spring-boot:repackage
ls -la creed-resource/creed-resource-veracode/target/*.jar   # 约 56 MB 的才是可执行 jar
```

### 2. Dockerfile（保存为 `openshift-learning/manifests/veracode/Dockerfile`）

```dockerfile
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

# 只拷贝 jar。构建上下文是一个只装着 jar 和本文件的临时目录，见第 3 步
COPY app.jar /deployments/app.jar

# 随机 UID 运行、GID 0：/tmp 一定可写；日志目录交给环境变量 LOGGING_FILE_PATH
ENV LOGGING_FILE_PATH=/tmp/logs \
    OTEL_SDK_DISABLED=true \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75"

EXPOSE 18097
# 用基础镜像自带的启动脚本（run-java.sh）：它会读取 JAVA_OPTS_APPEND，并启动 /deployments 下的 jar
```

### 3. 在集群里构建镜像（Binary 构建，不需要配置 docker push）

```bash
oc project creed-lab
mkdir -p tmp/ocp-ctx
cp creed-resource/creed-resource-veracode/target/creed-resource-veracode-1.0.0-SNAPSHOT.jar tmp/ocp-ctx/app.jar
cp openshift-learning/manifests/veracode/Dockerfile tmp/ocp-ctx/

oc new-build --name veracode --strategy docker --binary
oc start-build veracode --from-dir=tmp/ocp-ctx --follow
oc get imagestream veracode            # 镜像已进入内置仓库：image-registry.openshift-image-registry.svc:5000/creed-lab/veracode
```

> 不要用 `--from-dir=.` 上传整个仓库：`node_modules`、`target`、`.git` 会被全部上传，既慢又可能超出限制。

### 4. 手写部署 YAML（保存为 `openshift-learning/manifests/veracode/base/*.yaml`）

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: veracode
  labels: { app: veracode }
spec:
  replicas: 1
  selector:
    matchLabels: { app: veracode }
  template:
    metadata:
      labels: { app: veracode }
    spec:
      containers:
        - name: veracode
          image: image-registry.openshift-image-registry.svc:5000/creed-lab/veracode:latest
          ports:
            - containerPort: 18097
              name: http
---
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: veracode
spec:
  selector: { app: veracode }
  ports:
    - name: http
      port: 8080
      targetPort: http
---
# route.yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: veracode
spec:
  to: { kind: Service, name: veracode }
  port: { targetPort: http }
  tls:
    termination: edge          # 由路由器终止 TLS，路由器到 Pod 走明文 HTTP；第 06 章细讲
    insecureEdgeTerminationPolicy: Redirect
```

```bash
oc apply -f openshift-learning/manifests/veracode/base/
oc rollout status deploy/veracode
HOST=$(oc get route veracode -o jsonpath='{.spec.host}')
curl -sk https://$HOST/api/veracode/settings
curl -sk -XPOST https://$HOST/api/veracode/analysis -H 'Content-Type: application/json' -d '{}' | head -c 300
```

## 检查点

- [ ] `oc get pod -l app=veracode` 显示 Running，`oc logs deploy/veracode` 里有 `Started CreedResourceVeracodeApplication`
- [ ] `oc rsh deploy/veracode id` 显示的不是 185（镜像 `USER` 指定的 UID），而是项目区间里的随机 UID
- [ ] 通过 Route 调 `/api/veracode/analysis`，得到 `counts.total = 10`
- [ ] 能说出：没设置 `LOGGING_FILE_PATH` 时会怎样，并实际验证一次（去掉这个变量后重新部署，看日志）

## 常见坑

- **`no main manifest attribute`**：jar 不是可执行 jar，回到第 1 步，确认执行了 `spring-boot:repackage`。
- **`exec format error`**：镜像架构与集群不一致。CRC 是 arm64，用 Binary 构建在集群里构建就天然一致。
- **`ImagePullBackOff`，提示 `docker.io/library/veracode`**：Deployment 里写了短镜像名。在集群内要写完整的内置仓库地址，
  或者使用 ImageStream 触发器（第 04 章）。
- **Route 返回 503 `Application is not available`**：Service 的 selector 没选中 Pod、`targetPort` 写错，或者 Pod 还没就绪。
  排查 `oc get endpointslices -l kubernetes.io/service-name=veracode`。

## 参考文档

- OCP 文档：*Images → Creating images → Image creation guidelines*，其中的 *Support arbitrary user ids* 一节
- OCP 文档：*Builds using BuildConfig → Performing and configuring basic builds*，其中的 *binary builds*
- OCP 文档：*Registry → Accessing the registry*
- Red Hat 镜像目录里 `ubi9/openjdk-21-runtime` 的说明（环境变量 `JAVA_OPTS_APPEND` 等）：https://catalog.redhat.com/
