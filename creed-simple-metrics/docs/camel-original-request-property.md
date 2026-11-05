# 在每个 processor 里拿到原始 REST 请求：exchange property `originalRequest`

需求：`/camel/api/fulfillment` 的每个 processor（包括 multicast 分支和 wireTap）都要知道原始请求。本文记录
为什么 `getUnitOfWork().getOriginalInMessage()` 不行、统一的做法、`RoutePolicyFactory` 为什么能起作用、本模块两个
factory 的分工、对性能的影响，以及这个 property 能传到哪里、传不到哪里。实现见 `config/OriginalRequestRoutePolicyFactory`。
Camel 4.23.0 源码。

## 1. `getUnitOfWork().getOriginalInMessage()` 为什么不行

用一个复刻 fulfillment 结构的临时测试验证过（stream body → 模拟 json binding → multicast ×2 → wireTap）：

- **默认直接抛异常**：Camel 4 的 `allowUseOriginalMessage` 默认 `false`，`DefaultUnitOfWork.getOriginalInMessage()`
  抛 `IllegalStateException: AllowUseOriginalMessage is disabled`。当时项目里没有打开它，所以每个 processor 第一行就失败。
  （2026-11-06 起 `camel-context.xml` 设了 `allowUseOriginalMessage="true"`，按要求保留：不再抛异常，但下面的问题仍在，
  processor 仍应读 property。）
- **打开后也拿不到原始请求**：

  | 位置 | 拿到的"原始消息" |
  |---|---|
  | 第一个 processor | 原始 JSON |
  | multicast 分支、wireTap | 分叉那一刻的 body（已经是 Map） |
  | 主 exchange 上后面的 processor | 空字符串 |

  multicast 的每个分支和 wireTap 的副本都会新建自己的 UnitOfWork，它的 original 是新建时复制的 In 消息；主 exchange
  上的 original body 和当前 body 是同一个 stream cache，读过一次后再读就是空。打开开关还会让每个 exchange 都复制
  一份 In 消息，开销是全局的。

## 2. 做法：REST 入口统一存成 exchange property

`OriginalRequestRoutePolicyFactory`（`@Component`，`<camelContext>` 工厂 bean 自动发现，作用于所有 route，route 不用改）：

1. 只处理 `servlet:` 入口，每个 exchange 只存一次——嵌套的 `direct:` route 会再次触发 `onExchangeBegin`，看到
   property 已存在就跳过（和 `RestApiAuditRoutePolicyFactory` 同一套守卫）。
2. **时机在 REST binding 之前**：`RouteReifier` 先注册 route policy advice，再注册 `RestBindingAdvice`，所以此时 body
   还是原始请求，不是 `bindingMode="json"` 解析出的 Map。
3. body 是一次性的流：读成 String 后**放回 body**，binding 照样能解析；同时存进 `originalRequest`。
4. 只处理 JSON 请求（`Content-Type` 含 `json`）。multipart 上传（tutorial API-8）必须保留原来的流。

读取：

```java
import static com.creed.simple.config.OriginalRequestRoutePolicyFactory.ORIGINAL_REQUEST;

exchange.getProperty(ORIGINAL_REQUEST, String.class)
```

## 3. `RoutePolicyFactory` 为什么能起作用

一条链，从 Spring bean 到每次请求的回调：

1. **发现**：classic `<camelContext>` 的工厂 bean `AbstractCamelContextFactoryBean`（camel-core-xml）在初始化时
   `getRegistry().findByTypeWithName(RoutePolicyFactory.class)`，把 Spring 容器里每个 `RoutePolicyFactory` 加进
   `CamelContext.addRoutePolicyFactory(...)`。所以只要是 `@Component` 就生效，不用在 XML 里引用。启动日志可以确认：

   ```
   Using custom RoutePolicyFactory with id: originalRequestRoutePolicyFactory and implementation: ...
   Using custom RoutePolicyFactory with id: restApiAuditRoutePolicyFactory and implementation: ...
   ```

