# 在每个 processor 里拿到原始 REST 请求：exchange property `originalRequest`

需求：`/camel/api/fulfillment` 的每个 processor（包括 multicast 分支和 wireTap）都要知道原始请求。本文记录
为什么 `getUnitOfWork().getOriginalInMessage()` 不行、统一的做法、以及这个 property 能传到哪里、传不到哪里。
实现见 `config/OriginalRequestRoutePolicyFactory`。Camel 4.23.0 源码。

## 1. `getUnitOfWork().getOriginalInMessage()` 为什么不行

用一个复刻 fulfillment 结构的临时测试验证过（stream body → 模拟 json binding → multicast ×2 → wireTap）：

- **默认直接抛异常**：Camel 4 的 `allowUseOriginalMessage` 默认 `false`，`DefaultUnitOfWork.getOriginalInMessage()`
  抛 `IllegalStateException: AllowUseOriginalMessage is disabled`。项目里没有打开它，所以每个 processor 第一行就失败。
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

## 3. property 能传到哪里

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

## 4. 验证

2026-11-06，`:8096`，连运行中的 catalog（18081）/ order（18091）：

- 成功路径：请求处理、两个下游调用分支、filter、shipping、risk、wireTap（`PlainProcessor`）7 个步骤，跑在 servlet
  线程、`agg-poolA`、`notify-poolB` 上，打印的都是各自请求的完整原始 JSON。
- 失败路径（`failCatalog=true`）：`failureResponseProcessor` 也拿到了；`failCatalog` 被正确读到，说明 binding 不受影响。
- `GET /api/hello`、API-8 multipart 上传：200，不受影响。
