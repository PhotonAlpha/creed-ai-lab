# OpenShift（OCP）从 0 到 CI/CD 与负载均衡 —— 学习路线

在本机 **OpenShift Local（CRC 2.64，OpenShift 4.22.14，Apple Silicon / arm64）** 上，从「集群起得来」
一路做到「push 代码 → 流水线构建 → GitOps 部署 → 多副本负载均衡 → 灰度发布」。

练习用的应用是本仓库的 **`creed-resource/creed-resource-veracode`**，选它有三个原因：

- 明文 HTTP、无数据库、不需要 mTLS PKI，容器化门槛最低；
- actuator 暴露了全部端点：`/actuator/health/{liveness,readiness}` 用作探针，`/actuator/prometheus` 用于监控，
  `/actuator/env/HOSTNAME` 能直接看出这次请求落在哪个 Pod 上；
- **上传的报告只存在进程内存里**（`UploadedReportStore`）。多副本、没有会话粘滞时，「上传 → 建单」会随机返回
  `upload_expired`。这是一个真实可复现的负载均衡问题，第 05、06 章会用它做实验。

> 这些文档写于 2026-10-16，写作时 CRC 虚拟机处于停止状态。文中命令按 OCP 4.22 与 CRC 2.64 的行为编写，
> **尚未在本机集群上逐条跑过**。版本差异以 `oc explain`、`oc get ... -o yaml` 和官方文档为准，跑通后请在对应章节的
> 「检查点」处打勾并修正。

## 路线总览

```
00 环境 ─► 01 核心概念 ─► 02 容器化并部署 ─► 03 配置/探针/资源 ─► 04 集群内构建
                                                                          │
      ┌───────────────────────────────────────────────────────────────────┘
      ▼
05 负载均衡 I（Service） ─► 06 负载均衡 II（Route / HAProxy） ─► 07 CI：Tekton
                                                                       │
      ┌────────────────────────────────────────────────────────────────┘
      ▼
08 CD：GitOps（Argo CD） ─► 09 发布策略（滚动/蓝绿/金丝雀/回滚） ─► 10 可观测与排错 ─► 11 进阶
```

| # | 章节 | 你会得到什么 | 预计 |
|---|---|---|---|
| 00 | [环境准备](00-environment.md) | CRC 调好资源、能登录 console 和 `oc` | 0.5 天 |
| 01 | [核心概念](01-core-concepts.md) | Project / Pod / Deployment / Service / Route / SCC 的心智模型 | 1 天 |
| 02 | [容器化并部署第一个应用](02-containerize-and-deploy.md) | veracode 镜像，在集群里跑起来并从浏览器访问 | 1 天 |
| 03 | [配置、探针、资源](03-config-probes-resources.md) | ConfigMap/Secret、三种探针、requests/limits、HPA | 1 天 |
| 04 | [集群内构建](04-builds-and-imagestreams.md) | BuildConfig（Docker / Binary / S2I）、ImageStream 触发 | 1 天 |
| 05 | [负载均衡 I：Service](05-lb-service.md) | 连接级负载均衡、Endpoints、就绪摘除、ClientIP 亲和 | 0.5 天 |
| 06 | [负载均衡 II：Route / HAProxy](06-lb-route-haproxy.md) | TLS 三种终止方式、算法、粘滞 cookie、超时、权重分流 | 1.5 天 |
| 07 | [CI：OpenShift Pipelines（Tekton）](07-ci-tekton.md) | clone → test → 构建镜像 → 推送，触发器模拟 webhook | 2 天 |
| 08 | [CD：OpenShift GitOps（Argo CD）](08-cd-gitops.md) | Kustomize 分环境，Git 即真相，漂移自愈 | 1.5 天 |
| 09 | [发布策略](09-release-strategies.md) | 滚动、Recreate、蓝绿、金丝雀、回滚 | 1 天 |
| 10 | [可观测与排错](10-observability-troubleshooting.md) | 日志/事件/调试 Pod、用户工作负载监控 | 1 天 |
| 11 | [进阶](11-advanced.md) | NetworkPolicy、配额、service-serving 证书、mTLS passthrough、多服务 | 按需 |
| — | [命令速查](cheatsheet.md) | 常用 `oc` / `tkn` / `crc` 命令 | — |

## 每章的固定结构

1. **目标**：做完这一章能说清楚或者做出来的东西。
2. **知识点**：需要理解的概念。尤其是 OpenShift 和原生 Kubernetes 不一样的地方，这些都会单独标出。
3. **步骤**：可以照着敲的命令。
4. **检查点**：怎么确认这一步真的做对了，而不只是「命令没报错」。
5. **深入练习：看清机制**：每个实验都按「要弄清的问题 → 命令 → 观察 / 解释」的格式写，
   目的是看到机制本身，而不只是看到效果。例如：ClusterIP 在 OVN 里是什么、粘滞 cookie 里存的是什么、
   Tekton 怎么让一个 Pod 里的容器按顺序执行。很多实验会故意把东西弄坏，再从现象倒推原因。
   第 03 章另有三个落到 veracode 代码里的进阶练习（HTTPS + keystore 密码、ConfigMap 配置目录、actuator 值来源与脱敏）。
6. **常见坑**：这个仓库的应用在 OCP 上一定会踩到的坑（例如随机 UID、可执行 jar、OTel 导出）。
7. **参考文档**：官方文档的入口和对应章节名。

## 产出物放在哪

- `manifests/`：学习过程中写出来的 YAML（Deployment、Service、Route、Pipeline、Kustomize overlay 等）。
  第 08 章 Argo CD 会直接从这里同步，所以**它必须提交到 Git**。
- 每章末尾都有「检查点」清单，完成一项就把 `- [ ]` 改成 `- [x]`，这份文档本身就是学习进度表。

## 进度

- [ ] 00 环境准备
- [ ] 01 核心概念
- [ ] 02 容器化并部署
- [ ] 03 配置、探针、资源
- [ ] 04 集群内构建
- [ ] 05 负载均衡 I：Service
- [ ] 06 负载均衡 II：Route / HAProxy
- [ ] 07 CI：Tekton
- [ ] 08 CD：GitOps
- [ ] 09 发布策略
- [ ] 10 可观测与排错
- [ ] 11 进阶

## 总的参考入口

| 内容 | 地址 |
|---|---|
| OpenShift Local（CRC） | https://crc.dev/ |
| OpenShift Container Platform 文档（选 4.22 版本） | https://docs.redhat.com/en/documentation/openshift_container_platform/ |
| Red Hat OpenShift Pipelines 文档 | https://docs.redhat.com/en/documentation/red_hat_openshift_pipelines/ |
| Red Hat OpenShift GitOps 文档 | https://docs.redhat.com/en/documentation/red_hat_openshift_gitops/ |
| Tekton 上游文档 | https://tekton.dev/docs/ |
| Argo CD 上游文档 | https://argo-cd.readthedocs.io/ |
| Kubernetes 文档 | https://kubernetes.io/docs/ |
| Spring Boot：部署到云 / Kubernetes 探针 | https://docs.spring.io/spring-boot/how-to/deployment/cloud.html |
| Red Hat Developer 免费 OpenShift 学习课程 | https://developers.redhat.com/learn/openshift |