2. **每条 route 装一次**：`RouteReifier` 创建每条 route 时，对每个 factory 调 `createRoutePolicy(context, routeId, definition)`，
   返回的 policy 放进这条 route 的 policy 列表，再 `internal.addRoutePolicyAdvice(...)` 装到 route 的
   `CamelInternalProcessor` 上。作用于**所有** route（REST route、`direct:` route、tutorial route……），这是在 route
   **创建时**做的，不是每次请求做。本模块两个 factory 都返回同一个 policy 实例，不按 route 区分。
3. **每次进入 route 回调**：`CamelInternalProcessor.RoutePolicyAdvice` 在 exchange 进入这条 route 时（`before()`）依次调
   每个 policy 的 `onExchangeBegin(route, exchange)`，离开时（`after()`）调 `onExchangeDone`。
4. **顺序**：同一个 `RouteReifier` 里，policy advice 先于 `RestBindingAdvice` 注册，advice 的 `before()` 按注册顺序执行——
   所以 `onExchangeBegin` 看到的是 binding 之前的原始 body。

由此来的三个特性：

- **一个 exchange 会触发多次。** fulfillment 一次请求会进入 7 条 route（REST route → `fulfillment` → 两个 fetch → 两个
  enrich → notification），每次进入都回调一次，multicast 分支和 wireTap 副本进入 `direct:` route 也一样。所以两个 policy
  都要守卫：`getFromEndpoint()` 以 `servlet:` 开头（副本也继承了这个 from endpoint），再用一个 exchange property 判断
  "已经做过"（副本也复制了这个 property，于是跳过）。
- **`onExchangeBegin` 里的异常会被吞掉**：`RoutePolicyAdvice` 捕获后只打一条 WARN（"This exception will be ignored"），
  请求照常继续。policy 不能用来拦截请求；如果读 body 失败，后面的 binding 可能拿到一个已经被读过的流。
- **回调在调用方线程上同步执行**：REST route 的回调在 Tomcat 的请求线程上，分支的在 `agg-poolA` 上——policy 里的代码直接
  算进请求耗时，所以必须便宜。

## 4. 本模块的两个 `RoutePolicyFactory`

两者机制和守卫相同（只处理 `servlet:` 入口、每个 exchange 只做一次），做的事不同：

| | `RestApiAuditRoutePolicyFactory` | `OriginalRequestRoutePolicyFactory` |
|---|---|---|
| 目的 | 每个 REST 请求结束时打一行审计日志 | 让每个 processor 都能拿到原始请求体 |
| `onExchangeBegin` 做什么 | 记下 `CamelHttpMethod` / `CamelHttpUri` / `CamelHttpQuery`，挂一个 `RestApiAuditSynchronization`（`addOnCompletion`） | 读 JSON body 成 String、放回 body、存进 property `originalRequest` |
| "已做过"的标记 | property `CreedRestApiAuditAttached` | property `originalRequest` 本身 |
| 为什么必须在入口做 | 后面的 `fetch-*` route 会 `removeHeaders pattern="CamelHttp*"`，到结束时请求行已经没了 | binding 之后 body 变成 Map，原始流也已读完 |
| 输出 | 请求结束（UnitOfWork 完成）时：`CAMEL-AUDIT POST /camel/api/fulfillment status=200 in 27ms exchangeId=... route=...`，失败是 WARN | 不输出，只留下 property 给后面的 processor 用 |
| 适用请求 | 所有 REST 请求 | 只有 `Content-Type` 含 `json` 的 |

二者互不依赖，顺序无关。

## 5. 对性能的影响

按代码路径分析，未做基准测试：

- **装配**：每条 route 创建时调一次 `createRoutePolicy`，启动期开销，可以忽略。
- **每次进入 route，每个 policy**：一次 `onExchangeBegin` + 一次 `onExchangeDone`（`RoutePolicySupport` 默认空实现）。
  守卫不通过时只是一次字符串 `startsWith` 加一次 property 查找就返回。fulfillment 一次请求进入 7 条 route、两个 policy，
  共约 14 次这样的调用，和一次下游 HTTP 调用（毫秒级）比可以忽略。
