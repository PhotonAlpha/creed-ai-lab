# ProducerTemplate 线程模型与 traceId/上下文传播:原理、方案与踩坑

`asyncRequestBody*` 会把路由执行切到 ProducerTemplate 内部线程池,而 traceId(Observation scope /
Brave span / baggage → MDC)与 `StickyContextHolder` 全是 ThreadLocal 语义——本文记录这条断链是
怎么发生的、曾经"看似没断"的假象来自哪里,以及最终方案:**context-propagating executor +
自定义 `ThreadLocalAccessor`**(`CamelConfig.producerTemplate` /
`StickyContextThreadLocalAccessor`)。审计/计时观测的三层设计见
[camel-audit-observability.md](camel-audit-observability.md)。

## 1. ProducerTemplate 线程模型:它自己没有"转发线程池"

| API | 执行线程 | 说明 |
|---|---|---|
| `sendBody` / `requestBody*`(同步) | **调用者线程** | 全程不切线程;`direct:` 也是同线程方法调用。并发度 = 调用方线程数,瓶颈在 hc5 连接池 |
| `asyncSend` / `asyncRequestBody*` | **内部懒加载线程池** | `DefaultProducerTemplate` 用 `CompletableFuture.supplyAsync(task, getExecutorService())` 裸提交 |

懒加载池来自 `ExecutorServiceManager.newDefaultThreadPool(this, "ProducerTemplate")`,即默认
thread pool profile:**core 10 / max 20 / keepAlive 60s / queue 1000 / CallerRuns**。三个后果:

- **`asyncRequestBody` + 立即 `join()` 是反模式**:同步语义绕道线程池走一遍,全应用吞吐被
  max 20 卡死(超出先排 1000 深的队列再 CallerRuns),不如直接同步调用;
- `ProducerCache`(LRU,默认 1000)缓存的是"每个 endpoint 一个 producer",与并发度无关;
  `HttpProducer` 是单例线程安全的,真正并发上限 = hc5 连接池(`creed.camel.http.max-total` /
  `max-per-route`);
- 高并发调优顺序:servlet 容器线程数 → hc5 连接池(LB 场景 per-route 尤其关键)→ 只有真用
  async API 才需要管这个 executor。

## 2. 断链原理:traceId 的三个 ThreadLocal 载体

`supplyAsync` 提交的裸 Runnable 什么都不带,池线程上缺的是三样东西:

1. **Observation scope**(`ObservationRegistry` 的 ThreadLocal)——缺了它,
   `ObservationExecChainHandler` 找不到 parent,下游 `traceparent`/b3 另起新 trace;
2. **Brave span scope**(`CurrentTraceContext`)——缺了它,`MyMDCScopeDecorator` 不触发;
3. **MDC**(logback `%X{traceId}`)——本身就是 ThreadLocal map。

关键认知:**baggage(`correlationTraceId` 的 UUID)不存在线程里,存在 `TraceContext` 对象里**。
所以只要把"scope 在池线程上重新打开"这一件事做对,三样东西就会连锁恢复:
restore Observation scope → tracing handler 重开 Brave scope → `CorrelationScopeDecorator`
把 baggage 写进 MDC,任务结束逆序清理。

## 3. 曾经"看似没断"的假象:remote baggage 时代的三棒接力

改 `SingleBaggageField.local` 之前,池线程日志里能看到 UUID,靠的是一条**完全不同的、
与线程池无关的**链路:

1. `TracingFilter` 把 UUID 写进当前 `TraceContext` 的 baggage;`SingleBaggageField.remote`
   使它以 `correlationTraceId` header 随请求传播;
2. `PaymentStickyProcessor` 把入站消息**全部 headers** copy 进 `asyncRequestBodyAndHeaders`
   → 传播头(b3 + baggage)跟着到了池线程;
3. `camel-observation-starter` 的 RoutePolicy 在池线程上 `direct:fetch-order` 路由开始时
   从消息 headers **提取**父上下文建 span 并激活 scope → `MyMDCScopeDecorator` 写 MDC。

