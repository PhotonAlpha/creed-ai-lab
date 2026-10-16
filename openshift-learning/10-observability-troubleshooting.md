# 10 可观测与排错

## 目标

- 形成一套固定的排错顺序，遇到 Pod 起不来、Route 503、性能变慢时，都知道先看什么。
- 开启用户工作负载监控，用 ServiceMonitor 采集 veracode 的 `/actuator/prometheus` 指标，并在 Console 里查询。

## 知识点

### 排错顺序：事件 → 描述 → 日志 → 进入容器

| 现象 | 先看 |
|---|---|
| `Pending` | `oc describe pod`，看 Events：资源不足？PVC 没有绑定？ |
| `ImagePullBackOff` | 镜像地址、拉取权限、架构 |
| `CrashLoopBackOff` | `oc logs --previous`（**上一次崩溃时的日志**）、SCC 和随机 UID、缺少配置 |
| `Running` 但 `0/1 READY` | readinessProbe 的路径和端口；`oc describe` 里的 `Readiness probe failed` |
| Route 返回 503 | EndpointSlice 是否为空、Service 的 selector 和 targetPort、Pod 是否就绪 |
| Route 返回 504 | 路由器超时（第 06 章的 `timeout` 注解） |
| `OOMKilled` | `limits.memory` 和 `MaxRAMPercentage` |

### 常用命令

- `oc get events --sort-by=.lastTimestamp`
- `oc debug deploy/veracode`：用同一个镜像和配置起一个副本，**命令换成 shell**，排查启动即崩溃的问题最有用
- `oc rsh` / `oc exec`：进入正在运行的容器
- `oc port-forward svc/veracode 18097:8080`：绕过 Route，直接从本机访问
- `oc adm top pod`：需要开启集群监控

### 监控

- **平台监控**（`openshift-monitoring`）只采集平台组件。**用户工作负载监控**要在 `cluster-monitoring-config` 里设置 `enableUserWorkload: true` 才会开启。
  前提是 CRC 开启了集群监控（第 00 章），内存至少 14 GiB。
- **ServiceMonitor**：告诉 Prometheus 采集哪个 Service 的哪个端口和路径。veracode 已经包含 `micrometer-registry-prometheus`，
  并且 actuator 暴露了全部端点，所以 `/actuator/prometheus` 现成可用。
- **和本仓库已有的监控栈对比**：`monitoring/` 下的 docker compose（Prometheus/Grafana/Tempo/Loki）是在集群外运行的。
  集群里推荐用 OCP 自带的监控；也可以把 `CREED_OTEL_ENDPOINT` 指向一个 OTel Collector，那是进阶内容。

## 步骤

### 1. 制造故障并排查（每个都做一遍）

```bash
oc set env deploy/veracode LOGGING_FILE_PATH-                  # 去掉日志路径，看会不会因为写不了 ./logs 而报错
oc set image deploy/veracode veracode=image-registry.openshift-image-registry.svc:5000/creed-lab/veracode:nope   # ImagePullBackOff
oc set probe deploy/veracode --readiness --get-url=http://:18097/wrong    # 0/1 READY，Route 返回 503
oc set resources deploy/veracode --limits=memory=200Mi                     # OOMKilled
# 每制造一个故障，都按「事件 → describe → logs → debug」的顺序排查，然后用 oc rollout undo 恢复
```

### 2. 用户工作负载监控（kubeadmin）

```bash
cat <<'EOF' | oc apply -f -
apiVersion: v1
kind: ConfigMap
metadata: { name: cluster-monitoring-config, namespace: openshift-monitoring }
data:
  config.yaml: |
    enableUserWorkload: true
EOF
oc get pod -n openshift-user-workload-monitoring -w
```

```yaml
# openshift-learning/manifests/veracode/base/servicemonitor.yaml（developer 需要 monitoring-edit 角色才能创建）
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: { name: veracode }
spec:
  selector:
    matchLabels: { app: veracode }    # Service 本身也要带这个标签
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
```

```bash
oc label svc veracode app=veracode --overwrite
oc policy add-role-to-user monitoring-edit developer -n creed-lab   # kubeadmin 执行
oc apply -f openshift-learning/manifests/veracode/base/servicemonitor.yaml
```

在 Console → Developer → **Observe → Metrics** 中查询：
`sum by (pod) (rate(http_server_requests_seconds_count{namespace="creed-lab"}[1m]))`。
这些指标名来自 Micrometer 的 Prometheus 导出。**不要凭经验猜指标名**，先执行
`oc port-forward svc/veracode 18097:8080` 和 `curl -s localhost:18097/actuator/prometheus | grep http_server`
看实际的名字。（运行时镜像里不一定有 curl，所以用 port-forward 在本机查看。）

## 检查点

- [ ] 第 1 步的四个故障，每个都能在 3 条命令以内定位原因
- [ ] 在 Console 里查到了按 Pod 区分的请求速率；第 06 章 roundrobin 实验时，三个 Pod 的速率曲线基本一致
- [ ] 能区分 `oc logs` 和 `oc logs --previous` 分别在什么时候用

## 常见坑

- **只看 `oc logs`**：容器已经重启过的话，当前日志里没有崩溃原因，要加 `--previous`。
- **ServiceMonitor 不生效**：选择的是 **Service 的标签**（不是 Pod 的），而且 `port` 写的是 Service 端口的**名字**。
- **CRC 上开启监控之后内存吃紧**：做完本章，如果后面不需要监控，可以执行 `crc config set enable-cluster-monitoring false` 并重启 CRC。

## 参考文档

- OCP 文档：*Support → Troubleshooting*，以及 *Nodes → Working with pods → Viewing logs / Debugging*
- OCP 文档：*Monitoring → Enabling monitoring for user-defined projects*、*Managing metrics*
- Prometheus Operator 的 ServiceMonitor API：https://prometheus-operator.dev/
