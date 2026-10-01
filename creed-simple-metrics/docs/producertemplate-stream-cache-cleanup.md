# ProducerTemplate.send 返回后读 body 报 NoSuchFileException:原因与修复

`producerTemplate.send(...)` 拿回 `Exchange` 后再 `getBody(String.class)`,下游响应一旦超过
stream caching 的 `spoolThreshold` 就失败——**临时文件在 `send()` 返回之前已经被删了**。本文记录
根因调用链(Camel 4.18.2 源码)、为什么本模块默认复现不出来、以及既要 body 又要响应 header 时的修复。
实现见 `route/PaymentStickyProcessor`。线程/上下文方面的 ProducerTemplate 问题见
[camel-producertemplate-context-propagation.md](camel-producertemplate-context-propagation.md)。

## 1. 现象

```java
Exchange result = producerTemplate.send("https://order-resource/api/order/large?...&bridgeEndpoint=true",
        e -> e.getMessage().setBody(exchange.getIn().getBody()));
String body = result.getMessage().getBody(String.class);   // ← 这里炸
```

```
org.apache.camel.TypeConversionException: Error during type conversion from type:
  org.apache.camel.converter.stream.FileInputStreamCache to the required type: java.lang.String
  ... due to java.nio.file.NoSuchFileException:
  /var/folders/.../T/camel/camel-tmp-<uuid>/cos18293820992330707.tmp
    at org.apache.camel.converter.stream.FileInputStreamCache.createInputStream(FileInputStreamCache.java:187)
    ...
    at com.creed.simple.route.PaymentStickyProcessor.process(...)
```

小响应(< `spoolThreshold`)一切正常,因为那时 body 是内存里的 `ByteArrayInputStreamCache`,没有文件可删。

## 2. 根因:`send()` 自己新建并**结束**了一个 UnitOfWork

| # | 发生了什么 | 源码 |
|---|---|---|
| 1 | `send(endpoint, pattern, processor, resultProcessor)` 用 `endpoint.createExchange()` **新建** exchange(不复用路由里的那个),交给 `ProducerCache` | `DefaultProducerTemplate.java:161-175` |
| 2 | `ProducerCache.send` 经 `SharedCamelInternalProcessor` 执行,其 advice 是 `UnitOfWorkProcessorAdvice`:新 exchange 没有 UoW → `before()` **创建一个并负责结束它** | `DefaultProducerCache.java:180`、`CamelInternalProcessor.java:1131-1168` |
| 3 | camel-http 把超过 `responsePayloadStreamingThreshold`(8 KiB)的流式响应拷进 `CachedOutputStream`;超过 `spoolThreshold` 时写入临时文件 → `FileInputStreamCache`,并在**这个新 exchange** 上注册 `onDone` 回调:`closeFileInputStreams()` + `cleanUpTempFile()` | `HttpProducer.java:568-607`、`FileInputStreamCache.java:255-302` |
| 4 | 生产者完成后 `InternalCallback.done()` 依次执行:**① `resultProcessor.process(exchange)`** → **② `AdviceIterator.runAfterTask`**,即 `UnitOfWorkHelper.doneUow()` → 触发第 3 步的 `onDone` → **删文件** | `SharedCamelInternalProcessor.java:210-229`、`CamelInternalProcessor.java:1177` |
| 5 | `send()` 返回;body 仍是那个 `FileInputStreamCache`,指向已删除的文件 → 读取即 `NoSuchFileException` | — |

一句话:**ProducerTemplate 的每次调用都是一个完整的 UnitOfWork,返回即 done,done 即清理 stream cache。**
返回的 `Exchange` 是"已完结"的,里面任何依赖 UoW 生命周期的资源都已失效。

## 3. 为什么本模块默认复现不出来

需要**同时**满足两个条件,而本模块默认两个都不满足:

1. **Camel 4 默认不落盘。** `spoolEnabled` 默认 `false`;只写 `<streamCaching spoolThreshold="131072"/>`
   不会产生任何临时文件,大响应全部留在内存(`DefaultStreamCachingStrategy.java:410`:
   `if (spoolEnabled && (spoolThreshold > 0 || ...))`)。必须显式 `spoolEnabled="true"`。
2. **Logbook 会把 JSON 响应"去流化"。** 出站 hc5 链上的 `LogbookHttpExecHandler` 对需要记录 body 的响应
   调 `HttpEntities.copy(entity)` 并 `setEntity(ByteArrayEntity)`(`RemoteResponse.java:97`)。
   `ByteArrayEntity.isStreaming() == false`,而 camel-http 只对 `isStreaming()` 的 entity 做 stream
   caching(`HttpProducer.java:569`),否则原样返回 `ByteArrayInputStream`。本模块
   `creed.logbook.allowed-content-types: [application/json]`,所以 **JSON 响应永远不会变成
   `FileInputStreamCache`**;非 JSON(如 `text/plain`)才保持流式。

