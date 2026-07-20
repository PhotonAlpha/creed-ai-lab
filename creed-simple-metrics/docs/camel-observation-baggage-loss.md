# `correlationTraceId` 在 camel-http 调用里丢失:排查过程、根因与 `camel-observation-starter` 的移除

本文记录一次真实的踩坑排查:`MyMDCScopeDecorator.CORRELATION_FIELD`(local baggage 字段
`correlationTraceId`,见 [camel-audit-observability.md](camel-audit-observability.md) 与
[camel-producertemplate-context-propagation.md](camel-producertemplate-context-propagation.md))在
`fetch-catalog`/`fetch-order`/`fetch-payment` 这类**单跳、同步**的 `<to https://xxx-resource/...>`
调用里也会丢——不只是之前记录的 `<multicast parallelProcessing>` 异步分支。根因是 `camel-observation-starter`
（`org.apache.camel.observation.MicrometerObservationTracer`),现已从 `pom.xml` 移除。

## 现象

单条 curl 打 `/camel/api/catalog`(无 multicast、无线程切换,全程同一个 servlet 线程):

- 入口 `TracingFilter` 正确记下 `correlateId`;
- `fetch-catalog` route 里、`<to https://catalog-resource/...>` **之前**,`CORRELATION_FIELD.getValue()`
  仍是入站那个值;
- 但 `HttpComponent` 的 hc5 exec chain 里(`CamelLoadBalancerAuditExecHandler`,`addExecInterceptorLast`,
  最内层)读到的却是 `null`。

## 排查过程(含被推翻的假设)

### 假设 1(错):是新加的 `ObservationExecChainHandler` 自己把 MDC 搞坏了

`CamelConfig.httpComponent` 里加 `ObservationExecChainHandler`(hc5,Micrometer 官方 HTTP 客户端埋点)
的那次改动之后,`correlationTraceId` 就开始丢——很自然先怀疑它。反编译本地仓库实际解析到的
`micrometer-core-1.15.1.jar`,`ObservationExecChainHandler.execute(ClassicHttpRequest, Scope, ExecChain)`
(同步版,camel-http 走的就是它)的字节码显示:

```
Observation.start()  →  chain.proceed(request, scope)  →  observation.stop()
```

**从没调用 `openScope()`/`.scoped()`/`.observe()`**——它不持有、也不切换当前 trace 的 scope,理论上不该
影响 `chain.proceed()` 内部读到的 MDC/baggage。

**验证方式**:在 `CamelLoadBalancerAuditExecHandler`(exec chain 最内层)临时加两行日志,直接打
`CORRELATION_FIELD.getValue()`(注意不能通过 `MDC.get("traceId")` 间接看——见下面「方法论」一节的
fallback 陷阱),分别在 `ObservationExecChainHandler` **开启**和**注释掉**两种状态下各打一次同一个
endpoint,对比结果。

**结果**:两次都是 `null`。假设 1 不成立——它只是恰好和真正的问题同框。

### 假设 2(错):`direct:catalog` → `direct:fetch-catalog` 的 route 跳转把它冲掉了

`catalog` route 用 `<to uri="direct:fetch-catalog"/>` 跳到另一个 Camel route,而启动日志显示
`camel-observation-starter` 挂了一个 `RoutePolicyFactory`(`micrometerObservationTracer`)——它会给
**每个 route 边界**建自己的 Observation/span,先怀疑是这层跳转把 local baggage 冲断了(与
`camel-producertemplate-context-propagation.md` 记录的 `<multicast parallelProcessing>` 断链看起来像
同一类问题,只是发生在 route 边界而非线程切换)。

**验证方式**:写一个 `TraceDiagnosticProcessor`(`<process ref="traceDiagnosticProcessor"/>`),同时打印
`CORRELATION_FIELD.getValue()` 和两个相关 MDC key,分别挂在 `catalog` route 跳转**前**和
`fetch-catalog` route 入口(跳转**后**)——同一线程、同一请求,前后夹逼这次跳转。

