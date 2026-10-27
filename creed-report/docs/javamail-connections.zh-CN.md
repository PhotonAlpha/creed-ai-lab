# `JavaMailSender` 会打开几个 SMTP 连接？

[English](javamail-connections.md)

阅读的源码版本：`spring-context-support` **7.0.9**（`JavaMailSenderImpl`）、`jakarta.mail-api` **2.1.5**（`Session`）、
`angus-mail` **2.0.5**（`SMTPTransport`）。这是 Spring Boot 4.1.1 的 `spring-boot-starter-mail` 实际使用的版本。
下文的行号都出自这几个源码 jar。

## 结论

| 场景 | 总连接数 | 同时最多打开 |
|---|---|---|
| 单线程，**一次** `send(msg1..msg5)` | **1**（5 封信走同一个连接） | 1 |
| 单线程，循环调用 **5 次** `send(msg)` | **5**（一个关了才开下一个） | **1** |
| **5 个线程**各调一次 `send(msg)` | 5 | **5** |

一句话：**一个连接的生命周期，正好等于一次 `send(...)` 调用。** `JavaMailSenderImpl` 不持有连接，也没有连接池。
`send` 是同步的，所以单线程不可能同时开着两个连接；多线程则各开各的，没有任何数量上限。

## 源码依据

### 1. 连接是 `doSend` 的局部变量，`finally` 里一定会关掉

所有 `send` 重载最终都只调用一次 `doSend`：

```java
// JavaMailSenderImpl.java（spring-context-support 7.0.9）
367  protected void doSend(MimeMessage[] mimeMessages, Object @Nullable [] originalMessages) {
369      Transport transport = null;                               // 局部变量，每次调用一个
         try {
             for (int i = 0; i < mimeMessages.length; i++) {
375              if (transport == null || !transport.isConnected()) {
386                  transport = connectTransport();               // 建连接：TCP + TLS + EHLO + AUTH
                 }
414              transport.sendMessage(mimeMessage, addresses);
             }
         }
422      finally {
425          transport.close();                                    // 返回之前一定关闭
         }
     }
```

- `transport` 声明在方法**内部**（L369），出了这次调用就不存在，别的调用也看不到它。
- 不管发送成功与否，`finally`（L422–425）都会把它关掉。`SMTPTransport.close()`（angus-mail L1394）会发送
  `QUIT` 并关闭 socket。

### 2. 两次调用之间，sender 不保留任何连接

`JavaMailSenderImpl` 唯一缓存的是 `Session`（字段 L83，在 `getSession()` L153 中延迟创建）。`Session` 只是配置加工厂，
不是连接：

```java
// JavaMailSenderImpl.java
456  protected Transport connectTransport() throws MessagingException {
         Transport transport = getTransport(getSession());          // L479 -> session.getTransport(protocol) L487
         transport.connect(getHost(), getPort(), username, password);
         return transport;
     }

// Session.java（jakarta.mail-api 2.1.5）
720  public Transport getTransport(String protocol)              // -> getTransport(Provider, URLName) L791
813  private <T extends Service> T getService(...)               // -> 反射：
869      return type.cast(cons.newInstance(o));                   //    每次都 new 一个新的 SMTPTransport
```

所以每次 `connectTransport()` 都会新建一个 `SMTPTransport`，再新开一个 socket。sender 里没有字段能存住上一个连接，
也没有任何 API 能让连接跨多次 `send` 保留下来。

### 3. 为什么一次 `send(msg1..msg5)` 只用一个连接

`send(SimpleMailMessage...)`（L297–304）先把**所有**信转换好，再**只调用一次** `doSend`。在这一次调用里，L375 只在两种情况下
重新连接：`transport == null`（第一封信），或者 `!transport.isConnected()`。从第二封信开始，`SMTPTransport.isConnected()`
（angus-mail L1433）会在**现有** socket 上发一个 `NOOP`（设置 `mail.smtp.userset=true` 时改发 `RSET`）。只要服务器有回应，
下一封信就继续走这个连接。

