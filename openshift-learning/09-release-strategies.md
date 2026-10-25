# 09 发布策略：滚动、Recreate、蓝绿、金丝雀、回滚

## 目标

- 在持续压测的情况下完成一次**零错误**的滚动发布，并且能解释是哪些配置保证了零错误。
- 分别用 Route 切换和 Route 权重实现蓝绿发布与金丝雀发布。
- 会两种回滚：`oc rollout undo` 和 Git revert。在 GitOps 模式下，只有后者是正确的做法。

## 知识点

| 策略 | 实现 | 优点 | 代价 |
|---|---|---|---|
| **RollingUpdate**（默认） | `maxSurge` / `maxUnavailable` 控制节奏，依赖 readinessProbe | 不需要额外资源，零停机 | 新旧两个版本会**同时在线**，接口必须向后兼容 |
| **Recreate** | 先全部停掉，再全部启动 | 永远不会出现两个版本同时运行（例如数据库结构不兼容） | 有停机时间 |
| **蓝绿** | 两套 Deployment + Service；Route 的 `spec.to` 一次性切换过去 | 切换和回切都是秒级 | 资源翻倍 |
| **金丝雀** | Route 的 `alternateBackends` 按权重分配（第 06 章） | 风险可控，可以逐步放量 | 要靠监控判断能不能继续放量；会话粘滞会让权重显得不准 |

- **零停机滚动发布需要三件事**：
  1. readinessProbe 正确（新 Pod 就绪之后才接流量）；
  2. **优雅停机**：收到 SIGTERM 后先停止接收新请求，再处理完正在进行的请求；
  3. `terminationGracePeriodSeconds` 足够长。

  Spring Boot 默认开启 `server.shutdown=graceful`，默认等待 30s，可以用 `spring.lifecycle.timeout-per-shutdown-phase` 调整。
- **端点摘除和 SIGTERM 是并行发生的**：路由器更新端点列表需要一点时间，所以常常加一个 `preStop` 的 `sleep 5-10`，让流量先切走，再开始停机。
- **金丝雀发布和 veracode 的上传**：粘滞 cookie 会把用户固定在 v1 或 v2 上，同一个用户的「上传 → 建单」不会跨版本。
  这是好事，但也意味着流量比例是按用户（cookie）计算的，而不是按请求。

## 步骤

### 1. 带压测的滚动发布

```bash
# 终端 A：持续请求，打印状态码分布（每秒约 10 个请求）
HOST=$(oc get route veracode -o jsonpath='{.spec.host}')
while true; do curl -sk -o /dev/null -w '%{http_code}\n' https://$HOST/api/veracode/settings; sleep 0.1; done | uniq -c

# 终端 B：触发一次发布（改一个环境变量，就会生成新的 ReplicaSet）
oc set env deploy/veracode RELEASE_MARK=$(date +%s)
oc rollout status deploy/veracode
```

终端 A 应该只出现 200。接着做对照实验：去掉 readinessProbe（`oc set probe deploy/veracode --readiness --remove`），再发布一次，观察是否出现 502 或 503。

```bash
# 加上 preStop，配合优雅停机
oc patch deploy/veracode --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/lifecycle","value":{"preStop":{"exec":{"command":["sh","-c","sleep 5"]}}}}]'
oc patch deploy/veracode -p '{"spec":{"strategy":{"rollingUpdate":{"maxSurge":1,"maxUnavailable":0}}}}'
```

### 2. 蓝绿发布

```bash
# veracode（蓝）正在接流量；部署 veracode-green（绿），复用第 06 章 v2 的创建方式
oc patch route veracode -p '{"spec":{"to":{"name":"veracode-green"}}}'   # 一次性切换
oc patch route veracode -p '{"spec":{"to":{"name":"veracode"}}}'         # 回切
```

### 3. 金丝雀发布：逐步放量

```bash
for w in 10 30 50 100; do
  oc set route-backends veracode veracode=$((100-w)) veracode-v2=$w
  echo "v2=$w%"; sleep 60    # 每一档都观察错误率和延迟（第 10 章的监控），没问题再进入下一档
done
```

### 4. 回滚

