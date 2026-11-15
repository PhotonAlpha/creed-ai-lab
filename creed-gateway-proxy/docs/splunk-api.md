# Splunk 会话代理 API

`creed-gateway-proxy` 在 `/api/env-matrix/splunk/*` 上直接应答（controller 优先于网关的 `/api/**` 路由），
其余 `/api/*` 才转发到 env-matrix 后端。分两组：

| 组 | 路径 | 谁用 | 认证 |
|---|---|---|---|
| 页面 API | `/api/env-matrix/splunk/{totp,session,audit}` | `/splunk` 页面 | 无（OTP 只控制签发会话） |
| 管理 API | `/api/env-matrix/splunk/admin/**` | 运维脚本 / curl，**页面不调用、不显示** | `Authorization: Bearer <SPLUNK_ADMIN_TOKEN>` |

错误统一是这个形状（`SplunkApiErrors`）：

```json
{ "error": "not_available", "message": "…", "fields": [ … ], "time": "2026-11-15T12:57:45Z" }
```

`fields` 只在请求体字段校验失败时出现。成功响应都带 `Cache-Control: no-store`。

---

## 页面 API

### `GET /api/env-matrix/splunk/totp`

OTP 参数、服务器时间、登录目标、禁止时段、当前客户端。

```json
{
  "configured": true, "periodSeconds": 30, "digits": 6, "allowedDriftSteps": 5,
  "serverTimeMillis": 1794746877622, "codeVisible": true,
  "splunkMode": "mock", "splunkConfigured": true,
  "defaultTarget": "UAT", "scriptCookieName": "splunkd_8089",
  "block": { "windows": ["22:00-09:00"], "zone": "Asia/Shanghai", "blocked": false,
             "changesAtMillis": 1794751200000, "reason": null },
  "targets": [
    { "id": "SIT", "label": "SIT", "loginUrl": "…", "username": "admin", "passwordSet": false,
      "configured": true, "sessionCookie": "splunkd_8000", "scriptCookieName": "splunkd_8089",
      "scriptCookiePath": "/", "tunnel": null, "tunnelDefault": false,
      "block": { "windows": [], "zone": "Asia/Shanghai", "blocked": true,
                 "changesAtMillis": 1794830400000, "reason": "not_available" } }
  ],
  "client": { "ip": "10.1.2.3", "host": "pc-0042.corp.example" }
}
```

- 顶层 `block`：全局 `creed.splunk.block`，即没有自己配置的 target 所用的默认值。
- `targets[].block`：该 target **实际生效**的情况：
  - `windows`：它的禁止时段，自己配置了就用自己的，否则用全局的；
  - `blocked`：当前是否拒绝签发；
  - `reason`：拒绝的原因，`blocked` 表示处于禁止时段，`not_available` 表示不在允许时段内；
  - `changesAtMillis`：下次变化的时间，按服务器时钟，`null` 表示永不变化。
- **允许时段本身从不出现在这里**：页面只知道 target 当前不可用、以及何时恢复。
- `client`：服务器看到的 TCP 对端 IP，以及经正向解析确认的反向 DNS 主机名（没有时为 `null`）。前面有代理时显示的是代理。

### `GET /api/env-matrix/splunk/totp/current`

当前验证码（`creed.totp.expose-current-code=true` 时提供，否则 `404 code_hidden`）。

### `POST /api/env-matrix/splunk/session`

```json
{ "code": "123456", "target": "SIT", "sessionCookie": null, "scriptCookieName": null, "viaTunnel": null }
```

| 状态 | `error` | 说明 | 验证码 |
|---|---|---|---|
| 200 | — | 返回 `document.cookie` 脚本、指纹、关联 ID | 已用 |
| 400 | `unknown_target` / `no_tunnel` / `validation_failed` | | 未用 |
| 403 | `blocked` | 处于该 target 的禁止时段；带 `Retry-After`，提示里列出时段 | 未用 |
| 403 | `not_available` | 不在该 target 的允许时段内；带 `Retry-After`，提示只说何时恢复 | 未用 |
| 503 | `not_configured` | target 或 TOTP 未配置 | 未用 |
| 401 | `otp_invalid` / `otp_replayed` | | 无效 / 已用 |
| 429 | `too_many_attempts` | 验证码错误过多 | — |
| 502 | `splunk_*` | Splunk 失败 | 已用 |

能够在不消耗验证码的情况下拒绝的检查，都放在校验验证码之前。

### `POST /api/env-matrix/splunk/session/{correlationId}/revoke`

用 Splunk Web logout 结束本服务签发的会话。返回 200，或 400、404 `not_revocable`、502。

### `GET /api/env-matrix/splunk/audit?limit=50`

最新的审计记录（最多 500 条），每行带 `clientIp`、`clientHost`。**不包含管理 API 的记录**（`ADMIN_*`）。

---

## 管理 API：运行时修改允许时段

### 开启

