# How many SMTP connections does `JavaMailSender` open?

[中文版](javamail-connections.zh-CN.md)

Versions read: `spring-context-support` **7.0.9** (`JavaMailSenderImpl`), `jakarta.mail-api` **2.1.5**
(`Session`), `angus-mail` **2.0.5** (`SMTPTransport`) — the ones Spring Boot 4.1.1 resolves for
`spring-boot-starter-mail`. Line numbers below are from those source jars.

## Conclusion

| Scenario | Connections in total | Open at the same time (max) |
|---|---|---|
| One thread, **one** `send(msg1..msg5)` call | **1** — all five messages travel over one connection | 1 |
| One thread, `send(msg)` called **5 times** in a loop | **5** — each one is closed before the next is opened | **1** |
| **5 threads**, one `send(msg)` each | 5 | **5** |

In one sentence: **a connection lives exactly as long as one `send(...)` call.** `JavaMailSenderImpl`
holds no connection and has no pool. A single thread can therefore never have two open at once,
because `send` is synchronous. Several threads each get their own, and nothing caps how many.

## Evidence from the source

### 1. The connection is a local variable of `doSend`, and `finally` closes it

Every public `send` overload ends in one `doSend` call:

```java
// JavaMailSenderImpl.java (spring-context-support 7.0.9)
367  protected void doSend(MimeMessage[] mimeMessages, Object @Nullable [] originalMessages) {
369      Transport transport = null;                               // local, per call
         try {
             for (int i = 0; i < mimeMessages.length; i++) {
375              if (transport == null || !transport.isConnected()) {
386                  transport = connectTransport();               // open: TCP + TLS + EHLO + AUTH
                 }
414              transport.sendMessage(mimeMessage, addresses);
             }
         }
422      finally {
425          transport.close();                                    // always closed before returning
         }
     }
```

- `transport` is declared **inside** the method (L369), so it cannot outlive the call or be seen
  by another call.
- `finally` (L422–425) closes it whether the send succeeded or not. `SMTPTransport.close()`
  (angus-mail L1394) sends `QUIT` and closes the socket.

### 2. The sender keeps no connection between calls

The only connection-related thing `JavaMailSenderImpl` caches is the `Session` (field L83, lazily
created in `getSession()` L153). A `Session` is configuration plus a factory, not a connection:

```java
// JavaMailSenderImpl.java
456  protected Transport connectTransport() throws MessagingException {
         Transport transport = getTransport(getSession());          // L479 -> session.getTransport(protocol) L487
         transport.connect(getHost(), getPort(), username, password);
         return transport;
     }

// Session.java (jakarta.mail-api 2.1.5)
720  public Transport getTransport(String protocol)              // -> getTransport(Provider, URLName) L791
813  private <T extends Service> T getService(...)               // -> reflection:
869      return type.cast(cons.newInstance(o));                   //    a NEW SMTPTransport every time
```

So every `connectTransport()` builds a new `SMTPTransport` and dials a new socket. There is no field
in which a previous one could have been kept, and no API that keeps one across `send` calls.

### 3. Why one `send(msg1..msg5)` uses one connection

`send(SimpleMailMessage...)` (L297–304) converts **all** messages first, then calls `doSend`
**once**. Inside that single call, the loop at L375 only reconnects when `transport == null` (the first
message) or when `!transport.isConnected()`. For every later message, `SMTPTransport.isConnected()`
(angus-mail L1433) sends a `NOOP` (or `RSET` with `mail.smtp.userset=true`) over the **existing**
socket. While the server answers, the same connection carries the next message.

So `send(msg1, msg2, msg3)` leaves a fingerprint that only reuse can produce: **one socket, three
messages, two `NOOP`s** (one before `msg2`, one before `msg3`). A new connection per message would show
three sockets and no `NOOP` at all. `severalMessagesInOneSendReuseTheSameConnection` asserts exactly
that fingerprint (see below).

### 4. Why a loop of `send(msg)` in one thread never overlaps

Each `send(msg)` is a separate `doSend` call, so it gets its own connection (§1, §2). That makes five
connections. But `send` is synchronous and `close()` runs in `finally` **before** `send` returns.
The loop's next iteration therefore cannot start until the previous connection is closed:

```
send#1: [open ── send ── close]
send#2:                         [open ── send ── close]
send#3:                                                 [open ── send ── close]
```

### 5. Why N threads open N connections at once

`doSend` is not `synchronized` (only the lazy `getSession()` is), and `transport` lives on each
thread's own stack (§1). Nothing makes one thread wait for another's connection. Nothing reuses it,
and nothing counts how many are open. This is also exactly why `JavaMailSenderImpl` is thread-safe:
it shares nothing.

## Evidence from running it

