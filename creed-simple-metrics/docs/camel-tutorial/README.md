# Camel 教程（迁自 camel-session-0to1）

一套用 **Spring XML DSL** 把 Apache Camel 从一条路由讲到完整编排的教学 API，共 8 个。原本是独立工程
`camel-session-0to1`（Spring Boot 4.1 / Camel 4.22），2026-10-14 整体迁入 creed-simple-metrics，
跑在本模块的 Boot 3.5.14 / Camel 4.18.2 上。和模块原有的 `/camel/api/*` 路由同处一个 `<camelContext>`，
但代码、配置、路由文件、文档各自成组，互不交叉。

```
https://localhost:8096/simple
├── /camel/api/v1/orders/*      API-1 ~ API-7   Camel REST DSL（Camel servlet）
├── /camel/api/v1/files/upload  API-8           同上，multipart
├── /mock/remote/*  /mock/artisan/*            API-3 / API-7 调用的"外部系统"（Spring MVC）
├── /openapi  /openapi/<group>.yaml            分组后的 OpenAPI 文档
└── /swagger-ui.html                           Swagger UI（右上角下拉框切分组）
```

## 1. 跑起来

```bash
# 仓库根目录
mvn -q -pl creed-simple-metrics -am -DskipTests install
mvn -pl creed-simple-metrics spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

docs/camel-tutorial/scripts/test-apis.sh                      # 一键回归 14 个用例
docs/camel-tutorial/scripts/test-apis.sh https://host:8096/simple
open https://localhost:8096/simple/swagger-ui.html
```

- 只有 HTTPS 监听（自签证书），所以 curl 要带 `-k`。
- IntelliJ 的全量请求集在 `http/tutorial-api.http`，环境定义在同目录的 `http-client.env.json`，里面已关闭证书校验。
- 单元测试：`mvn -pl creed-simple-metrics test -Dtest='com.creed.simple.tutorial.**.*Test'`。

## 2. 八个 API

| API | 端点（相对 `/simple/camel`） | 路由 id | 演示的标签 |
|---|---|---|---|
| 1 | `POST /api/v1/orders/basic` | `api1-basic-order` → `api1-enrich-order` | `<route> <from> <to> <process> <log>` |
| 2 | `POST /api/v1/orders/dispatch` | `api2-dispatch-order` → `api2-place-order` | `<choice> <when> <otherwise> <simple> <jsonpath> <filter>` |
| 3 | `POST /api/v1/orders/quote` | `api3-quote-order` / `api3-calc-fee` | ProducerTemplate、`bean:`、`freemarker:` 出入站报文转换 |
| 4 | `POST /api/v1/orders/check` | `api4-parallel-check` + `api4-risk/credit/inventory` | `<multicast>` + `aggregationStrategy` |
| 5 | `POST /api/v1/orders/split` | `api5-split-items` + `item-*` + `audit-log` | `<split> <wireTap> <toD>` |
| 6 | `POST /api/v1/orders/fulfil` | `api6-complex-fulfilment` | 以上全部组合 |
| 7 | `POST /api/v1/orders/accounts`、`/accounts/create`、`/accounts/detail` | `api7-account-lookup/-create/-detail` | 自定义组件 `httpexchange:` + Spring `@HttpExchange` |
| 8 | `POST /api/v1/files/upload` | `api8-file-upload` | Servlet Filter 解码 Base64 文件字段、Camel attachment、`<post bindingMode="off">` + `<marshal>` |

- REST 层统一 `<to uri="direct:...">` 转交给业务路由。
- API-4 的三条风控子路由，以及 API-5 的 `item-*` 和 `audit-log`，都被 API-6 复用。
- 业务阈值：`ScoreProcessor` 的三项打分与 `RiskDecisionProcessor` 的 `PASS_LINE = 240` 共同决定 API-6 走哪条分支。
  - `amount <= 50000` 得 270 分，结果 APPROVED；`amount > 60000` 得 165 分，结果 REJECTED。
  - `RiskScoringTest` 固定了这两个结果。改阈值时，回归脚本里的两个 API-6 用例和 PPT 里引用的分数都要一起看。