> 生产上遇到此异常,说明那条调用链上 spool 已开启,且响应没有被 Logbook(或其他组件)整体缓冲。
> 不要把"本地 JSON 调用没问题"当作已修复——是被 Logbook 掩盖了。

### 本地复现方法

- `creed-resource-order` 的 `GET /api/order/large?kb=256&contentType=text/plain`:约 `kb` KiB 的 JSON 文本,
  `Content-Type` 可指定(非 JSON 类型绕开 Logbook 缓冲)。
- `camel-context.xml`:`<streamCaching ... spoolEnabled="true" spoolThreshold="131072"/>`。
- 起 order(primary)+ simple-metrics,`curl -k https://localhost:8096/simple/camel/api/payment`
  (`fetch-payment` 第一步就是 `PaymentStickyProcessor`)。

## 4. 修复:在 UoW 结束**之前**物化 body

关键就是第 2 节第 4 步的顺序:**`resultProcessor` 先于 `doneUow` 执行**。在那里把 body 转成
`String`(或 `byte[]`),返回的 `Exchange` 里就不再引用临时文件;header 不受影响。

### 4.1 只要 body:`Class<T>` 重载

```java
String body = producerTemplate.requestBodyAndHeaders(uri, reqBody, headers, String.class);
```

带 `Class<T>` 的 `request*` 重载内部传的正是 `new ProducerTemplateResultProcessor(type)`
(`DefaultProducerTemplate.java:437-441`),同一个机制。

### 4.2 body + 响应 header:`send(..., resultProcessor)`(本模块采用)

```java
Endpoint ep = exchange.getContext().getEndpoint(
        "https://order-resource/api/order/large?kb=256&contentType=text/plain&bridgeEndpoint=true");
Exchange result = producerTemplate.send(ep, ExchangePattern.InOut,
        e -> e.getMessage().setBody(exchange.getIn().getBody()),             // 请求
        e -> e.getMessage().setBody(e.getMessage().getBody(String.class)));  // doneUow 之前执行
if (result.getException() != null) {
    throw new RuntimeCamelException(result.getException());
}
String body = result.getMessage().getBody(String.class);
Object status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE);  // header 完好
```

`ProducerTemplate` 接口上带 `resultProcessor` 的只有 `Endpoint` 版本,所以要先 `getEndpoint(uri)`。

实测(spool 开、242 KB `text/plain`):修复前 500 + `NoSuchFileException`;修复后
`order response status=200 contentType=text/plain bodyLength=242703`,路由 200。

### 4.3 body 太大、不想整块进堆:把清理挂到外层 UoW

在新 exchange 上设置 `ExchangePropertyKey.STREAM_CACHE_UNIT_OF_WORK = exchange.getUnitOfWork()`
(外层路由的 UoW),`TempFileManager.addExchange` 会把清理回调注册到**外层** UoW 而不是新 exchange
(`FileInputStreamCache.java:287-299`)——split/multicast + AggregationStrategy 就是靠它让子路由的
stream cache 活到主路由结束。body 保持为可重复读的 `FileInputStreamCache`,文件在外层 exchange 完成时
删除。代价:依赖内部属性语义,且必须保证外层 UoW 存在、未结束;没有明确的内存问题时用 4.2。

### 不推荐

| 方案 | 问题 |
|---|---|
| endpoint 加 `disableStreamCache=true` | body 变成 hc5 原始流,而 camel-http 把"消费并关闭响应"同样挂在新 exchange 的 `onDone` 上(`HttpProducer.java:292-305`)——同一个时机失效,读不到;且是一次性流 |
| 调大 `spoolThreshold` / 关 spool | 只是把问题推迟到更大的响应,或放弃落盘 |
| 返回后自己 `getBody(String.class)` 再 try/catch | 文件已删,无从挽救 |

## 5. 顺带的两个坑

- **`send()` 不抛异常。** 下游失败(连接拒绝、无可用实例、`throwExceptionOnFailure` 的非 2xx)记录在
  `result.getException()` 上,不检查就会拿着 `null` body 继续走。`request*` 系列才会抛。
- **转发入站 header 时注意 `bridgeEndpoint=true`。** 从 servlet 入站 exchange 拷贝的 header 带
  `CamelHttpPath`/`CamelHttpQuery`/`CamelHttpUri`;`bridgeEndpoint=true` 会把 `CamelHttpPath`
  **追加**到 endpoint 路径、用 `CamelHttpQuery` **覆盖** endpoint 查询串。把这些 header 一起传给
  ProducerTemplate 前先 `remove`。