所以 `send(msg1, msg2, msg3)` 会留下一个只有"复用"才会出现的特征：**一个 socket、三封信、两次 `NOOP`**（`msg2` 之前一次，
`msg3` 之前一次）。如果每封信都新建连接，看到的会是三个 socket，而且一次 `NOOP` 都没有。
`severalMessagesInOneSendReuseTheSameConnection` 断言的就是这个特征（见下文）。

### 4. 为什么单线程循环调用 `send(msg)` 永远不会重叠

每次 `send(msg)` 都是一次独立的 `doSend`，所以各有各的连接（§1、§2），一共 5 个。但 `send` 是同步的，`close()` 在 `finally`
里执行，**先于** `send` 返回。所以循环的下一轮，一定要等上一个连接关掉才能开始：

```
send#1: [开 ── 发 ── 关]
send#2:                 [开 ── 发 ── 关]
send#3:                                 [开 ── 发 ── 关]
```

### 5. 为什么 N 个线程会同时打开 N 个连接

`doSend` 没有加 `synchronized`（只有延迟创建 Session 的 `getSession()` 加了），`transport` 又在每个线程自己的栈上（§1）。
没有任何东西让一个线程等另一个线程的连接，没有复用，也没有计数。这也正是 `JavaMailSenderImpl` 线程安全的原因：
它什么都不共享。

## 运行依据

`creed-report/src/test/java/com/creed/report/mail/JavaMailSenderConnectionTest.java` 把表里的三种场景都跑一遍，另外单独验证一次 `send` 内部的连接复用。连的是进程内的假
SMTP 服务器（`FakeSmtpServer`，只监听本机，不会发出真实邮件）。客户端和服务端各自计数：

- `[client]`：`ConnectionCountingMailSender` 重写了 `connectTransport()` / `doSend()`，每次打开、关闭连接都会记录线程名和当时
  打开的连接数。
- `[server]`：假服务器每接受一个 socket 就记录客户端的临时端口。**端口不同，就是不同的 TCP 连接。**服务器每封信故意晚 300ms
  回复，保证几个线程真的同时在发。

```bash
mvn -pl creed-report test -Dtest=JavaMailSenderConnectionTest
```

| 测试 | 断言 |
|---|---|
| `singleThreadBatchSendReusesOneConnection` | 客户端 1 个、服务端 1 个连接，5 封信 |
| `severalMessagesInOneSendReuseTheSameConnection` | `send(msg1, msg2, msg3)`：服务端**唯一的**连接承载了 **3** 封信，收到 **2** 次 `NOOP`；客户端只记录了 transport #1 的一次 OPEN 和一次 CLOSE |
| `singleThreadSequentialSendsNeverOverlap` | 服务端 5 个连接，两端同时最多都是 **1** |
| `multipleThreadsOpenConcurrentConnections` | 5 个连接，两端同时最多都 **> 1** |

一次运行的日志（已精简）：

