# creed-tracker

用户旅程追踪（user journey）的自动配置库。用户在"起点路径"发起请求时，服务端下发一个 `sid` cookie（SameSite=Lax）。之后同一用户的每个请求都带着这个 cookie，于是这些请求的 inbound request/response 在 Logbook 日志里都打上同一个 `sid=`。在 Splunk 里按 `sid` 搜索、按时间排序，就能还原一个用户的完整操作链。

sid **只标记请求和响应**（用户旅程）。请求触发的业务处理，包括它发出的 outbound 调用（RestClient / RestTemplate / Apache HttpClient），不带 sid，用 traceId 关联。

目前由 `creed-resource-veracode` 使用。

## 解决的问题

| 问题 | 做法 |
|---|---|
| 无法把一个用户的多次请求串起来 | 在起点路径下发 `sid` cookie，并把它放进 MDC，之后每行日志都带 `sid=` |
| Logbook 同时打印入站请求和 RestTemplate 调用，看不出来源 | `TrackingSink`：每个方向一个独立 logger，并在行首加上 `direction=inbound\|outbound peer=…` |
| 响应头超过 8KB，Tomcat 直接返回 500，应用里看不到原因 | 过滤器在 chain 结束后估算响应头大小，超限时打 WARN，内容包括 sid、URL 和最大的 5 个头 |

## 接入

```xml
<dependency>
    <groupId>com.creed</groupId>
    <artifactId>creed-tracker</artifactId>
</dependency>
```

版本由根 `pom.xml` 的 dependencyManagement 统一管理。这个 jar 只要在 classpath 上，并且应用是 servlet 应用，就会自动生效，不需要写任何代码。Logbook 是 optional 依赖：应用自己引入了才会启用 `TrackingSink`。

最小配置（以 veracode 为例）：

```yaml
logbook:
  filter:
    enabled: true            # 记录入站请求（Logbook 默认就是 true；这里写出来，方便用环境变量关掉）
  format:
    style: splunk            # 一个请求/响应一行 key=value，Splunk 能自动抽取字段
  obfuscate:
    headers: [Authorization, Cookie, Set-Cookie, X-Jira-Token]   # 用户令牌必须脱敏

creed:
  tracker:
    start-paths: /api/veracode/jira/connection

logging:
  level:
    creed.tracker.http: INFO   # 设为 OFF 可关闭；也可以按 .inbound / .outbound 分别控制
```

## 配置项 `creed.tracker.*`

| 属性 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `start-paths` | 空 | Ant 风格路径，**不含** servlet context-path。命中的请求会开始一段新旅程，即生成一个新的 sid |
| `cookie.name` | `sid` | |
| `cookie.same-site` | `Lax` | 同站请求和顶层导航会带上，跨站的子请求不会带 |
| `cookie.path` | `/` | |
| `cookie.http-only` | `true` | 页面 JS 读不到这个 cookie，所以 sid 另外通过响应头告诉页面 |
| `cookie.secure` | 未设置 | 未设置时跟随请求：HTTPS 请求才加 `Secure` |
| `cookie.max-age` | 未设置 | 未设置时是会话 cookie，浏览器关闭后失效 |
| `response-header` | `X-Session-Id` | 每个带 sid 的响应都会回写这个头，前端和排障时可用。留空则不写 |
| `response-header-warn-size` | 未设置 | 响应头超过这个大小时打 WARN。未设置时取 `server.tomcat.max-http-response-header-size`（默认 8KB） |
| `logbook.enabled` | `true` | 是否用 `TrackingSink` 替换 Logbook 默认的 Sink |

## 工作原理

```
请求 ──► ServerHttpObservationFilter (HIGHEST+1, traceId)
     ──► TrackerFilter (HIGHEST+3)
           · 命中 start-paths → 新 UUID → Set-Cookie: sid=…; SameSite=Lax; HttpOnly
             并打 INFO "sid=… journey started at POST /… (previous sid=…)"
           · 否则读取请求里的 sid cookie（必须匹配 [A-Za-z0-9_-]{8,64}，不匹配就忽略，防止日志注入）
           · 写 X-Session-Id 头，sid 放进 MDC
     ──► … Logbook 过滤器 (LOWEST) → 控制器（入站请求/响应行带 sid）
           → RestClient 调用 Jira（出站行不带 sid，用 traceId 关联）
     ◄── finally：估算响应头大小，超限打 WARN；恢复之前的 MDC
```

- **异步分派**：过滤器在 ASYNC 分派上同样运行，所以 SSE 等异步完成的响应日志也带 sid。异步分派不会开始新旅程。
- **只在请求线程上**：sid 只放在处理请求的那个线程的 MDC 里，不跨线程传递（2026-11-22 起，去掉了原来的 `SidThreadLocalAccessor`）。业务代码切到虚拟线程或线程池后没有 sid 是预期行为，这部分日志按 traceId 查。
- **替换 Sink**：`TrackerAutoConfiguration` 声明在 `LogbookAutoConfiguration` 之前加载，它注册的 `TrackingSink` 会先于 Logbook 的 `@ConditionalOnMissingBean(Sink)` 生效，Logbook 自带的 `DefaultSink` 因此不再创建。应用自己定义了 `Sink` bean 时，以应用的为准。