`creed-report/src/test/java/com/creed/report/mail/JavaMailSenderConnectionTest.java` runs the three
scenarios of the table, plus a closer look at the reuse inside one `send`, against an in-process fake SMTP server (`FakeSmtpServer`, loopback only, no real mail). Both
sides count:

- `[client]` — `ConnectionCountingMailSender` overrides `connectTransport()` / `doSend()` and logs
  every OPEN/CLOSE with the thread and the number open.
- `[server]` — the fake server logs every accepted socket with the client's ephemeral port. **Distinct
  ports = distinct TCP connections.** The server holds each reply for 300 ms, so concurrent threads
  really overlap.

```bash
mvn -pl creed-report test -Dtest=JavaMailSenderConnectionTest
```

| Test | Asserts |
|---|---|
| `singleThreadBatchSendReusesOneConnection` | client 1, server 1 connection, 5 messages |
| `severalMessagesInOneSendReuseTheSameConnection` | `send(msg1, msg2, msg3)`: the server's **one** connection carried **3** messages and received **2** `NOOP`s; the client logged exactly OPEN + CLOSE of transport #1 |
| `singleThreadSequentialSendsNeverOverlap` | server 5 connections, max concurrent **1** on both sides |
| `multipleThreadsOpenConcurrentConnections` | 5 connections, max concurrent **> 1** on both sides |

Log of a run (trimmed):

```text
===== single thread, send(msg x5) in ONE call =====
[main]          [client] send(5 message(s)) thread=main
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55294 active=1
[main]          [client] transport#1 OPEN  thread=main active=1
[main]          [client] CLOSE thread=main active=0
[smtp-conn-1]   [server] conn#1 CLOSE   client port=55294 messages=5 active=0      <- 5 messages, 1 port

===== single thread, send(msg1, msg2, msg3): is the connection reused? =====
[main]          [client] send(3 message(s)) thread=main
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=49934 active=1
[main]          [client] transport#1 OPEN  thread=main active=1
[smtp-conn-1]   [server] conn#1 MESSAGE #1 received  client port=49934
[smtp-conn-1]   [server] conn#1 NOOP (connection check) client port=49934           <- isConnected(), L375
[smtp-conn-1]   [server] conn#1 MESSAGE #2 received  client port=49934              <- same port: reused
[smtp-conn-1]   [server] conn#1 NOOP (connection check) client port=49934
[smtp-conn-1]   [server] conn#1 MESSAGE #3 received  client port=49934
[smtp-conn-1]   [server] conn#1 CLOSE   client port=49934 messages=3 active=0
[main]          [client] transport#1 CLOSE thread=main active=0
===== conn#1 client port=49934 carried 3 message(s), 2 NOOP(s) =====

===== single thread, send(msg) called 5 times =====
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55301 active=1
[smtp-conn-1]   [server] conn#1 CLOSE   client port=55301 messages=1 active=0
[smtp-conn-2]   [server] conn#2 ACCEPT  client port=55306 active=1                  <- new port, after the close
[smtp-conn-2]   [server] conn#2 CLOSE   client port=55306 messages=1 active=0
...                                     (5 ports, active never above 1)
===== client: 5 connection(s), max 1 concurrent | server: 5 connection(s), max 1 concurrent =====

===== 5 threads, one send(msg) each, released together =====
[smtp-conn-1]   [server] conn#1 ACCEPT  client port=55288 active=1
[smtp-conn-3]   [server] conn#3 ACCEPT  client port=55285 active=3
[smtp-conn-2]   [server] conn#2 ACCEPT  client port=55287 active=2
[smtp-conn-4]   [server] conn#4 ACCEPT  client port=55286 active=4
[smtp-conn-5]   [server] conn#5 ACCEPT  client port=55289 active=5                  <- 5 open at once
[mail-sender-3] [client] transport#5 OPEN  thread=mail-sender-3 active=5
===== client: 5 connection(s), max 5 concurrent | server: 5 connection(s), max 5 concurrent =====
```

Against the real server configured in `spring.mail.*`:

- **Test** — the opt-in `JavaMailSenderConnectionTest$Live` (`-Dmail.live.to=<address>`).
- **REST API** — `POST /report/api/mail/probe?mode=batch|sequential|concurrent&count=N&to=<address>`.
  It returns `connections`, `maxConcurrentConnections` and every OPEN/CLOSE event. The requests are
  in `.support/http-client/creed-report.http`.

Both send **real** mail.

### Why not just JavaMail's own debug log?

It is evidence too, and it needs no code. angus-mail's `MailLogger` writes every line two ways
(`MailLogger.log`, L214):

- **`mail.debug=true`** (`spring.mail.properties.mail.debug: true`) prints the protocol to
  **`System.out`**: `DEBUG SMTP: trying to connect to host "smtp.qq.com", port 465` (`SMTPTransport`
  L2187), `connected to host` (L2237), then `EHLO` … `QUIT`.