```text
===== single thread, send(msg x5) in ONE call =====
[main]          [client] send(5 message(s)) thread=main
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55294 active=1
[main]          [client] transport#1 OPEN  thread=main active=1
[main]          [client] CLOSE thread=main active=0
[smtp-conn-1]   [server] conn#1 CLOSE   client port=55294 messages=5 active=0      <- 5 封信，1 个端口

===== single thread, send(msg1, msg2, msg3): is the connection reused? =====
[main]          [client] send(3 message(s)) thread=main
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=49934 active=1
[main]          [client] transport#1 OPEN  thread=main active=1
[smtp-conn-1]   [server] conn#1 MESSAGE #1 received  client port=49934
[smtp-conn-1]   [server] conn#1 NOOP (connection check) client port=49934           <- isConnected()，L375
[smtp-conn-1]   [server] conn#1 MESSAGE #2 received  client port=49934              <- 同一个端口：复用
[smtp-conn-1]   [server] conn#1 NOOP (connection check) client port=49934
[smtp-conn-1]   [server] conn#1 MESSAGE #3 received  client port=49934
[smtp-conn-1]   [server] conn#1 CLOSE   client port=49934 messages=3 active=0
[main]          [client] transport#1 CLOSE thread=main active=0
===== conn#1 client port=49934 carried 3 message(s), 2 NOOP(s) =====

===== single thread, send(msg) called 5 times =====
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55301 active=1
[smtp-conn-1]   [server] conn#1 CLOSE   client port=55301 messages=1 active=0
[smtp-conn-2]   [server] conn#2 ACCEPT  client port=55306 active=1                  <- 新端口，在上一个关闭之后
[smtp-conn-2]   [server] conn#2 CLOSE   client port=55306 messages=1 active=0
...                                     （5 个端口，active 从未超过 1）
===== client: 5 connection(s), max 1 concurrent | server: 5 connection(s), max 1 concurrent =====

===== 5 threads, one send(msg) each, released together =====
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55288 active=1
[smtp-conn-3]   [server] conn#3 ACCEPT  client port=55285 active=3
[smtp-conn-2]   [server] conn#2 ACCEPT  client port=55287 active=2
[smtp-conn-4]   [server] conn#4 ACCEPT  client port=55286 active=4
[smtp-conn-5]   [server] conn#5 ACCEPT  client port=55289 active=5                  <- 同时打开 5 个
[mail-sender-3] [client] transport#5 OPEN  thread=mail-sender-3 active=5
===== client: 5 connection(s), max 5 concurrent | server: 5 connection(s), max 5 concurrent =====
```

连 `spring.mail.*` 里配置的真实服务器验证，有两种方式：

- **测试**：需要手动开启的 `JavaMailSenderConnectionTest$Live`（`-Dmail.live.to=<邮箱>`）。
- **REST API**：`POST /report/api/mail/probe?mode=batch|sequential|concurrent&count=N&to=<邮箱>`。返回 `connections`、
  `maxConcurrentConnections` 和每次打开、关闭连接的事件。请求写在 `.support/http-client/creed-report.http` 里。

两种方式都会发出**真实**邮件。

### 为什么不直接看 JavaMail 自带的 debug 日志？

它也能作为证据，而且不用写代码。angus-mail 的 `MailLogger` 会把每一行同时输出到两个地方（`MailLogger.log`，L214）：

- **`mail.debug=true`**（`spring.mail.properties.mail.debug: true`）：把协议交互打到 **`System.out`**，例如
  `DEBUG SMTP: trying to connect to host "smtp.qq.com", port 465`（`SMTPTransport` L2187）、`connected to host`（L2237），
  然后是 `EHLO` … `QUIT`。
- **JUL logger `org.eclipse.angus.mail.smtp`**（`FINE` 级别）：收到同样的内容。它的子 logger
  **`org.eclipse.angus.mail.smtp.protocol`**（`FINEST` 级别）会收到每一条命令和回复（`SMTPTransport` L196、`TraceOutputStream`）。
  Spring Boot 会安装 JUL→SLF4J 的桥接，并把 Logback 的日志级别同步回 JUL（`LogbackLoggingSystem` L146、L316），所以这些行会
  **带着时间戳和线程名**出现在应用日志里。creed-report 的 `application.yml` 已经把两个都打开了：

  ```yaml
  logging:
    level:
      org.eclipse.angus.mail.smtp: debug           # 建立连接 / 投递成功
      org.eclipse.angus.mail.smtp.protocol: trace  # EHLO、MAIL、RCPT、DATA、NOOP、QUIT
  ```

  `AUTH` 不会被打印（`mail.debug.auth` 默认是 `false`，`SMTPTransport` L197），所以密码不会进日志；但 `DATA` 之后的邮件正文会。

数 `connected to host` 有几行，就知道总连接数；看上一个 `QUIT` 之前有没有出现下一个 `connected`，就知道有没有并发。
下面这段是调用 `/api/mail/probe` 连本地假服务器得到的日志（已精简）：