```bash
oc rollout history deploy/veracode
oc rollout undo deploy/veracode                  # 回到上一个版本（手工管理时用）
# GitOps 模式下：git revert 掉那次修改镜像标签的提交并推送，由 Argo CD 同步。
# 直接执行 rollout undo 会被 selfHeal 改回去
```

## 检查点

- [ ] 带 readinessProbe、preStop 和 `maxUnavailable: 0` 时，发布过程中终端 A 只出现 200
- [ ] 去掉 readinessProbe 之后，复现了发布期间的错误
- [ ] 蓝绿切换后，`/actuator/env/HOSTNAME` 立即全部变成 green 的 Pod
- [ ] 能解释：GitOps 模式下 `oc rollout undo` 为什么「回滚了但又自己变回去了」

## 深入练习：看清机制

格式：**要弄清的问题 → 命令 → 观察 / 解释**。

### 实验 1：maxSurge / maxUnavailable 的算术

```bash
oc scale deploy/veracode --replicas=4
oc get deploy veracode -o jsonpath='{.spec.strategy}'; echo         # 默认 25% / 25%
oc get rs -l app=veracode -w &
oc set env deploy/veracode RELEASE_MARK=$(date +%s)
oc rollout status deploy/veracode; kill %1
```

**解释**：
- 4 个副本时：`maxSurge = ceil(4 × 25%) = 1`（最多 5 个 Pod），`maxUnavailable = floor(4 × 25%) = 1`（至少 3 个可用）。
  注意一个向上取整、一个向下取整。
- `-w` 的输出里能看到新 RS 每次 +1，旧 RS 每次 −1，交替进行，总数一直在 3–5 之间。
- 把策略改成 `maxSurge: 1, maxUnavailable: 0` 再发布一次：旧 RS 必须等新 Pod **就绪**后才能缩容。
  所以 readinessProbe 不只决定流量，也决定滚动发布的节奏。

### 实验 2：滚动发布卡住时会发生什么

```bash
oc patch deploy veracode -p '{"spec":{"progressDeadlineSeconds":60}}'
oc set image deploy/veracode veracode=image-registry.openshift-image-registry.svc:5000/creed-lab/veracode:nope
sleep 75; oc get deploy veracode -o jsonpath='{.status.conditions[?(@.type=="Progressing")]}' | python3 -m json.tool
oc get pod -l app=veracode                   # 老 Pod 依然在提供服务
oc rollout undo deploy/veracode
```

**观察**：
- `Progressing` 条件变成 `False`，原因是 `ProgressDeadlineExceeded`。
- 但 **Deployment 不会自动回滚**，它只是把状态标记出来，旧 Pod 继续提供服务。`maxUnavailable` 保证了这一点。
- 自动回滚要靠上层工具：CI 根据 `oc rollout status` 的退出码执行 undo，或者使用 Argo Rollouts。

### 实验 3：优雅停机的时间线

```bash
POD=$(oc get pod -l app=veracode -o name | head -1)
oc logs -f $POD --timestamps &
date -u +%T; oc delete $POD --wait=false
sleep 20; kill %1
```

**观察**：
- 先是 preStop 的 sleep（如果配置了）：这几秒里日志没有任何输出。
- 然后 JVM 收到 SIGTERM，Spring 打印 `Commencing graceful shutdown. Waiting for active requests to complete`，
  接着是 `Graceful shutdown complete`，然后进程退出。
- 把时间戳对齐：**delete → preStop 结束 → SIGTERM → 停机完成** 这几段时间加起来，必须小于 `terminationGracePeriodSeconds`。

**反向验证**：宽限期小于 preStop 时间。

```bash
oc patch deploy veracode -p '{"spec":{"template":{"spec":{"terminationGracePeriodSeconds":3}}}}'
# preStop 是 sleep 5：宽限期到了，进程直接被 SIGKILL，日志里不会出现 Graceful shutdown complete
oc patch deploy veracode -p '{"spec":{"template":{"spec":{"terminationGracePeriodSeconds":30}}}}'
```