这条链的脆弱处(也是它是"假象"的原因):

- **依赖入站请求带传播头**:从带 observation 的 RestClient/网关进来才接得上;curl 裸调时
  提取失败 → 另起新 root trace,日志"有 traceId"但与入站对不上;
- **`<multicast parallelProcessing>` 分支断链**(camel-observation 异步 EIP 已知问题)同根因——这个
  根因后来证实不止影响异步分支,连最普通的单跳同步 `<to>` 调用也中招,完整分析与最终修复(移除
  `camel-observation-starter`)见 [camel-observation-baggage-loss.md](camel-observation-baggage-loss.md);
- 改成 `local` 后 headers 里不再有 baggage,这条链对 UUID 彻底失效。

## 4. 最终方案:context-propagating executor(`CamelConfig.producerTemplate`)

```java
@Bean
ProducerTemplate producerTemplate(CamelContext camelContext) {
    ContextRegistry.getInstance()
            .registerThreadLocalAccessor(new StickyContextThreadLocalAccessor());
    ProducerTemplate template = camelContext.createProducerTemplate();
    ExecutorService pool = camelContext.getExecutorServiceManager()
            .newDefaultThreadPool(template, "ProducerTemplate");
    ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
    template.setExecutorService(ContextExecutorService.wrap(pool, snapshotFactory::captureAll));
    return template;
}
```

要点:

- **提交时快照、执行前恢复、结束后还原**:`captureAll()` 在调用线程上抓取所有注册的
  `ThreadLocalAccessor`(Observation scope + 下述 sticky)——所以时序上"先 set 后提交"的值
  才会被带过去;
- **生命周期**:裸池经 `ExecutorServiceManager` 创建,CamelContext 停止时由 Camel 关闭;
  交给 template 的只是包装层(`setExecutorService` 设置的外部 executor,template 自己不关);
- **覆盖自动配置**:camel-spring-boot 的 `CamelAutoConfiguration.producerTemplate` 带
  `@ConditionalOnMissingBean`,自定义 bean 直接接管,无冲突;
- **依赖**:`io.micrometer:context-propagation`(micrometer-tracing 已传递引入,pom 里显式
  声明因为代码直接编译到它);
- 与 remote-baggage 方案相比的收益:**不依赖入站传播头**,curl 裸调也能接上(上下文从调用
  线程直接带过去,不走 HTTP 头)。

### Spring `TaskDecorator` 为什么帮不上

`ContextPropagatingTaskDecorator` 只装饰 **Spring 管理的 `TaskExecutor`**;Camel 的池是
`ExecutorServiceManager` 自建的裸 `ThreadPoolExecutor`,Spring 根本碰不到。若想自己控制异步
边界,等价做法是:template 保持同步调用 + 自己的
`ThreadPoolTaskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator())` 上
`supplyAsync`——池归自己配置/监控,机制与本方案同源(都是 `ContextSnapshot`)。

## 5. 自定义 `ThreadLocalAccessor`:让 `StickyContextHolder` 一起被搬运

`captureAll()` 只搬**注册过 accessor** 的 ThreadLocal;`StickyContextHolder` 是普通
ThreadLocal,不注册就照旧断链。注册三步(`StickyContextThreadLocalAccessor`):

```java
public final class StickyContextThreadLocalAccessor implements ThreadLocalAccessor<String> {
    public static final String KEY = "creed.stickyId";
    @Override public Object key()                  { return KEY; }
    @Override public String getValue()             { return StickyContextHolder.get(); }   // 快照:捕获
    @Override public void setValue(String value)   { StickyContextHolder.set(value); }     // 恢复:有值
    @Override public void setValue()               { StickyContextHolder.clear(); }        // 恢复:快照为 null → 显式清
    @Override public void reset()                  { StickyContextHolder.clear(); }
}

// 注册(同 key 重复注册会替换,幂等):
ContextRegistry.getInstance().registerThreadLocalAccessor(new StickyContextThreadLocalAccessor());
```

语义要点:

