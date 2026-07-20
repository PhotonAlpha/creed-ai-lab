# Logbook 生产化调优:开关清单、内容类型过滤的方向差异、零缓冲策略与异步落盘

`creed-simple-metrics` 用 Zalando Logbook 做**入站(servlet filter)+ 出站(两个 hc5
`LogbookHttpExecHandler`)双向审计**。默认形态是调试用的(全量 body、多行块、同步落盘),本文记录把它
调到生产可用所做的全部改动,以及每处**为什么只能落在那一层**。审计三层设计的整体视角见
[camel-audit-observability.md](camel-audit-observability.md);本文只聚焦 Logbook 这一层的生产化。

一句话原则:**默认零缓冲成本,只在需要时(错误/JSON)才带 body,格式与落盘和业务日志彻底分离,
且日志 I/O 绝不阻塞 HTTP 调用线程。**

## 先理解 Logbook 的生命周期,才知道每个开关能放哪

一次请求/响应在 Logbook 里按固定顺序走这几步(`Strategy` 接口的 javadoc 明确了时序):

| # | 钩子 | 何时 | 能看到什么 | 本项目用它做什么 |
|---|---|---|---|---|
| 0 | `Logbook.condition()`(`Predicate<HttpRequest>`) | **请求发出前** | 只有请求 | 请求级前置过滤:路径黑名单 + **请求**内容类型白名单 |
| 1 | `Strategy.process(request)` | 缓冲请求 body 前 | 请求 | 默认(缓冲,请求体小) |
| 2 | `Strategy.write(precorrelation, request, sink)` | 请求处理完 | 请求 | 默认(立即写出请求行) |
| 3 | `Strategy.process(request, response)` | **缓冲响应 body 前** | 请求 + 响应 status/headers | **响应**内容类型 + 错误状态双重门槛,决定要不要缓冲响应 body |
| 4 | `Strategy.write(correlation, request, response, sink)` | 响应处理完 | 请求 + 响应 | 默认(写出响应行) |

**关键认知**:第 0 步 `condition` 是唯一"零成本"的闸门——它在请求发出前就判断,挡掉的请求连 body 缓冲的
入口都不碰。但它**结构上拿不到响应**(`Predicate<HttpRequest>`,响应还不存在),所以任何跟响应有关的判断
只能落到第 3 步 `Strategy.process(request, response)`。这条约束直接决定了下面"请求 vs 响应内容类型过滤"
的方向差异——不是实现偷懒,是 API 使然。

## 全部开关一览(`application.yml`)

Logbook 原生的 `logbook.*` + 本模块自己加的 `creed.logbook.*`(后者是 Logbook 属性面**没有**的能力,
落在 `web/LogbookAuditConfiguration.java` 两个 bean 里):

```yaml
logbook:
  predicate:
    exclude:
      - path: /actuator/**        # 健康检查/抓取路径整条排除(path/method 维度,Logbook 原生)
  format:
    style: http                    # 本地调试用多行块;生产切 splunk(单行 field=value,ELK/Loki 好切分)
  obfuscate:
    headers: [Authorization, Proxy-Authorization]   # header 打码(值替换成 XXX)
  write:
    max-body-size: 4096            # 只截断"写出"的字节数,body 仍会先完整缓冲(见下)
creed:
  logbook:
    skip-paths: []                 # Ant 模式路径黑名单,在 condition 里叠加(高流量/无意义路径整条跳过)
    allowed-content-types: [application/json]   # 内容类型白名单,请求+响应两个方向都用(见下节)
    body-on-error:
      enabled: false               # 生产建 true:响应 body 只在 status>=minimum-status 时缓冲/记录
      minimum-status: 500
    # strategy.enabled: true       # (默认 true)本模块接管 Strategy;设 false 回退 Logbook 内置策略
    # request-condition.enabled: true  # (默认 true)本模块接管 condition;设 false 回退 $ -> true
```