**贯穿全部 API 的两条语义：**
- **`<filter>` 不终止路由。** 条件不匹配时，只跳过 filter 内部的步骤，后面的节点照常执行。API-2 和 API-6 都把收口 Processor 放在 filter **外面**，所以放行和拦截两种情况返回的 JSON 字段完全一致。
- **Body 会被反复改写，Property 不会。** API-6 在 multicast 之前用 `<setProperty name="originalOrder">` 备份原始报文，履约前再还原。

## 3. 代码在哪

| 位置 | 内容 |
|---|---|
| `src/main/resources/camel/tutorial/` | **教学核心**：10 个片段文件，每个是一个 `<routeContext>`/`<restContext>` Bean，文件头有该组标签的英文讲解 |
| `src/main/resources/camel-context.xml` | 顶部 `<import>` 这 10 个文件，`<camelContext>` 里用 `<routeContextRef>`/`<restContextRef>` 装配（schema 要求这些 ref 写在 `<restConfiguration>` **之前**）|
| `src/main/resources/tutorial/` | `tutorial.yml`（教程自己的配置，由 `application.yml` 的 `spring.config.import` 引入）、`openapi.yaml`（手写契约）、`templates/*.ftl` |
| `com.creed.simple.tutorial.processor` / `.strategy` | `<process ref>` 和 `aggregationStrategy` 引用的 Bean，Bean 名就是 `@Component` 名 |
| `.service.RemoteCallService` | API-3：ProducerTemplate 的四种调用（两次 `freemarker:`、一次 `https://`、一次 `direct:`）|
| `.client` | API-7 的 `ArtisanClient`（HTTP Interface）、`ArtisanHeaders`、代理装配、`ArtisanQueryParams` |
| `.component.httpexchange` | 自定义组件 `httpexchange:`，scheme 注册在 `META-INF/services/org/apache/camel/component/httpexchange` |
| `.upload` | API-8：`Base64FileMultipartFilter`（+ `UploadFilterConfig`）、`MultipartAttachmentProcessor`、`FileUploadProcessor` |
| `.mock` | 扮演"外部系统"的 MVC Controller，**故意**使用另一套报文格式（`header`/`payload` 信封、缩写字段），让模板有东西可翻译 |
| `.openapi` | 分组机制：`OpenApiGroupService/Controller/Properties`、`SwaggerUiGroupConfig` |
| `TutorialThreadPoolConfiguration` | `tutorialTaskPool`：API-4/6 的并行 multicast 和 API-5/6 的 wireTap 都跑在这里（线程名 `tutorial-pool-*`）|

**新增一个 API：**
1. 在 `camel/tutorial/` 下加一个 `<routeContext>` 文件。
2. 在 `camel-context.xml` 里加 `<import>` 和 `<routeContextRef>`。
3. 在 `rest-api.xml` 里加 `<post>`。
4. 在 `openapi.yaml` 里补上 path。

XML 注释里不能出现连续两个短横线（`--`），画分隔线用 `=`。

## 4. 两处值得细读的设计

### API-7：自定义组件 `httpexchange:`

API-3 用 `ProducerTemplate` + `https://` 调用外部系统；API-7 是同一件事的**声明式**写法。两者刻意并存，方便对照。

```
httpexchange:<beanName>/<methodName>
    ?requestTemplate=...     调用前渲染，结果绑到 @RequestBody 参数（String 参数原样传，其他类型先解析）
    &responseTemplate=...    调用后渲染，输入是拆掉 ResponseEntity 后的返回值
    &unmarshalTo=...         把渲染结果解析回对象，供 REST bindingMode=json 使用
    &forwardHeaders=a,b      只在方法自己声明了 @RequestHeader Map 时生效
    &queryParamsHeader=...   哪个 Exchange header 装着 @RequestParam Map 的内容
    &normalizeResponse=true  渲染前先用 Jackson 把返回值转成 Map/List（默认 true）
```