- **`getValue()` 返回 null = "无值"**,恢复时走无参 `setValue()`(即显式 clear),而不是
  "保留池线程残留"——包装后的池线程不可能泄漏上一个请求的 stickyId,比
  `StickyContextHolder` 原有的 "always overwrite" 契约更强;
- 注册在**全局** `ContextRegistry` 上:应用里一切基于 `captureAll()` 的传播
  (Reactor、`ContextPropagatingTaskDecorator`、本 executor)都会捎带它;
- 不想写类也可用便捷重载:
  `registerThreadLocalAccessor(KEY, StickyContextHolder::get, StickyContextHolder::set, StickyContextHolder::clear)`;
- 另一种注册途径是 ServiceLoader(`META-INF/services/io.micrometer.context.ThreadLocalAccessor`,
  micrometer 自己的 `ObservationThreadLocalAccessor` 即此方式),适合 library;应用内显式注册
  更可发现。

## 6. 坑点清单(速查)

| # | 坑 | 机制 | 对策 |
|---|---|---|---|
| 1 | `asyncRequestBody` + 立即 `join()` | 同步语义绕道默认池,吞吐卡在 max 20 | 无并行需求就用同步 `requestBody*` |
| 2 | 池线程 traceId 断链 / 下游另起 trace | `supplyAsync` 裸提交,三个 ThreadLocal 载体全丢 | 本文方案(wrap executor) |
| 3 | Spring `TaskDecorator` 不生效 | 只装饰 Spring 管理的 executor,Camel 池是自建的 | 换 `ContextExecutorService.wrap`,或自管池 + 同步 template |
| 4 | `camel.main.use-mdc-logging` 治不了第一跳 | `MDCUnitOfWork` 在 **Exchange 创建时**快照 MDC,而 async 的 Exchange 在池线程上才创建,快照到的已是空 | 只作为补充(Exchange 进路由后 Camel 内部切线程时不丢) |
| 5 | "看似接上了"的假象 | remote baggage + headers copy + camel-observation 提取;curl 裸调即穿帮 | 改 local 后以 executor 传播为准,不依赖入站头 |
| 6 | `MDCContext.getValue` 的 MDC fallback | baggage 为空时读池线程残留 MDC,掩盖断链 | 建议移除 fallback,让断链显式暴露 |
| 7 | `StickyContextHolder` 残留/不传播 | 普通 ThreadLocal,`captureAll()` 不认识 | 注册 `StickyContextThreadLocalAccessor`(§5) |
| 8 | `<multicast parallelProcessing>` 分支断链 | 路由自有池,不在 template executor 覆盖范围;同根因也曾在普通单跳 `<to>` 上复现,见 #10 | 需要时对 EIP 的 `executorServiceRef` 同法包装 |
| 9 | executor 生命周期 | template 不关外部 executor;自 new 的池没人关 | 裸池走 `ExecutorServiceManager`,Camel 停机时关闭 |
| 10 | 单跳同步 `<to>` 调用里 baggage 也会丢 | `camel-observation-starter` 给每个 endpoint 建的 producer/CLIENT span 用自己的 parent 查找(`ActiveSpanManager`/`ObservationRegistry.getCurrentObservation()`),不保证继承当前 Brave `TraceContext.extra` | 已修:移除 `camel-observation-starter` 依赖,详见 [camel-observation-baggage-loss.md](camel-observation-baggage-loss.md) |

## 7. 验证方法

起服务后用**不带任何 tracing/baggage 头**的 curl 打 `/camel/api/payment...`:

1. `TracingFilter` 入站日志 `correlateId:[...]` 与
   `[Camel (camel-1) thread #N - ProducerTemplate]` 线程日志的 `%X{traceId}` **一致** → 传播生效
   (裸 curl 下一致才是真传播;remote 时代裸 curl 必对不上);
2. 池线程上 `LB resolved -> instance=...`(lbAudit)与 Logbook 审计块同 traceId → hc5 observation
   接上了 parent;
3. 连打两个不同 stickyId 的请求,确认池线程复用时无 stickyId 串号(§5 的显式 clear 语义)。