```text
# mode=concurrent&count=3：三个 "connected" 都出现在任何一个 QUIT 之前
[mail-probe-3] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[mail-probe-2] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[mail-probe-1] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
...
[mail-probe-1] org.eclipse.angus.mail.smtp.protocol: QUIT
[mail-probe-2] org.eclipse.angus.mail.smtp.protocol: QUIT

# mode=batch&count=2：一个 "connected"，两封信之间是 §3 里的 NOOP，最后一个 QUIT
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: MAIL FROM:<ethan.caoq@foxmail.com>
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: 250 queued
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: NOOP
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: MAIL FROM:<ethan.caoq@foxmail.com>
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: 250 queued
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: QUIT
```

它缺的东西，正是要写子类的原因：

| | JavaMail debug 日志 | `ConnectionCountingMailSender` + `FakeSmtpServer` |
|---|---|---|
| 总连接数 | 手工数 `connected to host` 的行数 | `totalConnections()` |
| 同时最多打开几个 | 从交错的时间戳里自己推算 | `maxConcurrentConnections()` |
| 某一行属于哪个连接 | 没有连接编号，也没有本地端口。`mail.debug` 打到 `System.out` 时连线程名都没有，5 个线程的输出交错在一起，分不清谁是谁 | 客户端有 `transport#N` 加线程名，服务端有 `client port=` |
| 测试能不能断言 | 只能先截获日志再解析 | 直接拿数字：`assertThat(...).isEqualTo(1)` |
| 是否独立于客户端 | 否，是客户端自己说自己 | 服务端数的是它实际接受的 socket |

想看和真实服务器的交互过程，用 debug 日志；要*证明*上面那张表，用计数。

## 边界情况：什么时候结果会和表格不一样

表格是正常情况。下面这些情况会改变结果，原因都出自同一份源码：

| 情况 | 结果 | 原因（源码） |
|---|---|---|
| 一次发多封的过程中，服务器断开了连接（空闲超时、`421`） | 这一次 `send` 总共用到 **2 个以上**连接；同时仍然最多 1 个 | L375：`!transport.isConnected()` → 先关掉，再重新 `connectTransport()`（L376–386） |
| 线程实际上没有重叠（发得快、线程池只有 1 个线程、`send` 外面加了锁） | 同时打开数**小于**线程数，最少是 1；总数仍是每次 `send` 一个 | 并发程度完全由调用方决定，sender 既不增加也不减少 |
| 用 `@Async` / 线程池异步发信 | 同时打开数 ≤ **线程池大小** | 同上：线程池是唯一的上限 |
| 多个线程，每个线程各自一次发多封 | 连接数 = 线程数，每个连接承载多封信 | 线程内按 §3，线程之间按 §5 |
| 连接失败（DNS、连接被拒、TLS） | 没有建立连接；这次 `send` 里剩下的**所有**信都在一个 `MailSendException` 里报失败 | L391–396：剩下的信全部放进 `failedMessages` |
| 登录被拒 | 没有建立连接；直接抛 `MailAuthenticationException`，不逐封列出失败 | L388–389：直接从循环里抛出 |
| 一次发多封时，其中一封被拒（收件人不对） | **同一个**连接继续发剩下的信 | L416–419：错误按每封信记录，循环继续 |
| `testConnection()` | 开一个连接再关掉 | L344–354：`connectTransport()` + `finally` 里 `close()` |
| 服务器限制每个账号的并发连接数 | 超出上限的线程连不上或被拒绝 | 这是服务器策略，不是 JavaMail 的行为，它才是 §5 在实际中的上限 |

有两件事**不算**额外打开的连接：

- **`TIME_WAIT`**：`close()` 之后，先断开的一方会让 socket 在 `TIME_WAIT` 状态停留一段时间（macOS 上约 30 秒，可以用
  `netstat -an | grep 465` 看到）。这期间不传数据，也不占服务器的连接名额。但短时间内循环 `send(msg)`，本机会积压一串这样的 socket。
- **每个连接的开销**：每建一次新连接，都要重新做 TCP 握手、TLS 握手和 `AUTH` 登录。连远程服务器时，这部分通常比发信本身还慢。
  `JavaMailSenderImpl` 自己能共用连接的唯一方式，就是把多封信放进**同一次** `send(...)`。