- **`ArtisanClient` 的 `@RequestBody` 一律是 `String`。** 模板渲染出的 JSON 原样发出，出站报文的结构只写在 `.ftl` 里。
  - 类型级的 `contentType = application/json` 不能省，否则 String 会被标成 `text/plain`。
- **请求头分三层，不要在接口方法上写 `@RequestHeader`：**
  - 常量头：写在接口的 `@HttpExchange(headers = ...)` 上，支持 `${}`，例如 `X-Client-Id`。
  - 每次请求不同的头：由 `ArtisanHeaders` 加，例如 `X-Trace-Id`、`X-Channel`。
  - 只有某个方法需要的头：写在那个方法的 `@RequestHeader Map` 上。
- **`ArtisanHeaders` 怎么拿到 Exchange：** `HttpExchangeProducer` 在调用代理期间把当前 Exchange 放进 `HttpExchangeContext`（ThreadLocal）。RestClient 是同步的，调用和拦截器在同一线程。
- **组件的三个坑**（都真实踩过）：
  1. 要从**接口**上找方法。代理类上的方法不带参数注解，所有参数都会绑成 null。
  2. 必须开 `-parameters`，根 pom 已开。
  3. 请求头参数不能用 `HttpHeaders`（Framework 7 起它不再实现 `MultiValueMap`），要用 `MultiValueMap<String,String>`。
- `FreemarkerRenderer` 的模型变量名（`body`/`headers`/`exchange`）和 camel-freemarker 一致，所以同一个 `.ftl` 在两种方式下渲染结果相同。

### API-8：Base64 文件字段上传

客户端发 `multipart/form-data`，其中 `file` 是**文本字段**，值是文件内容的 Base64（也接受 data URL）。可选字段是 `fileName` 和 `contentType`。

1. `Base64FileMultipartFilter`（**只挂在** `/camel/api/v1/files/*`）在 Camel 之前把文本 part 换成真正的二进制 part。
   - 同时把 `file` 从请求参数里摘掉，否则整份 Base64 会变成 Exchange header 跟着路由走。
   - 非法 Base64 直接返回 400，超限返回 413。
2. `MultipartAttachmentProcessor` 把文件 part 转成名为 `file` 的 Camel attachment。见第 5 节第 2 条。
3. `FileUploadProcessor` 读取附件内容，组装响应。

- `<post bindingMode="off">`：body 是文件，不是 JSON，所以这个 verb 单独关掉 binding，路由末尾自己 `<marshal>`。
- **大小限制有两处：** Base64 是**文本字段**，Tomcat 把它算进 `server.tomcat.max-http-form-post-size`（默认 2MB），而不是 `spring.servlet.multipart.max-file-size`。两者都在 `tutorial.yml` 里调到了 10MB，改一个要看另一个。

## 5. 迁移时改了什么（以及为什么）