- 用 `SPLUNK_ADMIN_TOKEN` 环境变量，或 `SPLUNK_ADMIN_TOKEN_FILE`（存放 token 的文件）提供，至少 24 个字符。
- **不能**写在 yml 里，启动时也不会提示输入。
- 未提供时，下面所有路径都返回 `404`，就像不存在一样。
- 启动日志会写一行 `Splunk admin API (/api/env-matrix/splunk/admin/**): on|off`。

```bash
openssl rand -hex 32 > admin-token && chmod 600 admin-token
SPLUNK_ADMIN_TOKEN_FILE=$PWD/admin-token java -jar creed-gateway-proxy.jar
```

### 时段语法

和 `block.windows` 相同：用逗号分隔，每一项是以下两种之一，可以混写：

- 每日时段 `HH:mm-HH:mm`，结束早于开始时跨午夜；
- 日期区间 `yyyy-MM-dd HH:mm[:ss] - yyyy-MM-dd HH:mm[:ss]`，只生效一次，结束必须晚于开始。

开始时刻算在内，结束时刻不算；按该 target 的禁止时段时区（`block.zone`）解释。`""` 表示不限制。

允许时段与禁止时段的关系：**target 只在某个允许时段之内、并且不在任何禁止时段之内时才签发会话。**

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/env-matrix/splunk/admin/targets` | 全部 target 的允许时段 |
| `GET` | `/api/env-matrix/splunk/admin/targets/{id}/availability` | 一个 target 的允许时段 |
| `PUT` | `/api/env-matrix/splunk/admin/targets/{id}/availability` | 替换为 `{"windows": "…"}`，**立即生效** |
| `DELETE` | `/api/env-matrix/splunk/admin/targets/{id}/availability` | 恢复为 yml 中的 `targets[].allow` |

响应（`PUT` / `DELETE` / 单个 `GET`；列表是它的数组）：

```json
{
  "target": "SIT",
  "windows": "2026-11-16 20:00 - 2026-11-20 00:00",
  "zone": "Asia/Shanghai",
  "source": "api",
  "updatedAt": "2026-11-15T12:57:45.051517Z",
  "updatedBy": "10.1.2.3 (pc-0042.corp.example)",
  "available": false,
  "reason": "not_available",
  "changesAtMillis": 1794830400000
}
```

| 字段 | 含义 |
|---|---|
| `windows` | 当前生效的允许时段，`""` 表示不限制 |
| `source` | `config`（来自 yml）或 `api` |
| `updatedAt` / `updatedBy` | 最近一次通过 API 修改的时间与调用方；启动后未改过时为 `null` |
| `available` | 此刻能否签发会话（在允许时段内且不在禁止时段内） |
| `reason` | 不能签发的原因：`blocked` / `not_available`；能签发时为 `null` |
| `changesAtMillis` | `available` 下次变化的时间；`null` 表示不再变化，例如区间已经过去 |

| 状态 | `error` | 说明 |
|---|---|---|
| 200 | — | |
| 400 | `validation_failed` | 时段写错、结束早于开始，或缺少 `windows` |
| 401 | `unauthorized` | 没有 token 或 token 错误（响应带 `WWW-Authenticate: Bearer`） |
| 404 | `not_found` / `unknown_target` | 未开启管理 API / 没有这个 target |
| 429 | `too_many_attempts` | 同一 IP 5 分钟内错 5 次 token；带 `Retry-After`，在此期间正确的 token 也会被拒 |

### 例子

```bash
B=https://proxy-host:8088/api/env-matrix/splunk/admin/targets
AUTH="Authorization: Bearer $(cat admin-token)"

# SIT 只在 11-16 20:00 到 11-20 00:00 之间可用
curl -H "$AUTH" -X PUT -H 'Content-Type: application/json' \
     -d '{"windows":"2026-11-16 20:00:00 - 2026-11-20 00:00:00"}' $B/SIT/availability

# UAT 每天 09:00-18:00，外加一段周末维护后的窗口
curl -H "$AUTH" -X PUT -H 'Content-Type: application/json' \
     -d '{"windows":"09:00-18:00, 2026-11-21 10:00 - 2026-11-21 12:00"}' $B/UAT/availability

# 取消限制，但不恢复 yml 中的配置
curl -H "$AUTH" -X PUT -H 'Content-Type: application/json' -d '{"windows":""}' $B/SIT/availability

# 恢复 yml 中的配置
curl -H "$AUTH" -X DELETE $B/SIT/availability

# 查看全部
curl -H "$AUTH" $B
```

### 注意

- **修改只保存在内存里。** 进程重启后回到 yml 中的 `targets[].allow`。多实例部署时，需要对每个实例分别调用。
- **审计。** 每次修改（含新旧时段、调用方 IP 与主机名），以及每次 token 被拒，都写一行 `ADMIN_ALLOW`。
  这些记录保存在审计库里，但 `GET /audit` 会过滤掉，页面看不到；要查看请直接查库：
  `select * from splunk_broker.splunk_audit where event_type like 'ADMIN%'`。
  Node BFF 读同一张表时不做这层过滤。
- **8088 是明文 HTTP。** token 在网络上是明文传输：前面加 TLS，或只在可信网络里调用。
- token 只在内存中保存其 SHA-256，比较时用常量时间，也不会写进日志。