> 新版本 Kubernetes 支持原生的 `lifecycle.preStop.sleep: {seconds: 5}`，不再要求镜像里有 `sleep` 命令。
> 用 `oc explain deployment.spec.template.spec.containers.lifecycle.preStop` 确认你的集群支持哪种写法。

### 实验 4：为什么需要 preStop

把实验 3 和第 05 章实验 4 对照起来看。删除 Pod 时，有两件事**同时**开始：

1. kubelet 执行 preStop，然后给进程发 SIGTERM；
2. EndpointSlice 把这个端点标记为终止中，再由路由器和 OVN 各自更新自己的后端列表。

第 2 件事要经过好几个组件，需要时间。没有 preStop 的话，应用可能在路由器还没摘掉它时就停止接受新连接了，
这段间隙里进来的请求会失败。preStop 的 sleep 并不是让应用做什么，**而是让应用晚一点开始停机**，等流量先切走。

用本章步骤 1 的压测循环对比有无 preStop 的情况：在 4 副本、频繁发布的条件下，没有 preStop 时偶尔会出现 502/503。

### 实验 5：Recreate 的停机窗口

```bash
oc patch deploy veracode --type=merge -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
# 终端 A 跑步骤 1 的压测循环；终端 B：
oc set env deploy/veracode RELEASE_MARK=$(date +%s)
oc patch deploy veracode --type=merge -p '{"spec":{"strategy":{"type":"RollingUpdate"}}}'
```

**观察**：终端 A 出现一段连续的 503，持续时间大约等于「旧 Pod 停机 + 新 Pod 启动 + 就绪」。这就是 Recreate 的代价，换来的是「新旧版本永不同时运行」。

### 实验 6：回滚的本质是「换一个旧的 Pod 模板」

```bash
oc rollout history deploy/veracode
oc rollout history deploy/veracode --revision=2          # 看那个版本的模板
oc get rs -l app=veracode -o custom-columns=NAME:.metadata.name,REV:.metadata.annotations.deployment\\.kubernetes\\.io/revision,DESIRED:.spec.replicas
oc rollout undo deploy/veracode --to-revision=2
oc get rs -l app=veracode -o custom-columns=NAME:.metadata.name,REV:.metadata.annotations.deployment\\.kubernetes\\.io/revision,DESIRED:.spec.replicas
```

**观察**：
- 每个版本就是一个保留下来的 ReplicaSet，`revision` 写在它的注解里。
- 回滚后，被选中的那个旧 RS 的 revision 号变成了**最新**的号：回滚本身也是一次新的发布。
- 保留多少个版本由 `revisionHistoryLimit` 决定（默认 10），超出的旧 RS 会被清理，也就回滚不到了。

### 检查点（机制）

- [ ] 算出了 4 副本时 maxSurge 和 maxUnavailable 的实际值，并在 `-w` 输出里验证
- [ ] 看到 `ProgressDeadlineExceeded`，并确认 Deployment 没有自动回滚
- [ ] 对齐了优雅停机各阶段的时间戳，复现了宽限期不足时的 SIGKILL
- [ ] 能用「两件事同时开始」解释 preStop 存在的理由

## 常见坑

- **只依赖默认的优雅停机，但 Pod 的终止宽限期太短**：`terminationGracePeriodSeconds` 默认 30s，
  它必须大于 preStop 时间加上应用停机超时，否则进程会被 SIGKILL 强杀。
- **数据库结构变更搭配滚动发布**：新旧版本同时在线，新版本加了一列 NOT NULL 字段，旧版本的写入就会失败。
  要么采用「扩展 → 迁移 → 收缩」的分步变更，要么改用 Recreate。
- **金丝雀发布没有观测**：没有按版本区分的错误率指标，放量就是在赌运气。先完成第 10 章再认真做金丝雀发布。

## 参考文档

- OCP 文档：*Building applications → Deployments*，重点看 *Using deployment strategies* 和 *Using route-based deployment strategies*（蓝绿、A/B）
- Kubernetes 文档：*Deployments → Strategy*；*Pod Lifecycle → Termination of Pods*
- Spring Boot 文档：*Web → Graceful Shutdown*
- （可选）Argo Rollouts（渐进式发布控制器）：https://argoproj.github.io/rollouts/