| 原工程 | 这里 | 原因 |
|---|---|---|
| `camel-platform-http`（挂在 DispatcherServlet）| Camel servlet，路径前缀 `/camel` | 一个 CamelContext 只能有一个 `<restConfiguration>`，本模块用的是 servlet |
| platform-http 绑定请求时就把 multipart 转成附件 | 路由里加了一步 `multipartAttachmentProcessor` | Camel servlet 的 REST 消费者固定使用 `ServletRestHttpBinding`，**不处理 multipart**；`camel.component.servlet.attachment-multipart-binding` 只对普通 `servlet:` 端点生效。实测确认过 |
| `ArtisanHeaders` 是 `HttpRequestValues.Processor` | RestClient 的 `ClientHttpRequestInterceptor` | 这个接口是 Framework 7 才有的，6.2 没有。专属的 RestClient 本来就只服务 `ArtisanClient`，效果相同 |
| Jackson 3（`JsonMapper`）| Jackson 2（`ObjectMapper`）| Boot 3.5 |
| `http://` + `stripScheme(url)` | 保留 URL 原样的 scheme | 本模块只有 HTTPS 监听；`CamelConfig` 的 http 组件同时注册了 `http`/`https` 两个名字 |
| ArtisanClient 的 RestClient 走 Boot 自动装配 | 复用模块的 mTLS 池 `clusterPool`，加 Logbook exec handler 和 ObservationRegistry | 被调用的 mock 就是本服务的 HTTPS 端口；出站审计与模块其他客户端保持一致 |
| `config/LogbookHttpClientConfig` | 删除 | `CamelConfig.httpComponent` 已经挂了 Logbook，未知主机（如 localhost）由 `LoadBalancerRoutePlanner` 直连 |
| `ThreadPoolConfig` 的 `myTaskPool` | `tutorialTaskPool`（带 executor 指标和 TaskDecorator）| 与模块其他线程池同样的写法；不和 `aggregatePoolA` 抢线程 |
| `MyMDCScopeDecorator`、`SpanTaskDecorator`、`camel-observation-starter` | 不迁 | 模块已有同名实现；camel-observation 是本模块**有意移除**的（见 `docs/camel-observation-baggage-loss.md`）|
| `<camelContext useMDCLogging useBreadcrumb streamCache>` | 不改模块的 `<camelContext>` | 日志行首是 `[creed-simple-metrics,traceId,spanId] [线程]`，traceId 由 `TracingFilter` 按请求生成，再由 TaskDecorator 带进 `tutorial-pool-*`。实测一次 API-6 请求的所有日志共用同一个 traceId |
| API-3 调 mock 时 traceId 跨进去（W3C traceparent）| mock 收到的是一个新的 traceId | 本模块的 traceId 是**本地 baggage**，有意不随 HTTP 头传播 |
| mock 路径 `/buziVa/artisan` | `/mock/artisan` | 所有 mock 统一放在 `/mock/*` 下 |
| `demo.remote.*`、`demo.openapi.*` | `creed.tutorial.remote.*`、`creed.tutorial.openapi.*` | 本模块的配置前缀 |
| `.idea/rest/API-requests.http`（未入库）| `http/tutorial-api.http`（入库）| — |

**行为一致性**：迁移后把原工程的 jar 和本模块同时启动，对 15 个 JSON 用例、上传和非法 Base64 逐字段比对了响应。状态码和字段全部相同，只有 `exchangeId`、时间戳这类每次都会变的值不同（`scripts/parity-check.py`）。

## 6. API 文档与分组

`tutorial/openapi.yaml` 是**手写**的 OpenAPI 3.0 契约，共 14 条路径：10 个业务端点加 4 个 mock。

- **为什么手写**：springdoc 只扫描 `@RestController`，看不到 REST DSL。所以 `springdoc.packages-to-scan` 故意指向一个不存在的包，`/v3/api-docs` 是空的，真正的文档由 `OpenApiGroupController` 从这份 yaml 过滤后提供。
- **`servers` 写的是 `/simple`**，所以 Try it out 会调用打开页面的那台主机。

分组写在 yaml 根级的 `x-groups` 里。四种条件之间是 AND 关系，不写就表示不约束：

| 条件 | 含义 |
|---|---|
| `tags` | operation 带其中任一 tag 就保留 |
| `paths` | 路径匹配任一 Ant 通配就保留 |
| `methods` | HTTP 方法在列表里就保留 |
| `excludeInternal` | 去掉标了 `x-internal: true` 的端点 |

| 分组 | 选法 | 条数 |
|---|---|---|
| `all` | 不写条件 | 14 |
| `business` | 按 tag | 10 |
| `mock` | 按 tag | 4 |
| `remote-calls` | 按多个 tag：API-3 + API-7 + mock | 8 |
| `public` | `excludeInternal` | 10 |
| `orders` | path `/camel/api/v1/orders/**` + method | 9（API-8 不在这个前缀下）|