## 请求 vs 响应内容类型过滤:同一个白名单,两种效果

`creed.logbook.allowed-content-types`(默认仅 `application/json`,大小写不敏感、**前缀**匹配,所以
`application/json;charset=UTF-8` 也命中)同时管两个方向,但因为生命周期位置不同,效果**不对称**:

| 方向 | 落点 | 非白名单时的效果 | 为什么 |
|---|---|---|---|
| **请求** | `condition`(第 0 步) | 整条审计(请求+响应)都不记,零缓冲 | 请求发出前就判断,还没写任何东西,可以最省地整条跳过 |
| **响应** | `Strategy.process(request,response)`(第 3 步) | 只丢**响应 body**,保留响应元数据行(status/headers) | 拿到响应类型时请求行早已写出,丢不掉;能做的只是不缓冲这个非 JSON body |

无 body 的请求(GET/DELETE/健康探针)两侧都**直接放行**(`getContentType()` 为空 → 不拦)。白名单
**置空** = 关掉这道内容类型门槛(两个方向都关),只留路径过滤 / 状态门槛。

> 为什么不用 Logbook 自带的 `logbook.predicate.include/exclude` 做这件事?反编译
> `LogbookProperties.LogbookPredicate` 确认过,它只有 `path` 和 `methods` 两个字段,**没有内容类型
> 维度**——这正是要另开 `creed.logbook.*` 而不是复用 `logbook.predicate.*` 的原因。两套是叠加关系。

## `body-on-error`:真正的零缓冲,不是"记了又不写"

需求是"高 QPS 下平时不带 body,只有出错才带"。看起来 Logbook 内置的
`strategy: body-only-if-status-at-least` 正好干这个——但它(连同 `status-at-least` / `without-body`)
**全部只重写 `Strategy.write(...)`**,而 write 发生在两个 body 已经被默认 `process()` 完整缓冲进内存
**之后**。`minimum-status` 只决定要不要把已经缓冲好的内容**写出去**——**省的是磁盘/网络 I/O,不是内存**。
大响应照样吃内存/延迟。

`ContentAwareBodyStrategy` 把判断放在 **`process(request, response)`**(第 3 步),按 `Strategy` 契约它在
响应 body 读取**之前**触发。而本模块用的 hc5 classic 集成(`LogbookHttpExecHandler`)在这一步 status
line / headers 已经到手、entity 还没消费,所以 `getStatus()` 和 `getContentType()` 都可靠——被拒的
body **一次都不缓冲**,是真正的零成本跳过。

响应 body 最终被缓冲/记录,当且仅当:**内容类型命中白名单** 且(`body-on-error` 开时)**status ≥ minimum-status**。
两个门槛是 AND 关系,组合在同一个 `process(request, response)` 里。请求 body 始终缓冲(`process(request)`
用默认,请求体是已过闸的小 JSON,错误时留着有用)。

**一个有意的设计选择:不 defer。** 内置的状态门槛策略会把请求写出推迟到响应到达时(`write(precorrelation)`
留空,最后 `writeBoth`),好把请求/响应合并成一条。本策略**不这么做**——保持 write 的默认时序(请求发出时
就写请求行,响应到达时写响应行)。原因:一旦 defer,**请求发出但响应永远没来**(超时/连接断)的情况下
请求行会彻底丢失,这对审计日志是最不该丢的一类记录。合并可读性 < 审计完整性,所以选不 defer。缓冲跳过的
收益不受影响(缓冲决策在 `process`,与 write 时序无关)。

## 输出通道:独立文件 + 异步,不阻塞 HTTP 线程(`logback-spring.xml`)

`LogbookHttpExecHandler` 在 hc5 exec chain 里是**同步**调 `sink.write()` 的——不异步化,这段落盘/格式化
I/O 直接算进下游调用耗时。所以给 `org.zalando.logbook` logger 单独配了:

- **`LOGBOOK_FILE`**:独立 rolling 文件(`${appName}-logbook.log`)+ 独立滚动策略(50MB/15 份/2GB 上限),
  `additivity=false` 不回流业务日志——审计量大、价值周期短,和业务日志分开保留策略;
- **`ASYNC_LOGBOOK`**:外套 `AsyncAppender`,落盘和控制台输出都走它,把 I/O 摘出请求热路径;
  - `neverBlock=true`:队列打满时**丢日志而不是阻塞**调用线程(审计不该拖垮业务);
  - `discardingThreshold=0`:关掉"队列超 80% 自动丢 TRACE/DEBUG/INFO"的默认行为——Logbook **全部**走
    TRACE,默认阈值会把审计日志整体误伤,只有 `neverBlock` 兜底的整队列丢弃才是想要的降级方式。

pattern 保留 `%X{traceId}`:Logbook 自己的 correlation id 配对同一次调用的 request/response 两行,
traceId 负责跨服务串联。

> 坑(通用):Logbook 往 `org.zalando.logbook` 写的是 **TRACE** 级,logback 不显式开这个 logger 到
> TRACE,整套审计**静默失效**、不报错。这也是运行时热开关的另一面——生产可常态关到 INFO,排障时用
> actuator `loggers` 端点热开 TRACE,无需改配置重启(`writer.isActive()` 为 false 时整条链路短路,零开销)。

## 格式:生产用 splunk / json,不用 http 多行块

`logbook.format.style` 可选 `http` / `json` / `curl` / `splunk`(反编译 autoconfigure 里的
`@ConditionalOnProperty` 确认)。`http` 是多行可读块,本地调试好用,但在 ELK/Loki 里一条日志被切成多行是
灾难。生产选 **`splunk`**(单行 `key=value`)或 `json`(单行结构化),按目标平台选一个。当前仓库里留的是
`http`(便于本地看),上生产改这一个值即可。

## 逐条验证

1. **请求内容类型闸门**:同一个端点分别用 `-H 'Content-Type: application/json'` 和
   `-H 'Content-Type: text/plain'` 打——前者进 `*-logbook.log`,后者整条不出现(连请求行都没有)。
2. **响应内容类型/错误门槛**:`body-on-error.enabled=true` 时,正常 200 JSON 响应只有元数据行、没有响应
   body;构造一个 5xx 才看到响应 body。`enabled=false` 时 JSON 响应带 body、非 JSON 响应只丢 body。
3. **异步不阻塞**:高并发下对比开/关 `ASYNC_LOGBOOK` 时下游调用的 `LB resolved ... in Nms`——异步后审计
   I/O 不再算进这个耗时。
4. **启动自检**:启动日志里 `LogbookAuditConfiguration` 打的
   `Logbook Strategy active: response body buffered only when Content-Type matches [...]` 一行,确认
   策略 bean 生效、白名单/门槛读到位。

## 仍是建议、尚未实施

- **body 级字段脱敏(PCI-DSS)**:属性配置管不到 JSON 字段,要注册 `BodyFilter` bean(autoconfigure 会
  合并)。payment 域**不是可选项**——PAN/CVV 进日志即违规,要么字段级打码,要么用 predicate 把支付路径
  整个排除出 body 记录:

  ```java
  @Bean
  BodyFilter bodyFilter() {
      return JsonBodyFilters.replaceJsonStringProperty(Set.of("password", "cardNumber", "cvv"), "***");
  }
  ```

- **审计"是谁"**:纯报文缺主体信息,注册 `AttributeExtractor`(如 `JwtFirstMatchingClaimExtractor` 提取
  JWT `sub`/`client_id`),让审计行直接回答"哪个客户端在何时调了什么"。
- **出站范围**:只给业务 hc5 client 挂 `LogbookHttpExecHandler`,health-check 独立池**不挂**,否则每轮
  探活都刷审计(业务/探活双池隔离正好方便这一点)。
