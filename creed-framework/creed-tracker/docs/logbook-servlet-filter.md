# Logbook 的 Servlet 过滤器：在 Spring 过滤器链里排第几、为什么、有什么后果

基于 **Logbook 4.2.0 + Spring Boot 4.1.1（Tomcat 11）** 的源码。文中的顺序都在 `creed-resource-veracode` 上实际测过（见第 8 节）。

## 0. 结论

| | `logbookFilter` | `secureLogbookFilter` |
|---|---|---|
| 开关 | `logbook.filter.enabled`，**默认 `true`** | `logbook.secure-filter.enabled`，默认 `true` |
| 什么时候注册 | Servlet 应用，classpath 上有 `logbook-servlet` | 另外还要求 classpath 上有 Spring Security 的 `SecurityFilterChain` |
| order | **`Ordered.LOWEST_PRECEDENCE`（`Integer.MAX_VALUE`）**：链里最后一个，紧挨着 `DispatcherServlet` | **`Ordered.HIGHEST_PRECEDENCE + 1`**：几乎最先，在 Spring Security（-100）外面 |
| dispatcher | `REQUEST`、`ASYNC`（没有 `ERROR`、`FORWARD`、`INCLUDE`） | 同左 |
| 记录什么 | 所有请求（受 `logbook.predicate`、`Strategy` 控制） | 只记录状态码为 **401 / 403** 的请求和响应，不带 body |

可以这样理解：`logbookFilter` 是**最内层**的过滤器，看到的是外层过滤器都处理过之后的请求，记录的是 `DispatcherServlet` 产生的响应。
被外层挡掉的请求（比如 Security 返回的 401）根本到不了它。`secureLogbookFilter` 放在最外层，就是专门用来补记这类请求的。

## 1. 属性怎样变成过滤器

`logbook-spring-boot-autoconfigure` 的 `LogbookAutoConfiguration` 里有两个内部配置类（下面的代码有删节）：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ Servlet.class, LogbookFilter.class })
static class JakartaServletFilterConfiguration {
    private static final String FILTER_NAME = "logbookFilter";

    @Bean
    @ConditionalOnProperty(name = "logbook.filter.enabled", havingValue = "true", matchIfMissing = true)  // ①
    @ConditionalOnMissingBean(name = FILTER_NAME)                                                         // ②
    public FilterRegistrationBean<?> logbookFilter(Logbook logbook, AsyncOnCompleteListenerWrapper wrapper) {
        LogbookFilter filter = new LogbookFilter(logbook)
                .withFormRequestMode(properties.getFilter().getFormRequestMode())                          // ③
                .withAsyncOnCompleteListenerWrapper(wrapper);
        return newFilter(filter, FILTER_NAME, Ordered.LOWEST_PRECEDENCE);                                  // ④
    }

    static FilterRegistrationBean<?> newFilter(Filter filter, String filterName, int order) {
        FilterRegistrationBean<?> registration = new FilterRegistrationBean<>(filter);
        registration.setName(filterName);
        registration.setDispatcherTypes(REQUEST, ASYNC);                                                   // ⑤
        registration.setOrder(order);
        return registration;
    }
}