**结果**:两处日志的 `baggage.correlationTraceId` **都正确**、完全一致。route 边界本身没有问题,
假设 2 也不成立。

### 定位:窗口收窄到「`<to https://...>` 之前」→「hc5 exec chain 入口」之间

两次排除法之后,已知好的最后一个点(`fetch-catalog` route 里、`<removeHeaders>`/`<to>` 之前的
`TraceDiagnosticProcessor`)和已知坏的第一个点(hc5 exec chain 入口)之间,只隔着 Camel 把控制权交给
`https://catalog-resource/...` 这个 endpoint 的 **producer 调用**本身。这正是 `camel-observation-starter`
给每个 `<to>` endpoint 建 **CLIENT span** 的地方——不是 route 级(`RoutePolicyFactory`,假设 2 已排除),
而是 producer 级。

反编译 `camel-observation` 4.18.2 的 `MicrometerObservationTracer.startSendingEventSpan()`
(`org.apache.camel.observation.MicrometerObservationTracer`,借助本地仓库的 `-sources.jar`/字节码交叉
核对)确认了机制:

- 它自己 `Observation.createNotStarted(name, contextSupplier, observationRegistry)` 建一个新的
  Observation,parent 通过一个静态方法 `getParentObservation(SpanAdapter)` 找——这是 Camel 自己的
  `ActiveSpanManager`(Exchange 属性挂的 span 栈),**不是** Brave 的 `CurrentTraceContext`;
- 找不到时 fallback 到 `ObservationRegistry.getCurrentObservation()`(Micrometer 自己的 ThreadLocal
  当前 observation),再从它的 `TracingContext` 里取 `Span`,`Tracer.withSpan(span)` 开一个
  `SpanInScope`——但这个 scope **只在 span 创建那一瞬间**打开又关掉,不包住实际的发送过程;
- `camel-tracing`(`camel-observation` 依赖的基座 SPI)整体是 Zipkin/OpenTracing 时代跨进程传播的设计:
  `org.apache.camel.tracing.propagation.CamelHeadersExtractAdapter`/`InjectAdapter` 走消息 header,
  `SpanDecorator`(`HttpSpanDecorator` 等)也是按「这次调用可能是远程」建模的。

