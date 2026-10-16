# 03 配置、探针、资源

## 目标

- 配置与镜像分离：同一个镜像，用 ConfigMap/Secret 区分 mock 和真实 Jira。
- 三种探针各用对地方，滚动发布时没有一个请求失败。
- 给容器设置合理的 requests/limits，理解 JVM 堆和容器内存上限的关系。

## 知识点

### 配置注入

- **ConfigMap 映射成环境变量** → Spring 的宽松绑定：`VERACODE_JIRA_PROJECT_KEY` 对应 `veracode.jira.project-key`。
  本仓库的约定是 `${CREED_FOO:fallback}`，所以直接设置 `CREED_JIRA_PROJECT`、`CREED_VERACODE_MOCK` 这类变量最省事。
- **Secret** 用来放 `CREED_JIRA_TOKEN` 这类敏感值。Secret 默认只是 **base64 编码，不是加密**；靠 RBAC 控制谁能读，
  生产环境再配合 etcd 加密或外部密钥管理系统。
- **修改 ConfigMap 不会让 Pod 自动重启**。以环境变量方式注入时，需要 `oc rollout restart`。

### 探针（Spring Boot 在 Kubernetes 上会自动开启探针健康组）

| 探针 | 端点 | 失败时的后果 | 用途 |
|---|---|---|---|
| startupProbe | `/actuator/health/liveness` | 启动期间失败只是继续等，不重启 | JVM 启动慢；避免 liveness 在启动阶段误杀 |
| readinessProbe | `/actuator/health/readiness` | 从 Service 的 EndpointSlice 中**摘除**（不重启） | 决定流量能不能进来，**这就是负载均衡的开关** |
| livenessProbe | `/actuator/health/liveness` | **重启容器** | 只用来检测「进程卡死」，**不要把下游依赖放进去** |

Spring Boot 检测到 `KUBERNETES_SERVICE_HOST` 环境变量时，会自动启用 `liveness` 和 `readiness` 两个健康组。

### 资源

- `requests` 决定调度和资源保证；`limits.memory` 超出就会被 **OOMKilled**；`limits.cpu` 超出不会被杀，只会被限流（throttle）。
- **JVM 默认只用容器内存上限的 25% 做堆**，所以第 02 章设置了 `-XX:MaxRAMPercentage=75`。
  剩下的部分留给元空间、线程栈、直接内存（Tomcat 和 POI 都会用到）。
- **HPA** 需要指标：CRC 上要开启集群监控（第 00 章）才有 CPU 指标。

## 步骤

```bash
# 1. 配置
oc create configmap veracode-config \
  --from-literal=CREED_VERACODE_MOCK=true \
  --from-literal=CREED_JIRA_PROJECT=CREED
oc create secret generic veracode-secrets --from-literal=CREED_JIRA_TOKEN=dummy-for-mock
oc set env deploy/veracode --from=configmap/veracode-config
oc set env deploy/veracode --from=secret/veracode-secrets

# 2. 探针
oc set probe deploy/veracode --startup   --get-url=http://:18097/actuator/health/liveness  --period-seconds=5 --failure-threshold=30
oc set probe deploy/veracode --readiness --get-url=http://:18097/actuator/health/readiness --period-seconds=5
oc set probe deploy/veracode --liveness  --get-url=http://:18097/actuator/health/liveness  --period-seconds=10

# 3. 资源
oc set resources deploy/veracode --requests=cpu=100m,memory=384Mi --limits=memory=768Mi

# 4. 把上面的改动导回 YAML，提交到 manifests/（这是第 08 章 GitOps 的素材）
oc get deploy veracode -o yaml | less    # 手工整理出 spec 部分，删掉 status、uid、resourceVersion 等字段

# 5.（开启了监控时）HPA
oc autoscale deploy/veracode --min=2 --max=4 --cpu-percent=70
oc get hpa -w
```

## 检查点

- [ ] `oc set env deploy/veracode --list` 能看到来自 ConfigMap 和 Secret 的变量
- [ ] 修改 ConfigMap 后，确认 Pod 里的值**没有**变化；执行 `oc rollout restart deploy/veracode` 后才变
- [ ] `oc describe pod` 里能看到三个探针；人为制造启动慢（例如把 startup 的 `failure-threshold` 改成 1），观察会发生什么
- [ ] 把 `limits.memory` 改成 200Mi，观察 `OOMKilled`（`oc get pod` 的 `RESTARTS` 一列和 `oc describe` 里的 `Last State`）

## 常见坑

- **liveness 依赖了外部系统**：Jira 一挂，所有 Pod 都被重启，故障被放大。liveness 只检查进程本身。
- **没有 readinessProbe**：滚动发布时，新 Pod 端口刚打开、Spring 上下文还没就绪就开始接流量，会出现几秒的 502/503。
- **requests 设得太大**：单节点的 CRC 上，后面的 Pod 会一直 Pending，`oc describe pod` 会显示 `Insufficient memory`。

## 参考文档

- OCP 文档：*Building applications → Application health*（探针）
- OCP 文档：*Nodes → Working with pods → Automatically scaling pods with the horizontal pod autoscaler*
- Kubernetes 文档：*Configure Liveness, Readiness and Startup Probes*；*Resource Management for Pods and Containers*
- Spring Boot 文档：*Actuator → Kubernetes Probes*（https://docs.spring.io/spring-boot/reference/actuator/endpoints.html）
