# manifests —— 学习过程中产出的 YAML

按章节逐步写进来。从第 08 章起，Argo CD 直接从这里同步，所以目录结构要固定：

```
manifests/
├── veracode/
│   ├── Dockerfile                 # 第 02 章
│   ├── Dockerfile.multistage      # 第 04 章（可选）
│   ├── base/                      # 第 02/03/10 章：deployment / service / route / servicemonitor + kustomization.yaml
│   └── overlays/{dev,prod}/       # 第 08 章
├── pipeline/                      # 第 07 章：pipeline.yaml、triggers.yaml
└── argocd/                        # 第 08 章：Application
```

规则：

- **不提交 Secret 的明文**（pull secret、GitHub PAT、Jira token）。Secret 用 `oc create secret` 在集群里创建，
  或者见第 11.4 节的方案。
- 从 `oc get -o yaml` 导出的内容要清理掉 `status`、`metadata.uid`、`resourceVersion`、`creationTimestamp`、
  `managedFields` 再提交，否则 Argo CD 会一直显示有差异。
