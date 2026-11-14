# HTTP header 大小：`HeadersTooLargeException`（"no room available in the buffer"）

## 现象

```
org.apache.coyote.http11.HeadersTooLargeException: An attempt was made to write more data to the response headers
than there was room available in the buffer. Increase maxHttpHeaderSize on the connector or write less data into the
response headers.
```

客户端收到 **HTTP 500**，body 只有几个字节；请求本身已经被处理（下游调用、日志都已发生），只是响应写不出去。

## 原因

1. **Camel REST 路由会把请求头原样写回响应头。** servlet binding 把 exchange 的 message headers 全部写成 HTTP 响应头，
   而 exchange 一开始就带着全部请求头（下游返回的响应头也会混进来）。实测：`X-Trace-Note`、`Cookie`、`Authorization`、
   `Proxy-Authorization`、`X-Api-Key` 都会出现在响应里。
2. **Tomcat 的请求头和响应头上限是两个值**，默认都是 8 KB：
   - `server.max-http-request-header-size` → `maxHttpRequestHeaderSize`
   - `server.tomcat.max-http-response-header-size` → `maxHttpResponseHeaderSize`

   错误信息里的 `maxHttpHeaderSize` 是 Tomcat 的旧名字（同时设置两者），Boot 4 没有对应的单一属性。

于是：请求头刚好在 8 KB 以内 → 请求被接受 → 回显到响应，再加上响应自己的头（约 200 B）→ 超过 8 KB → 500。

## 复现（2026-11-14，本地实测）

`docs/scripts/header-size-repro.sh https://localhost:8096/simple 7980 8000 8040 8100`

| `X-Trace-Note` | Tomcat 默认（8 KB / 8 KB） | 只把请求上限调到 16 KB | 现在的配置（8 KB / 16 KB） |
|---|---|---|---|
| 7,980 B | 200（响应头 8,186 B） | 200 | 200 |
| 8,000 B | **500**（HeadersTooLarge） | **500** | 200 |
| 8,040 B | **500** | **500** | 200 |
| 8,100 B | 400 Request header is too large | **500** | 400 |
| 12,000 / 15,000 B | 400 | **500** | 400 |

**只调大请求上限会更糟**：原本干净的 400 变成了请求已被处理之后的 500。复现旧行为：
`CREED_SIMPLE_MAX_RESPONSE_HEADER_SIZE=8KB` 启动。

## 配置

```yaml
server:
  max-http-request-header-size: ${CREED_SIMPLE_MAX_REQUEST_HEADER_SIZE:8KB}
  tomcat:
    max-http-response-header-size: ${CREED_SIMPLE_MAX_RESPONSE_HEADER_SIZE:16KB}
```

规则：**响应上限 ≥ 请求上限 + 响应自己的头**，取 2 倍。调大请求上限时，响应上限一起调。
`HttpHeaderSizeCheck` 在启动时检查（余量 2 KB），不满足时打 WARN；`HttpHeaderSizeTest` 在真实 Tomcat 上
（不需要 PKI、不需要 Camel，用一个同样回显请求头的 servlet）锁住这条规则。

TLS 加在 Boot 自己的那个 connector 上（`TomcatHttpsConfiguration` 只设置 `Ssl`），所以这两个属性对 HTTPS 生效；
如果以后加了 `addAdditionalConnectors` 的额外 connector，要在那个 connector 上单独设置。

## 根本修复：REST 头过滤（`RestHeaderFilterStrategy`，2026-11-14）

servlet 组件挂上了自己的 `HeaderFilterStrategy`（`camel.component.servlet.header-filter-strategy: "#restHeaderFilterStrategy"`，
名单在 `creed.camel.rest.headers.*`）：

- **出站（写回响应）：白名单** `Cache-Control, Content-Disposition, Location, Retry-After, ETag, Last-Modified`；
  `Content-Type` 由 binding 单独写，不用列。请求头的回显、路由内部头（`source`、`lane`、`branchError`…）、下游自己的
  响应头（含 `Set-Cookie: JSESSIONID`）都不再出去。列出的名字即使在 Camel 自带的出站黑名单里（`cache-control` 等）也照发，
  所以不要把 `Content-Length` / `Transfer-Encoding` 加进白名单。
- **入站（进入 exchange）：黑名单** `Authorization, Proxy-Authorization` 和 hop-by-hop 头。必须是黑名单：query 参数和头
  走同一个过滤器，路由要读很多 query 参数。`Cookie` 保留（payment 的 sticky 路由要读 `stickyId`）。`Camel*` 两个方向都不能过。

实测（响应上限故意设回 8 KB）：带 `Cookie` / `Authorization` / `X-Api-Key` 的请求，响应头只剩 `traceId`、`Content-Type`、
`Content-Length`、`Date`；8,000 / 8,040 B 的 `X-Trace-Note` 都是 200（响应头 153 B）。上面的上限配置保留，作为余量
（白名单变长、或者过滤器被拿掉时）。`RestHeaderFilterStrategyTest` 用 Camel 自己的 `DefaultHttpBinding` 锁住两个方向。