## 日志格式

两个 logger，都是 INFO 级别：

- `creed.tracker.http.inbound`：本服务收到的请求和返回的响应（Logbook 的 `Origin.REMOTE`），还有旅程开始和响应头超限的那两行。
- `creed.tracker.http.outbound`：本服务对外发出的调用。

splunk 风格下，每行前面会加上：

```
sid=2f1c…e9 direction=inbound peer=10.0.0.5 origin=remote type=request method=GET uri=… 
direction=outbound peer=jira.example.com origin=local type=request method=POST uri=…/search/jql …
direction=outbound peer=jira.example.com origin=remote type=response status=200 …
sid=2f1c…e9 direction=inbound peer=10.0.0.5 origin=local type=response status=200 …
```

- **只有 inbound 行带 `sid=`**。outbound 行是应用自己的处理，可能发生在别的线程上，不带 sid；要看某个请求触发了哪些外部调用，用该请求的 traceId 查。

- **不要用 Logbook 的 `origin=` 判断方向**：Logbook 在响应行上会把 origin 翻转（入站响应写 `local`，出站响应写 `remote`）。判断方向一律看 `direction=`。
- `peer`：入站时是客户端地址，出站时是被调用的主机。
- 还没有 sid 的请求（旅程开始前）记为 `sid=-`。
- json 风格下，`sid`（仅 inbound）/ `direction` / `peer` 会作为字段插进 JSON 对象，而不是加在行首。
- 响应可能在另一个线程上写出（异步完成的情况），所以 sid 按 Logbook 的 correlation id 暂存，等配对的响应写出后再取用。暂存最多 4096 条，客户端断开导致永远等不到的响应不会造成泄漏。

### Splunk 查询示例

```
index=… sid=2f1c…e9 | sort _time                         # 一个用户的完整旅程
index=… "<某个请求的 traceId>" direction=outbound        # 这个请求触发的外部调用（traceId 由日志 pattern 打成 [traceId-spanId]，或响应头 X-Trace-Id）
index=… "journey started"                                 # 所有旅程的起点
index=… "exceed" "max-http-response-header-size"          # 响应头超限
```

## 响应头超过 8KB 时

Tomcat 11 在提交响应时发现头超过 `server.tomcat.max-http-response-header-size`（默认 8KB），会抛 `HeadersTooLargeException`，然后自己返回一个**不带任何头的 500**。这时应用（包括 Logbook）记下的还是自己设置的状态码，看不到这个 500。实际能看到的日志：

1. **请求行能看到**：Logbook 的 inbound request 行在控制器执行之前就写了，带着 sid。
2. **响应行看不到**：Logbook 那一行的状态码不可信，或者干脆没有。
3. **tracker 的 WARN 能看到**：

   ```
   WARN creed.tracker.http.inbound sid=… GET /api/… response headers ~9312 bytes exceed 8192 bytes
     (server.tomcat.max-http-response-header-size): the container answers 500 instead of status 200;
     largest headers: X-Big=8800, Content-Type=30, …
   ```

估算方法：每个头按 `Name: value\r\n` 计算，再加 128 字节，近似状态行和 Tomcat 自己补的头。这是近似值，所以接近阈值时可能出现误报或漏报。如果要提前预警，可以把 `response-header-warn-size` 调到比阈值小一些。

## 测试

```bash
mvn -pl creed-framework/creed-tracker test
```

- `TrackerFilterTest`：起点路径下发 cookie，已有 cookie 保持不变，非法 cookie 被忽略，context-path 的处理，`Secure` 跟随请求。
- `it/TrackerJourneyTest`：在 RANDOM_PORT 上启动真实 Tomcat，使用 splunk 风格。验证 inbound 请求/响应行带 sid，在虚拟线程上发出的 outbound 调用行不带 sid；验证响应头超过 8KB 时返回 500，同时请求行和 WARN 都记录了下来。

## 注意

- sid 只用来关联日志，**不是**身份凭证，不能用于鉴权。
- Logbook 的入站过滤器默认开启（`logbook.filter.enabled` 不写就是 `true`），排在过滤器链最后（`LOWEST_PRECEDENCE`）。顺序、写日志的时机，以及未处理异常时没有响应行、表单 `@RequestParam` 被读空这些坑，见 [docs/logbook-servlet-filter.md](docs/logbook-servlet-filter.md)。开启后，请求头里的令牌也会被打印，所以 `logbook.obfuscate.headers` 要把所有令牌头都列进去。veracode 的配置已经包含 `Authorization`、`Cookie`、`Set-Cookie`、`X-Jira-*`、`X-Confluence-*`。`Set-Cookie` 被脱敏后，sid 的值只会出现在 "journey started" 那一行。
- 自定义 `Sink` bean 会让 `TrackingSink` 失效，日志就不再带 `sid` 和 `direction`。
