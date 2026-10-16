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