- **真正有成本的只有 `OriginalRequestRoutePolicyFactory` 的那一次**：
  - 把请求体整个读成一个 String（一次拷贝，内存 ≈ body 大小），并一直留到 exchange 结束；
  - 之后 binding 解析的是这个 String 而不是流——解析工作量相同，只是不再走 stream cache。

  对 JSON 接口没问题；大请求体（几 MB 以上）会占同等内存，这类接口应排除在外（现在已经排除了非 JSON 请求）。
- **对比 `allowUseOriginalMessage="true"`**（现在 `camel-context.xml` 里是打开的，这部分开销当前确实存在）：那个开关会给**每个** exchange——包括每个 multicast 分支和 wireTap 副本——新建
  UnitOfWork 时都复制一份 In 消息（必要时还要缓存流），而且拿到的内容还不对（见第 1 节）。property 方案只在入口复制一次，
  副本之间共享同一个 String 引用。
- **`RestApiAuditRoutePolicyFactory`**：每个请求读 3 个 header、挂一个回调，结束时打一行日志，开销同样可以忽略。

## 6. property 能传到哪里

property 挂在 exchange 上：后面处理的还是这个 exchange、或者是 Camel 从它复制出来的 exchange，就能拿到；Camel
新建 exchange、或者出了 JVM，就拿不到。**和同步还是异步无关。**

能拿到：

| 场景 | 原因 | 依据 |
|---|---|---|
| 同一 route 里依次执行的 processor、bean | 一直是同一个 exchange 对象 | 实测 |
| 进入另一条 route（`direct:`） | 还是同一个 exchange | 实测 |
| `multicast`（包括 `parallelProcessing` 在别的线程池） | 每个分支是副本，复制时带上 property | 实测（`agg-poolA`） |
| `wireTap`（默认 `copy="true"`） | `configureCopyExchange` → `createCorrelatedCopy`，property 一起复制 | 实测（`notify-poolB`） |
| `split`、`recipientList`、`enrich` | 和 multicast 一样复制 exchange | 按源码机制，未实测 |
| `onCompletion`、`onException`、`doTry`/`doCatch` | 同一个 exchange | 按机制 |

拿不到：

| 场景 | 原因 |
|---|---|
| `wireTap copy="false"` | `configureNewExchange` 新建一个空 exchange，不带任何 property |
| processor 里用 `ProducerTemplate` 发起调用 | 新建 exchange（如 `tutorial/service/RemoteCallService`），被调用的 route 里没有 |
| 自定义 `AggregationStrategy` 自己 new exchange 当结果 | 汇总结果里没有；现有 strategy 返回已有的 exchange，所以还在 |
| 发到外部系统（http / jms / kafka …），或从别的 consumer 进来 | property 只在本进程内存里，不上线路 |
| 非 REST 入口（timer 等），或请求体不是 JSON | 根本没存 |

`ProducerTemplate` 那一侧需要的话，发送前手动带过去：

```java
producerTemplate.send(uri, e -> {
    e.setProperty(ORIGINAL_REQUEST, exchange.getProperty(ORIGINAL_REQUEST));
    // ...
});
```

另外几点：

- **复制是浅拷贝**，副本和原 exchange 指向同一个值。存 String（不可变）是安全的；改存 Map 之类可变对象，并行分支之间
  会互相影响。
- 在分支里改这个 property 只改到副本，不会传回主 exchange。
- 每个请求的 body 在内存里留到 exchange 结束——JSON 接口没问题，不适合很大的请求体。

## 7. 验证

2026-11-06，`:8096`，连运行中的 catalog（18081）/ order（18091）：

- 成功路径：请求处理、两个下游调用分支、filter、shipping、risk、wireTap（`PlainProcessor`）7 个步骤，跑在 servlet
  线程、`agg-poolA`、`notify-poolB` 上，打印的都是各自请求的完整原始 JSON。
- 失败路径（`failCatalog=true`）：`failureResponseProcessor` 也拿到了；`failCatalog` 被正确读到，说明 binding 不受影响。
- `GET /api/hello`、API-8 multipart 上传：200，不受影响。