`OpenApiGroupServiceTest` 固定了这张表。

- tag 写错时**启动直接失败**；路径或方法筛出 0 条只**告警**。
- 加分组只需要改 yaml。
- `tutorial.yml` 里的 `creed.tutorial.openapi.groups` 是覆盖层，非空时整份替换 `x-groups`。
- `x-swagger-ui` 块里写 Swagger UI 的显示选项（`filter`、`docExpansion` 等），`springdoc.swagger-ui.*` 里已设置的项优先。

**改这块会遇到的四个坑：**
1. `springdoc.api-docs.enabled` 不能关，否则 Swagger UI 整个起不来。
2. `urls.primaryName` 要填显示名，不是内部名。
3. description 里的 EIP 标签要用反引号包起来，否则会被 Markdown 当成 HTML 标签吃掉。
4. yaml 是打进 jar 的，改完要重新构建才生效。

## 7. 这个目录里有什么

| 路径 | 内容 |
|---|---|
| `decks/Camel-入门指南.pptx` / `Camel-Getting-Started-Guide.pptx` | **主力手册**，26 页（API-1~6、速查表、Java DSL 对照），中英同构 |
| `decks/Camel-API6-复杂编排流程.pptx` / `Camel-API6-Complex-Orchestration.pptx` | API-6 流程图、逐节点标签表、控制台轨迹，4 页 |
| `decks/Camel-入门指南-通俗版.pptx` | 类比版，19 页，**已弃用**（保留原样，未更新）|
| `diagrams/api6-flow*.html` | 交互式流程图（archify 生成），源规格是同名 `.workflow.json` |
| `generators/` | 生成 PPT 的 python 脚本（`pip3 install python-pptx`）|
| `http/` | IntelliJ HTTP Client 请求集（含断言）|
| `scripts/test-apis.sh` | 14 个用例的一键回归 |
| `scripts/parity-check.py` | 与原工程逐字段比对响应（原工程 jar 起在另一个端口）|

**重新生成 PPT**（输出到 `decks/`）：

```bash
python3 docs/camel-tutorial/generators/build_guide_deck.py      # 中文手册
python3 docs/camel-tutorial/generators/build_guide_deck_en.py   # 英文手册
python3 docs/camel-tutorial/generators/build_api6_deck.py       # API-6（另有 _en）
```

- **中英两份手册共用一套版式代码。** 版式在 `guide_deck.py`，两份文案分别在 `guide_text_zh.py` 和 `guide_text_en.py`，两边的 key 必须一一对应。
- 所有 PPT 共用 `slidekit.py`。
- **预览第 N 页**（N 从 0 开始；Quick Look 只渲染第 1 页，所以先把那页挪到最前）：
  `python3 generators/peek.py decks/X.pptx N ../../../tmp/p.pptx && qlmanage -t -s 1400 -o ../../../tmp ../../../tmp/p.pptx`。
- 改文案后，用下面的脚本检查中英文案的 key 是否对齐：

```bash
cd docs/camel-tutorial/generators && python3 -c "
import guide_text_zh as z, guide_text_en as e
assert set(z.TEXT)==set(e.TEXT)
for k in z.TEXT: assert set(z.TEXT[k])==set(e.TEXT[k]), k
print('ok')"
```

**手册内容已按本模块更新**：技术栈、目录树、路径、端口、servlet 传输方式和运行命令都已改过。仍以 API-1~6 为主线，API-7/8 只在本 README 和 openapi 里有讲解。控制台轨迹那一页展示的是原工程的输出（带 `[route:…]` 前缀），页面上已注明。

**流程图**：`diagrams/` 下的 HTML 由 archify skill 生成。本仓库不带这个 skill，所以只直接改了 HTML 和 `.workflow.json` 两边的同一处文字（`platform-http` 改为 `servlet`）。要重新生成，得到原工程 `camel-session-0to1/.agents/skills/archify` 下执行：
`node bin/archify.mjs deliver workflow <json> <html> --quality showcase`。