- The JUL logger **`org.eclipse.angus.mail.smtp`** at `FINE` receives the same lines, and its child
  **`org.eclipse.angus.mail.smtp.protocol`** at `FINEST` receives every command and reply
  (`SMTPTransport` L196, `TraceOutputStream`). Spring Boot installs the JUL→SLF4J bridge and
  propagates Logback levels back to JUL (`LogbackLoggingSystem` L146, L316). The lines therefore land
  in the application log **with timestamp and thread name**. creed-report's `application.yml` turns
  both on:

  ```yaml
  logging:
    level:
      org.eclipse.angus.mail.smtp: debug           # connect / delivered
      org.eclipse.angus.mail.smtp.protocol: trace  # EHLO, MAIL, RCPT, DATA, NOOP, QUIT
  ```

  `AUTH` is not traced (`mail.debug.auth` defaults to `false`, `SMTPTransport` L197), so the
  password does not reach the log. Message bodies after `DATA` do.

Count `connected to host` lines and you have the total. Check whether a second `connected` comes
before the previous `QUIT` and you have concurrency. This excerpt is from `/api/mail/probe` against a
local fake server (trimmed):

```text
# mode=concurrent&count=3 — three "connected" before any QUIT
[mail-probe-3] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[mail-probe-2] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[mail-probe-1] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
...
[mail-probe-1] org.eclipse.angus.mail.smtp.protocol: QUIT
[mail-probe-2] org.eclipse.angus.mail.smtp.protocol: QUIT

# mode=batch&count=2 — one "connected", the NOOP of §3 between the two messages, one QUIT
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp: connected to host "127.0.0.1", port: 2525
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: MAIL FROM:<ethan.caoq@foxmail.com>
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: 250 queued
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: NOOP
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: MAIL FROM:<ethan.caoq@foxmail.com>
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: 250 queued
[http-nio-48080-exec-2] org.eclipse.angus.mail.smtp.protocol: QUIT
```

What it lacks is why the subclass exists:

| | JavaMail debug log | `ConnectionCountingMailSender` + `FakeSmtpServer` |
|---|---|---|
| Total connections | Count `connected to host` lines by hand | `totalConnections()` |
| Max open at once | Reconstruct from interleaved timestamps | `maxConcurrentConnections()` |
| Which line belongs to which connection | No connection id or local port. `mail.debug` on `System.out` has no thread name either, so 5 threads' lines interleave unattributably | `transport#N` + thread on the client, `client port=` on the server |
| A test can assert on it | Only by capturing and parsing log output | Plain numbers: `assertThat(...).isEqualTo(1)` |
| Independent of the client | No — the client describing itself | The server counts sockets it accepted |

Use the debug log to watch a real server's conversation. Use the counters to *prove* the table.

## Edge cases: when the numbers differ from the table

The table is the normal case. These change it, and each one follows from the same source:

| Situation | What happens | Why (source) |
|---|---|---|
| Server drops the connection in the middle of a batch (idle timeout, `421`) | A batch uses **2+** connections in total; still at most 1 open at a time | L375: `!transport.isConnected()` → close, then `connectTransport()` again (L376–386) |
| Threads do not actually overlap (fast sends, a 1-thread executor, a lock around `send`) | Max concurrent is **below** the thread count, down to 1. Total is still one per `send` | Concurrency is whatever the callers create; the sender adds none and removes none |
| An `@Async` / executor-driven sender | Max concurrent ≤ **pool size** | Same: the pool is the only cap |
| Several threads, each sending a batch | Connections = threads, each carrying several messages | §3 inside each thread, §5 across threads |
| Connecting fails (DNS, refused, TLS) | No connection; **every** remaining message of that `send` is reported failed in one `MailSendException` | L391–396: all remaining messages are put in `failedMessages` |
| Login rejected | No connection; `MailAuthenticationException`, with no per-message list | L388–389: thrown straight out of the loop |
| One message rejected mid-batch (bad recipient) | The **same** connection carries on with the rest | L416–419: the error is recorded per message, the loop continues |
| `testConnection()` | Opens one connection and closes it | L344–354: `connectTransport()` + `close()` in `finally` |
| Server limits concurrent connections per account | Threads beyond the limit fail to connect or are refused | Server policy, not JavaMail — it is the practical cap on §5 |

Two things that are **not** extra open connections:

- **`TIME_WAIT`**: after `close()`, the side that closed first keeps the socket in `TIME_WAIT` for a
  while (about 30 s on macOS; see `netstat -an | grep 465`). No data flows and the server's
  connection slot is free. A tight loop of `send(msg)` still leaves a trail of them.
- **Cost per connection**: every new connection repeats the TCP handshake, the TLS handshake and
  `AUTH`. Against a remote server this is usually slower than the message itself. The only way
  `JavaMailSenderImpl` itself shares a connection is to pass several messages to **one** `send(...)`.
