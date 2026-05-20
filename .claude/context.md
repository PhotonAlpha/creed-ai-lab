# Session Context — spring-creed-auth-server

> 会话快照，便于后续 Claude Code 会话快速建立上下文。
> 生成日期：2026-05-21
> 用户：ethan (ethan.caoq@gmail.com)

---

## 1. 会话元信息

| 项 | 值 |
|---|---|
| 日期 | 2026-05-21 |
| 平台 | Windows 11 (PowerShell + Bash via WSL) |
| 工作目录 | `D:\workspace\source\ai-lab\spring-creed-auth-server` |
| Git 分支 | `master`（主分支同名） |
| 当前打开文件 | `creed-author-server/src/main/resources/application.yml` |
| Git 用户 | ethan |

### Git 状态（会话开始时）
- `M pom.xml`（修改但未提交，diff 为空——可能是行尾符差异）
- 未跟踪：`.claude/`、`creed-report/`

### 最近提交
```
fb714cd generate HTML template
6f48e26 generate HTML template
f87a6cb add grafana
831f901 add grafana
ea8513b init project
8a39e3a init project
386fbb3 init
```

---

## 2. 项目概览

**spring-creed-auth-server** — 一个基于 Spring Boot 3.5 / Spring Cloud 2025 的多模块 OAuth2 技术栈，包含授权服务器、网关、资源服务和报告模块。

### 技术栈
- Java 21
- Spring Boot 3.5.14
- Spring Cloud 2025.0.2
- Maven (多模块, packaging=pom)
- 监控：Micrometer + Prometheus + Grafana

### 顶层结构
```
spring-creed-auth-server/
├── pom.xml                    # 父 POM，统一依赖管理
├── .gitignore                 # 保留 .idea/http-client 和 .idea/runConfigurations
├── creed-author-server/       # OAuth2 / OIDC 授权服务器 (war)
├── creed-gateway/             # Spring Cloud Gateway
├── creed-resource/            # 资源服务（聚合）
│   ├── creed-resource-catalog/
│   └── creed-resource-order/
├── creed-report/              # 报告模块（HTML 模板生成）
└── monitoring/                # Prometheus + Grafana docker-compose
    ├── docker-compose.yml
    ├── prometheus.yml
    ├── dashboards/
    └── provisioning/
```

---

## 3. 各模块速览

### 3.1 creed-author-server （授权服务器）
- **打包**：war
- **端口**：9000，context-path `/auth-server`
- **依赖**：spring-boot-starter-oauth2-authorization-server, security, web, actuator, micrometer-registry-prometheus
- **关键源文件**：
  - `com.creed.auth.CreedAuthorServerApplication`
  - `com.creed.auth.config.AuthorizationServerConfiguration`
  - `com.creed.auth.config.ActuatorSecurityConfiguration`
  - `com.creed.auth.metrics.JvmMemoryMetricsLogger`
- **配置要点**（`application.yml`）：
  - Actuator 全端点暴露（web + jmx），prometheus 端点无限制访问
  - HTTP server 请求开启百分位直方图
  - 自定义命名空间 `creed.metrics.jvm-memory`：initial-delay=10s, interval=30s

### 3.2 creed-gateway
- Spring Cloud Gateway（待补充细节）

### 3.3 creed-resource （资源服务聚合）
- **creed-resource-catalog**：`CatalogController` + JWT 解码缓存配置 + Security 配置
- **creed-resource-order**：`OrderController` + 同上结构
- 两个子模块共享同样的安全模式：`CachedJwtDecoderConfiguration` + `SecurityConfiguration`

### 3.4 creed-report
- **关键源文件**：
  - `com.creed.report.CreedReportApplication`
  - `com.creed.report.web.ReportController`
  - `com.creed.report.service.ServerInfoService` / `AssetService`
  - `com.creed.report.model.ServerInfo`
- 用途：生成服务器信息相关的 HTML 报告

### 3.5 monitoring
- Docker Compose 编排 Prometheus + Grafana
- Grafana 已通过 provisioning 目录预置 dashboards

---

## 4. 已知工作目标 / 上下文线索

本次会话开始时尚无明确开发任务；唯一明确的用户请求是「将此会话的上下文保存到项目的 .claude」。

用户在 IDE 中打开了 `creed-author-server/src/main/resources/application.yml`，可能预示后续会涉及授权服务器的配置调整或监控指标相关工作。

`pom.xml` 显示未提交修改（diff 为空），值得在下次会话开始时确认是否为遗留状态。

---

## 5. 现有 .claude 配置

`.claude/settings.local.json` 仅授权了一条 Bash 命令：
```
curl -s "http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Bapplication%3D%22creed-author-server%22%7D"
```
说明此前曾在本地用 Prometheus（9090）查询授权服务器的 JVM 内存指标，与 `JvmMemoryMetricsLogger` + monitoring 模块一致。

---

## 6. 后续会话使用建议

- 先读取本文件 + `MEMORY.md`，再决定是否需要 `git status` / `git log` 刷新状态
- 配置类改动优先在 `creed-author-server/src/main/resources/application.yml` 验证
- 监控相关变更需同步 `monitoring/prometheus.yml` 和 Grafana provisioning
- 资源模块新增功能时，catalog/order 通常对称演进