这套机制对**跨进程**(header 传播)和**同进程但走 Brave ambient `CurrentTraceContext`**这两种模型都不是
天然契合——它有自己的一套 parent 查找/span 管理,不依赖也不维护 Brave 的当前 trace context。而
`correlationTraceId` 改成 `SingleBaggageField.local`(见 `TracingConfig`)之后,它的值只存在于 Brave
`TraceContext.extra`(当前 trace context 对象上），camel-observation 建的这个新 span/Observation 如果
底层 `TraceContext` 是它自己另起的(或至少不保证复用同一个 `extra` 引用),`CORRELATION_FIELD.getValue()`
在这个新 context 下自然就是空的——这和 `camel-producertemplate-context-propagation.md` 记录的
`<multicast parallelProcessing>` 分支断链**同根因**,只是那份文档写的时候低估了影响范围:不止异步 EIP
分支,连最普通的单跳同步 `<to>` 调用也中招。

## 结论与修复

**根因**:`camel-observation-starter`(`org.apache.camel.observation.MicrometerObservationTracer`)给
每个 `<to>` endpoint 建的 producer/CLIENT span,用的是它自己的 parent 查找机制
(`ActiveSpanManager`/`ObservationRegistry.getCurrentObservation()`),不保证正确继承当前 Brave
`TraceContext` 的 `extra`(local baggage 的存储位置)。

**修复**:直接把 `camel-observation-starter` 依赖从 `pom.xml` 删掉,而不是用它自带的
`camel.observation.exclude-patterns` 配置项只排除 `catalog-resource`/`order-resource`/`payment-resource`
这几个 endpoint。理由:`camel-audit-observability.md` 里这个模块自己选定的三层观测
(`RestApiAuditRoutePolicyFactory` + `CamelSendTimingEventNotifier` + hc5 `ExecChainHandler` 三件套,
含本文事发时怀疑过又被排除的 `ObservationExecChainHandler`)本来就完全不依赖它,它更像是历史遗留/顺带
装上的依赖,留着只有风险没有必要收益。

`CamelConfig.httpComponent` 里的 `ObservationExecChainHandler` 保持**启用**——它不是问题本身(假设 1
已经排除),去掉 `camel-observation-starter` 之后重新验证:同一个 curl 请求,hc5 exec chain 入口和出口的
`correlationTraceId` 都恢复正常,和入站 `TracingFilter` 记的值完全一致。

## 代价:route/processor 级 Prometheus 指标一并消失

`camel-observation-starter` 不是只干了 tracing 这一件事——它把每个 route 和 processor 节点都包成一个
Micrometer `Observation`,也是 `README.md` 里 `fulfillment_seconds` / `fulfillment_notification_seconds`
/ `fulfillment_active_seconds` 这些 **route 级计时器**的来源(该文档原先把它们标注为「正常,保留」)。
移除依赖后这些指标**全部消失**(`curl /actuator/prometheus | grep fulfillment_seconds` 验证为空),
`README.md` 里那一整段「`<log>` 变成指标 → 基数爆炸」的踩坑记录(`<log>` 节点被当成 Observation 包裹、
动态消息文本变成指标名导致基数爆炸)也随之成为历史记录——依赖已经不在了,那个爆炸风险本身也不存在了。

这是一次**有意识的取舍**,不是疏忽:trace 正确性(`correlationTraceId` 能不能把一条请求跨 route/下游串起来)
优先于这几个 route 级 timer。如果之后需要把这些指标找回来,两条路都不牵扯本文的根因:

1. 手写等价的 `CamelSendTimingEventNotifier` 扩展(该 EventNotifier 本来就覆盖所有 `ExchangeSentEvent`,
   见 `camel-audit-observability.md` 第 2 层),补一个按 route id 聚合的 Timer,不需要 Observation;
2. 如果坚持要 camel-observation 提供的指标,改用 `camel.observation.exclude-patterns` 只排除三个下游
   endpoint(保留 route 级观测),但**必须**重新用本文同款的「TraceDiagnosticProcessor 前后夹逼 + A/B
   开关」方法重新验证——exclude-patterns 排除的是 span 创建本身,理论上能避开这个 bug,但没有实测过。

## 方法论:这类「本地状态跨某个边界丢失」问题怎么排查

1. **不要通过有 fallback 逻辑的读取路径判断状态是否丢失**。`MyMDCScopeDecorator.MDCContext.getValue()`
   在 baggage 为空时会 fallback 读 `MDC.get(BaggageFields.TRACE_ID.name())`(池线程残留值),这个
   fallback 本身就是为了「看起来没断」设计的(见 `camel-producertemplate-context-propagation.md` 坑点
   #6),会掩盖真正的断点。诊断代码要直接读原始状态(`CORRELATION_FIELD.getValue()`),不能经过它。
2. **前后夹逼,不要只看最终结果**。在怀疑的每个边界两侧各打一行诊断日志(本文用了一次性 `Processor`
   bean,`camel-context.xml` 里 `<process ref="..."/>` 挂两处),同一个 correlateId、同一个线程,直接
   对比该边界前后的值,比只看最终响应头准得多——最终响应头可能因为同线程 MDC 残留而「看起来对」。
3. **A/B 开关比静态分析快**。反编译字节码得出的「这个 handler 理论上不该影响状态」的结论,最终被同一个
   请求关/开这个 handler 的实测结果直接验证——静态分析定位可疑代码,实测才能确认因果。
4. **诊断代码用完就删**,包括：临时 `Processor` bean、XML 里的 `<process ref="..."/>`、日志里新增的字段
   ——本次排查完成后全部回退(见 git 历史),只留下这份文档和最终修复的 diff。