@ConditionalOnClass({ SecurityFilterChain.class, Servlet.class, LogbookFilter.class })                  // ⑥
@ConditionalOnWebApplication(type = Type.SERVLET)
static class JakartaSecurityServletFilterConfiguration {
    @Bean
    @ConditionalOnProperty(name = "logbook.secure-filter.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "secureLogbookFilter")
    public FilterRegistrationBean<?> secureLogbookFilter(Logbook logbook, AsyncOnCompleteListenerWrapper wrapper) {
        SecureLogbookFilter filter = new SecureLogbookFilter(logbook).withAsyncOnCompleteListenerWrapper(wrapper);
        return newFilter(filter, "secureLogbookFilter", Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
```

1. `matchIfMissing = true`：**不写这个属性，过滤器就是开着的。** 引入 starter 的 servlet 应用默认会记录所有入站请求。只想记录客户端调用（RestTemplate、HttpClient）的话，要显式设成 `false`。lb-health 就是这么配的。
2. `logbookFilter` 按 **bean 名字** back off。自己定义一个同名 bean，就能替换 order、dispatcher 类型、url-pattern，见第 6 节。
3. `logbook.filter.form-request-mode`：`BODY`（默认）、`PARAMETER`、`OFF`。这个设置有副作用，见 5.5。
4. order 写死在代码里，**没有对应的属性**。
5. 不注册 `ERROR` dispatcher，所以 Boot 的错误页（`/error`）那次分派不会被记录，见 5.2。
6. 只要 classpath 上有 `spring-security-web`，就会多出一个 `secureLogbookFilter`，不需要应用里真的配置了 `SecurityFilterChain`。

`LogbookAutoConfiguration` 上的 `@AutoConfigureAfter` 写的是 Boot 3 的类名：`org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration` 和 `...security.servlet.SecurityFilterAutoConfiguration`。
Boot 4 里它们已经搬到 `org.springframework.boot.webmvc.autoconfigure.*` 和 `org.springframework.boot.security.autoconfigure.web.servlet.*`，所以在 Boot 4 上这两条声明实际不起作用。
不过过滤器的先后**本来就和自动配置的加载顺序无关**，只看下一节讲的 `FilterRegistrationBean.order`。

## 2. Spring Boot 怎样把过滤器排成链

```
ServletWebServerApplicationContext.createWebServer()
  └─ getSelfInitializer() → selfInitialize(servletContext)
       └─ new ServletContextInitializerBeans(beanFactory)
            ├─ 收集所有 ServletContextInitializer bean（FilterRegistrationBean、DelegatingFilterProxyRegistrationBean……）
            ├─ addAdaptableBeans：把裸的 Filter bean（@Component、@Bean Filter）包成 FilterRegistrationBean，
            │     order 取 Ordered.getOrder() / @Order，都没有就是 LOWEST_PRECEDENCE
            └─ 按 AnnotationAwareOrderComparator 排序
       └─ 按排好的顺序逐个 onStartup()
            └─ servletContext.addFilter(name, filter) + addMappingForUrlPatterns(dispatcherTypes, matchAfter, "/*")
```

Tomcat 按添加 filter mapping 的先后组装 `ApplicationFilterChain`，所以 **order 越小越靠外**：请求最先经过它，响应最后离开它。
order 相同时，排序是稳定的，先后取决于 bean 的注册顺序，代码里不能依赖这一点。

veracode 实测的链，取自 `ServletContextInitializerBeans` 的迭代顺序：

| order | 过滤器 | 作用 | dispatcher |
|---|---|---|---|
| `MIN_VALUE` | `characterEncodingFilter` | 请求和响应的字符集 | 全部 |
| `MIN_VALUE + 1` | `webMvcObservationFilter`（`ServerHttpObservationFilter`） | 开始 span，MDC 里有了 `traceId` | REQUEST, ASYNC |
| `MIN_VALUE + 2` | `traceIdResponseFilter`（veracode） | 写 `X-Trace-Id` 响应头 | 全部 |
| `MIN_VALUE + 3` | `trackerFilter`（creed-tracker） | 下发、读取 `sid`，放进 MDC | REQUEST, ASYNC |
| -9900 | `formContentFilter` | 解析 PUT、PATCH、DELETE 的表单 body | 全部 |
| -105 | `requestContextFilter` | `RequestContextHolder` | 全部 |
| *(-100)* | *Spring Security 的 `springSecurityFilterChain`*（veracode 没有，`spring.security.filter.order` 的默认值） | | |
| **`MAX_VALUE`** | **`logbookFilter`** | | **REQUEST, ASYNC** |

如果应用用了 Spring Security，链的样子是：

```
secureLogbookFilter (MIN+1) ─► … ─► springSecurityFilterChain (-100) ─► … ─► logbookFilter (MAX) ─► DispatcherServlet
        │                               │ 401 / 403 在这里就返回了                 ▲
        │                               └──────── logbookFilter 看不到 ───────────┘
        └─ 响应回到这里时，状态码是 401 / 403 就把请求和响应一起写出（SecurityStrategy）
```

`secureLogbookFilter` 的 order 和 `ServerHttpObservationFilter` 都是 `MIN_VALUE + 1`，谁在外面取决于 bean 的注册顺序。
如果 observation 在里面，`secureLogbookFilter` 写日志时 span 已经结束了，那两行日志里就没有 `traceId`。

## 3. `LogbookFilter.doFilter`：日志在什么时候写

`logbook-servlet` 的 `LogbookFilter`（`HttpFilter` 只是把 `ServletRequest` 转成 `HttpServletRequest`）：

```java
public void doFilter(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain chain) {
    RemoteRequest request = new RemoteRequest(httpRequest, formRequestMode);              // 包装请求（body 可重复读）
    LocalResponse response = new LocalResponse(httpResponse, request.getProtocolVersion()); // 包装响应（tee 一份 body）

    ResponseProcessingStage processing;
    if (request.getDispatcherType() == ASYNC) {
        processing = (ResponseProcessingStage) request.getAttribute(responseProcessingStageName); // ASYNC 分派：沿用第一次的 stage
    } else {
        processing = process(request).write();                                            // ★ 请求行在这里写出：控制器还没执行
        request.setAttribute(responseProcessingStageName, processing);
    }
    ResponseWritingStage writing = processing.process(response);                          // 决定响应要不要 body

    chain.doFilter(request, response);                                                    // ★ 链的剩余部分 + DispatcherServlet

    if (request.isAsyncStarted()) {                                                       // 异步：完成时由 listener 写
        request.getAsyncContext().addListener(new LogbookAsyncListener(
                asyncOnCompleteListenerWrapper.wrap(event -> write(response, writing))));
        return;
    }
    if (request.getDispatcherType() != ASYNC) {
        write(response, writing);                                                         // ★ 响应行：chain 正常返回之后
    }
}
```

`Logbook.process(request)` 在 `DefaultLogbook` 里依次执行：

1. `sink.isActive() && predicate.test(request)`。`logbook.predicate.exclude` 在这一步生效，不满足就返回 `Stages.noop()`。**这时过滤器已经包装了请求和响应**，只是后面不再写日志。
2. 生成 correlation id。
3. 调用 `strategy.process(request)`。默认的 `Strategy` 是 `request.withBody()`，会**立即把整个请求 body 读进内存**。
4. 依次过 RequestFilter、HeaderFilter、BodyFilter，然后 `strategy.write(precorrelation, …)` → `Sink.write`。

由此可以得出三点：

- **请求行在控制器执行之前写出**，线程就是请求线程。外层过滤器放进 MDC 的值都在：traceId（observation），以及 creed-tracker 的 sid。这正是 `TrackerFilter` 要排在 Logbook 前面的原因。
- **响应行在 `chain.doFilter` 正常返回后写出**，这时仍在外层过滤器的作用域里。observation 的 span 还没结束，`TrackerFilter` 的 `finally` 也还没恢复 MDC，所以响应行也带着 traceId 和 sid。
- 异步请求（SSE、`Callable`、`DeferredResult`）第一次分派时只写请求行，响应行由 `AsyncListener.onComplete` 写，**可能在另一个线程上**。
  creed-tracker 因此按 correlation id 暂存 sid，不依赖 MDC。stage 存在 request attribute 里，attribute 名字带每个实例自己的 UUID，同一条链里有两个 Logbook 过滤器也不会互相串。

`SecureLogbookFilter` 只是一个委托：`new LogbookFilter(logbook, new SecurityStrategy())`。`SecurityStrategy` 的三个方法分别是：

- `process(request)` 返回 `request.withoutBody()`。
- `write(precorrelation, …)` 什么也不写，等到响应出来再决定。
- `write(correlation, …)` 只在状态码是 401 或 403 时调用 `sink.writeBoth`。

## 4. body 是怎样被"偷看"的

- **请求**：`RemoteRequest` 是一个状态机，状态有 Unbuffered、Offering、Withouted、Buffering、Ignoring、Passing。
  - `withBody()` 时调用 `doBuffer()`，用 `toByteArray(getInputStream())` 读完整个 body，之后 `getInputStream()` 和 `getReader()` 返回的都是这份副本。
  - multipart 请求不缓冲（Passing）。表单请求按 `FormRequestMode` 处理。
  - 状态是 `withoutBody()` 时，下游直接读原始流。
- **响应**：`LocalResponse` 是懒加载的。下游第一次调用 `getOutputStream()` 或 `getWriter()` 时，才换成一个 `Tee`：同时写给客户端和一个 `ByteArrayOutputStream`。
  - 只有经过这个包装对象写出的内容才会被记录。
  - 状态码和响应头直接读底层的 response。

## 5. "最后一个"带来的后果

### 5.1 好处：看到的是最终的请求

外层过滤器做的事 Logbook 都能看到：编码、包装、`X-Forwarded-*` 改写、Security 认证后的结果，以及 MDC 里的 traceId 和 sid。
控制器写出的内容也一定经过它的 tee，因为它离 `DispatcherServlet` 最近。

### 5.2 未处理的异常：只有请求行，没有响应行（已实测）

`chain.doFilter(...)` 外面**没有 try/finally**。异常从 `DispatcherServlet` 抛出来时，经过 `LogbookFilter` 一路冒泡，`write(response, writing)` 根本不会执行。
之后 Tomcat 以 `ERROR` 分派转发到 `/error`，而 Logbook 没有注册 `ERROR`，Boot 生成的 500 错误页也不会被记录。

实测：在 veracode 上让一个测试控制器抛 `IllegalStateException`，客户端收到 500，日志只有 `type=request`，**没有 `type=response`**。正常的请求有成对的两行。

被 `@ExceptionHandler` 或 `@ControllerAdvice` 接住的异常没有这个问题，因为对过滤器来说那是一次正常返回（比如 veracode 的 400 `jira_token_required`）。

查这类请求的方法：用请求行的 `correlation`、`traceId`、`sid` 去找同一个 traceId 下的 ERROR 日志，例如 Boot 打的 `Servlet.service() ... threw exception`。

### 5.3 被外层挡掉的请求看不到

Security 返回的 401 和 403、外层过滤器自己 `sendError` 或直接写响应后不调用 `chain.doFilter`，这些请求都到不了 `logbookFilter`，**请求行也不会有**。
401 和 403 由 `secureLogbookFilter` 补记（需要 classpath 上有 Security）。其他情况只能靠那个过滤器自己记日志。

### 5.4 外层过滤器在 chain 之后改的响应看不到

响应行在内层写出。外层过滤器在 `chain.doFilter` **之后**再加的响应头，或者改的状态码，Logbook 都记录不到；这时响应通常也已经提交了，改了也不生效。
所以 veracode 的 `TraceIdResponseFilter` 在 chain **之前**设置 `X-Trace-Id`，这个头会出现在 Logbook 的响应行里。

Tomcat 在提交阶段自己产生的 500（比如响应头超过 8KB）也看不到，见 creed-tracker README 的"响应头超限"一节。

### 5.5 请求 body 在控制器之前就读完了

- 默认 `Strategy` 会把整个请求 body 读进内存（multipart 除外）。上传大文件或大 JSON 时，内存里多一份副本，读取也提前发生在 Logbook 这一层。
  veracode 用 `JsonBodyOnlyStrategy`：只有 JSON 才调用 `withBody()`，其他类型直接放行，原始流不被碰。
- **`form-request-mode: BODY`（默认）会让表单 POST 的 `@RequestParam` 变成 null（已实测）。**
  Logbook 先把 `application/x-www-form-urlencoded` 的原始流读走了。包装对象的 `getParameter*` 没有被覆盖，Tomcat 解析参数时流已经空了。
  - 实测：`POST a=hello`，在 BODY 模式下控制器收到 `a=null`，在 PARAMETER 模式下收到 `a=hello`。两种模式下日志里的 body 都是 `a=hello`。
  - 要记录表单 body，就设 `logbook.filter.form-request-mode: PARAMETER`（用参数重建 body），或者 `OFF`（不记录表单 body）。
  - veracode 没有表单接口，而且 `JsonBodyOnlyStrategy` 根本不会缓冲表单，所以不受影响。

### 5.6 只记 REQUEST 和 ASYNC

`FORWARD` 和 `INCLUDE`（比如 JSP 或视图转发）不会重复记录，`ERROR` 也不记录（见 5.2）。

## 6. 改顺序、改范围、关掉

**换一个位置**：定义同名 bean，自动配置就会 back off（`@ConditionalOnMissingBean(name = "logbookFilter")`）：

```java
@Bean
FilterRegistrationBean<LogbookFilter> logbookFilter(Logbook logbook) {
    var registration = new FilterRegistrationBean<>(new LogbookFilter(logbook)
            .withFormRequestMode(FormRequestMode.PARAMETER));
    registration.setName("logbookFilter");
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);   // 例：放到 Security 外面，401/403 也能记录
    registration.addUrlPatterns("/api/*");                                        // 例：只记录 API
    return registration;
}
```

**往外挪之前要想清楚**：

- 挪到 `ServerHttpObservationFilter` 外面，日志就没有 traceId 了。
- 挪到 `TrackerFilter` 外面，日志就没有 sid 了。
- 挪到 Security 外面，所有被拒绝的请求都会以带 body 的形式记录下来。

**关掉**：

- `logbook.filter.enabled: false` 只是不注册入站过滤器。`Logbook` bean 照常创建，RestTemplate、HttpClient5、WebClient 的出站日志不受影响（lb-health 就是这么用的）。
- `logbook.secure-filter.enabled: false` 关掉 401/403 补记。
- 不想记录某些路径时，用 `logbook.predicate.exclude`，不要去改过滤器的 url-pattern。前者对出站日志也生效。

## 7. 本仓库的用法

| 模块 | `logbook.filter.enabled` | 说明 |
|---|---|---|
| creed-resource-veracode | `${CREED_VERACODE_LOGBOOK_FILTER:true}` | 用户旅程：入站行带 sid（creed-tracker），`JsonBodyOnlyStrategy` 只记录 JSON body |
| creed-simple-metrics | 未设置，即**默认开启** | 用来记录入站的 `/camel/*`（替代了旧的 `AuditLoggingFilter`）。classpath 上只有 `spring-security-oauth2-jose`，没有 `SecurityFilterChain`，所以没有 `secureLogbookFilter` |
| creed-resource-lb-health | `false` | 只记录健康检查的出站请求 |

本仓库目前没有一个模块同时引入了 Logbook 和 `spring-security-web`，所以 `secureLogbookFilter` 在这里一次都没有注册过。

## 8. 怎么复现第 2、5 节的结论

用一次性的 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 测试，跑完就删掉：

```java
// 过滤器顺序：就是 Boot 交给 Tomcat 的顺序
for (var i : new ServletContextInitializerBeans(beanFactory)) {
    if (i instanceof AbstractFilterRegistrationBean<?> r) {
        System.out.println(r.getOrder() + " " + r.getFilterName() + " " + r.determineDispatcherTypes());
    }
}
```

- **5.2**：`@Import` 一个抛异常的 `@RestController`，用 `java.net.http.HttpClient` 请求真实端口（MockMvc 不会做 ERROR 分派），再用 `OutputCaptureExtension` 抓取 `creed.tracker.http.inbound` 的行。
- **5.5**：设置 `veracode.logbook.allowed-content-types=`（为空表示记录所有 body），分别用 `logbook.filter.form-request-mode=BODY` 和 `PARAMETER` 发送 form POST，比较控制器收到的 `@RequestParam`。

源码位置（Logbook 4.2.0，sources jar 在本地仓库 `org/zalando/*/4.2.0/`）：

- `logbook-spring-boot-autoconfigure`：`LogbookAutoConfiguration`、`LogbookProperties`
- `logbook-servlet`：`LogbookFilter`、`SecureLogbookFilter`、`HttpFilter`、`RemoteRequest`、`LocalResponse`、`FormRequestMode`
- `logbook-core`：`DefaultLogbook`、`SecurityStrategy`
- `logbook-api`：`Strategy`
- Spring Boot：`spring-boot` 的 `ServletContextInitializerBeans`，`spring-boot-security` 的 `SecurityFilterProperties.DEFAULT_FILTER_ORDER = -100`
