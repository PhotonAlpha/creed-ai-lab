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

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。

### 实验 1：`oc debug` 到底创建了什么

```bash
oc debug deploy/veracode --keep-labels=false -- sleep 600 &
sleep 10; DBG=$(oc get pod -o name | grep debug)
diff <(oc get deploy veracode -o jsonpath='{.spec.template.spec.containers[0]}' | python3 -m json.tool) \
     <(oc get $DBG -o jsonpath='{.spec.containers[0]}' | python3 -m json.tool)
oc get $DBG --show-labels
kill %1
```

**观察**：
- 调试 Pod 复制了镜像、环境变量和卷，但**去掉了探针**，命令换成了你指定的命令，并且**不带原来的 label**。
- 所以它不会被 Service 选中，不会接到流量，也不会因为探针失败被重启。
- 这正是排查「启动即崩溃」时需要的：进入一个和出事 Pod 环境完全一样、但不会自己跑起来的容器，手动执行启动命令，看它报什么错。

### 实验 2：退出码就是第一条线索

```bash
oc get pod -l app=veracode -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.status.containerStatuses[0].lastState.terminated.exitCode}{"  "}{.status.containerStatuses[0].lastState.terminated.reason}{"\n"}{end}'
```

| 退出码 | 含义 | 常见原因 |
|---|---|---|
| 0 | 正常退出 | 进程跑完了。对 Web 服务来说通常意味着入口命令写错了 |
| 1 | 应用自己报错退出 | Spring 启动失败，看 `oc logs --previous` |
| 137 | 128+9，SIGKILL | `OOMKilled`；或者优雅停机超过宽限期（第 09 章实验 3） |
| 143 | 128+15，SIGTERM | 正常的终止流程 |

用第 03 章实验 7 和第 09 章实验 3 分别制造 137，对比 `reason` 字段：前者是 `OOMKilled`，后者通常是 `Error`。退出码相同，原因完全不同。

### 实验 3：日志在节点上的什么地方（kubeadmin）

```bash
POD=$(oc get pod -l app=veracode -o jsonpath='{.items[0].metadata.name}')
NODE=$(oc get pod $POD -o jsonpath='{.spec.nodeName}')
oc debug node/$NODE -- chroot /host sh -c "ls /var/log/pods/ | grep veracode; ls /var/log/pods/creed-lab_${POD}_*/veracode/"
```

**解释**：
- 容器的 stdout/stderr 由 CRI-O 写到节点的 `/var/log/pods/<ns>_<pod>_<uid>/<container>/N.log`，N 是重启次数。
- `oc logs` 读的是当前这个文件，`--previous` 读的是上一个。Pod 被删除后目录也跟着删掉，日志就没了。
  所以生产环境需要日志收集（OpenShift Logging、Loki 等），在日志被删之前把它运走。
- 写到 `LOGGING_FILE_PATH` 里的文件日志不在这里，`oc logs` 看不到。它在容器自己的文件系统里，容器重建后也会丢失。

### 实验 4：不重启改日志级别，抓线程栈

```bash
oc port-forward deploy/veracode 18097:18097 &
curl -s localhost:18097/actuator/loggers/com.creed.resource.veracode
curl -s -XPOST localhost:18097/actuator/loggers/com.creed.resource.veracode -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
curl -s -XPOST localhost:18097/api/veracode/analysis -H 'Content-Type: application/json' -d '{}' >/dev/null
oc logs deploy/veracode --since=30s | grep -c DEBUG
curl -s localhost:18097/actuator/threaddump -H 'Accept: text/plain' | head -30
oc exec deploy/veracode -- kill -3 1; oc logs deploy/veracode --since=10s | grep -m3 '"http-nio'
kill %1
```

**解释**：
- `/actuator/loggers` 直接修改运行中的 Logback 配置，不需要重启。这和第 03 章「配置只在启动时读一次」形成对比：
  Spring 的少数组件提供了运行时修改接口，大部分配置没有。
- `kill -3`（SIGQUIT）让 JVM 把线程栈打印到 stdout，所以能在 `oc logs` 里看到。这是镜像里没有 jcmd/jstack 时最可靠的办法。
- 改的级别只在**这一个 Pod** 里生效，多副本时要逐个改，Pod 重启后也会恢复。

### 实验 5：Prometheus 给指标加了哪些标签

在 Console → Observe → Metrics 中分别查询：

```promql
up{namespace="creed-lab"}
http_server_requests_seconds_count{namespace="creed-lab"}
```

**观察**：
- 每个序列都带着 `namespace`、`pod`、`service`、`job`、`endpoint`、`instance` 这些标签，而应用自己导出的指标里**没有**它们。
- 这些标签是 Prometheus 采集时根据 ServiceMonitor 的服务发现结果加上的：先从 Service 找到 EndpointSlice，再找到每个 Pod。
- 对照 `/actuator/prometheus` 的原始输出，能看到应用自己带的 `application` 标签（来自 `creed-common-metrics`）。

**反向验证**：把 ServiceMonitor 的 `port` 写成 `8080`（数字，而不是名字 `http`），等一两分钟，`up` 序列消失。
`port` 字段匹配的是 Service 端口的**名字**。

### 实验 6：事件不是永久保存的

```bash
oc get events --sort-by=.lastTimestamp | head -3          # 最早的一条有多久了
oc get events -o jsonpath='{.items[0].metadata.creationTimestamp}'; echo
```

**解释**：
- 事件是普通的 API 对象，但 API Server 会在一段时间后（默认几个小时）自动删除它们。
- 所以「昨天晚上 Pod 重启过」这种问题，`oc get events` 是回答不了的，要靠监控里的
  `kube_pod_container_status_restarts_total` 或者日志系统。
- 同一个事件重复发生时只更新计数（`count`、`lastTimestamp`），不会新建对象。

### 检查点（机制）

- [ ] 用 diff 看出了 `oc debug` 去掉了哪些字段
- [ ] 制造了两种退出码为 137 的情况，并用 `reason` 区分开
- [ ] 在节点上找到了容器的日志文件，并说出 `--previous` 读的是哪个文件
- [ ] 不重启 Pod，把日志级别改成了 DEBUG
- [ ] 说出 `pod`、`namespace` 标签是谁加上去的

## 常见坑

- **只看 `oc logs`**：容器已经重启过的话，当前日志里没有崩溃原因，要加 `--previous`。
- **ServiceMonitor 不生效**：选择的是 **Service 的标签**（不是 Pod 的），而且 `port` 写的是 Service 端口的**名字**。
- **CRC 上开启监控之后内存吃紧**：做完本章，如果后面不需要监控，可以执行 `crc config set enable-cluster-monitoring false` 并重启 CRC。

## 参考文档

- OCP 文档：*Support → Troubleshooting*，以及 *Nodes → Working with pods → Viewing logs / Debugging*
- OCP 文档：*Monitoring → Enabling monitoring for user-defined projects*、*Managing metrics*
- Prometheus Operator 的 ServiceMonitor API：https://prometheus-operator.dev/
