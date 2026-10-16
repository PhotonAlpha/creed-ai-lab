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
