# Apache HTTP Server + mod_proxy_cluster（HTTPS 注册）

`creed-simple-metrics` 通过 mod_cluster 把自己注册为节点的负载均衡器。**注册（MCMP）和业务流量都走 HTTPS**：

```
creed-simple-metrics ──MCMP over mTLS──▶ :6666  creed-httpd  :9443 ◀── 客户端 HTTPS
        ▲                                (docker)    │
        └──────────── HTTPS (Type=https) ────────────┘  httpd → 节点，校验 Creed CA
```

| 端口 | 用途 | TLS |
|---|---|---|
| `6666` | MCMP：`CONFIG` / `ENABLE-APP` / `STATUS` / `INFO`（也有一份 `/mod_cluster_manager`，给带证书的脚本用） | **双向 TLS**：必须出示 Creed CA 签发的客户端证书 |
| `16666` | `/mod_cluster_manager` 状态页，**给浏览器用** | 服务端 TLS，**不要客户端证书**；只发布在本机回环（`127.0.0.1` 和 `::1`） |
| `9443` | 业务入口，由 mod_proxy_cluster 转发到已注册节点 | 服务端 TLS（`creed-httpd` 证书） |

镜像：官方 `httpd:2.4.68` + 从源码编译的 `mod_manager` / `mod_proxy_cluster`（上游仓库没有发布 tag，
`Dockerfile` 里用 `MPC_REF` 钉住 commit）。

## 启动

```bash
# 1. 一次性：用现有 Creed CA 签发 httpd 证书（不要为此重跑 CA-Generation.sh —— 那会重建整个 CA）
.support/httpd/issue-cert.sh

# 2. 构建并启动
docker compose -f .support/httpd/docker-compose.yml up -d --build

# 3. 节点以 HTTPS 注册：modcluster profile（application-modcluster.yml）一次打开全部设置
mvn -pl creed-simple-metrics spring-boot:run -Dspring-boot.run.profiles=local,modcluster \
  -Dspring-boot.run.workingDirectory="$PWD"
```

`modcluster` profile = `enabled: true` + `proxies: 127.0.0.1:6666` + `manager-scheme: https` +
**`node.host: 192.168.65.254`**。要保留 `local`：命令行指定 profile 会替换 `spring.profiles.active`。
客户端证书默认取 `creed-gateway-partner-CLI-keystore.p12`（本模块的出站身份），见 `creed.mod-cluster.ssl.*`。

> **不用 profile、只开 `CREED_MODCLUSTER_ENABLED=true` 时，节点会以 `127.0.0.1` 注册**：注册 banner 全绿、
> context 显示 ENABLED，但所有请求都是 **503**（httpd 日志 `All workers are in error state` /
> `AH00957 ... 127.0.0.1:8096 failed`）—— 容器里的 127.0.0.1 是容器自己。这就是改用 profile 的原因。

## 查看已注册的实例

下面的输出都是实测结果（节点 `creed-simple-metrics-8096` 已注册时）。**没有节点注册时这些视图都是空的**，
要先把 `creed-simple-metrics`（或别的节点）起起来。

### 1. 浏览器：`https://localhost:16666/mod_cluster_manager`

用 **16666**，不是 6666。浏览器打不开 6666 有两层原因，第一层就足以挡住：

1. **6665–6669 是浏览器内置的「不安全端口」（IRC）**，Chrome / Firefox 根本不会发起连接，直接报
   `ERR_UNSAFE_PORT`（「无法访问此网站」）。httpd 日志里连一次连接都看不到 —— 这是实测排查出来的。
2. 就算端口放行，6666 还要求 Creed 客户端证书，浏览器没有就在 TLS 握手阶段被断开。

16666 是同一个页面：不在屏蔽列表里，不要客户端证书，只发布在本机回环（`127.0.0.1` 和 `::1`，因为 macOS
上 `localhost` 先解析成 `::1`）—— 页面上的 **Disable / Stop** 按钮会真的改变路由，不能对局域网开放。

首次打开会提示「您的连接不是私密连接」（`NET::ERR_CERT_AUTHORITY_INVALID`；httpd 日志里对应
`alert certificate unknown`），因为 Creed CA 不在系统信任列表里。两种处理：

- 点「高级 → 继续前往 localhost（不安全）」；或
- 一次性信任 Creed 根 CA（写入登录钥匙串，会弹框要求输入系统密码）：
  ```bash
  security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db \
    .support/scripts/pki/creed-CA-global-root.crt
  ```

页面里能看到：

```
Node creed-simple-metrics-8096 (https://192.168.65.254:8096):
Balancer: mycluster, ... Status: OK, ... Load: 100
Virtual Host 1: Contexts: /simple, Status: ENABLED   [Disable] [Stop]
Aliases: localhost
```

- 节点行的 **`Status: OK`** = httpd 能连上这个节点（`NOTOK` 见「踩过的坑」第 2 条）；
- context 行的 **`ENABLED`** = 流量会被转发过去；
- `Load` 是节点通过 `STATUS` 心跳上报的负载因子（1..100）；
- 页面顶部有 **Auto Refresh**、**show DUMP output**、**show INFO output** 三个链接。

### 2. 命令行（带客户端证书，走 6666）

```bash
P=.support/scripts/pki
mc() { curl -s --cacert $P/ca-chain.crt --cert $P/creed-gateway-partner-CLI.crt --key $P/creed-gateway-partner-CLI.key "$@"; }
```

> zsh 不会把 `$C="--cacert … --key …"` 这样的变量按空格拆开，所以用函数，不要用变量拼参数。

**状态页（文本化）**—— 最直观：

```bash
mc https://127.0.0.1:6666/mod_cluster_manager | sed 's/<[^>]*>/ /g' | grep -E 'Node|Status|Alias'
```

**MCMP `INFO`**—— 纯文本，适合脚本；模块启动 banner 判定「注册成功」用的就是它：

```bash
mc -X INFO https://127.0.0.1:6666/
```
```
Node: [0],Name: creed-simple-metrics-8096,Balancer: mycluster,LBGroup: ,Host: 192.168.65.254,Port: 8096,Type: https,Flushpackets: On,Flushwait: 10,Ping: 10,Smax: -1,Ttl: 60,Elected: 0,Read: 0,Transfered: 0,Connected: 0,Load: 100
Vhost: [0:1:0], Alias: localhost
Context: [0:1:0], Context: /simple, Status: ENABLED
```

**MCMP `DUMP`**—— 原始共享内存表，包括 balancer 的粘性会话配置：

```bash
mc -X DUMP https://127.0.0.1:6666/
```
```
balancer: [0] Name: mycluster Sticky: 1 [JSESSIONID]/[jsessionid] remove: 0 force: 1 Timeout: 0 maxAttempts: 1
node: [0:0],Balancer: mycluster,JVMRoute: creed-simple-metrics-8096,LBGroup: [],Host: 192.168.65.254,Port: 8096,Type: https,flushpackets: 1,flushwait: 10,ping: 10,smax: -1,ttl: 60,timeout: 0
host: 0 [localhost] vhost: 1 node: 0
context: 0 [/simple] vhost: 1 node: 0 status: 1
```

不需要证书的等价写法（本机）：`curl -s --cacert $P/ca-chain.crt https://localhost:16666/mod_cluster_manager`。

**每个 balancer 下有多少个实例**（`INFO` 每个节点一行 `Node:`，按 `Balancer:` 分组计数）：

```bash
mc -X INFO https://127.0.0.1:6666/ | grep '^Node:' \
  | sed -E 's/.*Name: ([^,]+),Balancer: ([^,]+),.*Host: ([^,]+),Port: ([^,]+),.*/\2 \1 \3:\4/' | sort \
  | awk '{n[$1]++; print} END {for (b in n) print "=> balancer " b ": " n[b] " instance(s)"}'
```
```
mycluster creed-simple-metrics-8096 192.168.65.254:8096
mycluster creed-simple-metrics-8097 192.168.65.254:8097
=> balancer mycluster: 2 instance(s)
```

不带证书的快速版（16666，只数节点、不分 balancer）：

```bash
curl -s --cacert $P/ca-chain.crt https://localhost:16666/mod_cluster_manager | grep -c '<h1> Node '
```

注意「已注册」≠「能接流量」：`Host` 是 `127.0.0.1` 的节点、或状态页里 `Status: NOTOK` 的节点虽然被计数，
httpd 并不会把请求发给它（见「踩过的坑」第 9 条）。刚停掉的节点也会在表里留到停机时的 `REMOVE-APP` 送达
或 `ttl` 过期为止。

### 3. 访问日志

```bash
docker logs -f creed-httpd
#   192.168.65.1 creed-gateway-partner-CLI "STATUS / HTTP/1.1" 200 ...      ← 心跳，带注册用的证书 CN
#   192.168.65.1 "GET /camel/api/ping HTTP/1.1" 200 ... -> https://192.168.65.254:8096   ← 业务流量选中的节点
```

`STATUS` 每 `creed.mod-cluster.status-interval`（默认 10s）一行：还在打就说明节点活着。

### 4. 节点自己的启动 banner

`creed-simple-metrics` 启动时打印 `mod_cluster registration` banner，某个代理那一行显示
**`REGISTERED`** 就表示该 httpd 已持有本节点（日志级别本身就是结论：INFO 全部成功 / WARN 部分 / ERROR 全失败）。

### 经 httpd 访问业务：按 context path 转发

节点注册的 context 就是它的 Tomcat context path（`creed-simple-metrics` 为 **`/simple`**），
mod_proxy_cluster 按它路由，httpd 这边**不需要**任何 `ProxyPass`：

| 请求 | 结果 |
|---|---|
| `https://localhost:9443/simple/camel/api/hello` | 转发到节点 → 200 |
| `https://localhost:9443/simple/actuator/health` | 转发到节点 → 200 |
| `https://localhost:9443/camel/api/hello`（不带前缀） | 不属于任何 context → httpd 自己回 404 |

完整请求清单（默认全部经 httpd）：`.support/http-client/creed-simple-metrics.http`。

```bash
curl --cacert $P/ca-chain.crt https://localhost:9443/simple/camel/api/hello
```

> 表里还残留一个注册了 `/` 的旧节点（比如用旧配置启动、没带 context path 的实例）时，不带前缀的请求会
> 匹配到它 —— 如果它还不可达，就是 **503** 而不是 404。重启该实例，或在 16666 状态页上 Stop 掉它的 `/`。

## 踩过的坑

1. **`node.host` 必须是 IP（或在节点本机能解析的名字）。** mod_cluster 在**节点这一侧**解析
   `externalConnectorAddress`（`TomcatConnector.getAddress`）。`host.docker.internal` 只在容器里能解析，
   在 Mac 上解析失败 → 返回 null → 每次 `CONFIG` 都在 `DefaultMCMPRequestFactory` 里 NPE：代理照常应答
   `INFO`/`STATUS`，却永远不知道这个节点（`MEM: Can't read node with "..." JVMRoute`）。Docker Desktop
   的宿主机网关是 `192.168.65.254`（`docker exec creed-httpd getent hosts host.docker.internal`）。
   模块现在会在启动时对无法解析的 `node.host` 打 ERROR。
2. **`SSLProxyEngine` 必须在 server 级别。** mod_proxy_cluster 在主 server 上创建并 ping 节点 worker，
   写在 9443 VirtualHost 里不生效：节点注册成功，但 `Status: NOTOK`，所有请求 503，日志
   `AH01961: failed to enable ssl support`。
3. **`ssl=true` 时 mod_cluster 一定会加载 keystore**，默认 `~/.keystore`（JKS），文件不存在就在 Tomcat
   生命周期里抛 `IllegalStateException`。所以 `creed.mod-cluster.ssl.*` 两个库都显式设置，且相对路径在
   模块里先转成绝对路径 —— 否则库会相对 `catalina.base`（内嵌 Tomcat 的临时目录）去找。
4. **按 upstream Containerfile 的逐模块 `buildconf/configure` 编译会失败**（模块共用 `../common`，
   第二个模块链接到第一个留下的 `common.lo`）；改用 upstream README 推荐的 cmake 构建。
5. **指令名是 `EnableMCMPReceive`**（旧文档里写作 `EnableMCPMReceive`）。
6. `SSLProxyCheckPeerName off`：节点通过网关 IP 访问，而它的证书只含
   `DNS:creed-gateway-partner, DNS:localhost`；证书链仍然校验。
7. **浏览器打不开 `https://localhost:6666/mod_cluster_manager`**：6665–6669 在浏览器的不安全端口列表里
   （IRC），连 TCP 连接都不会发起；而且 6666 还要求客户端证书。状态页因此放在 16666（无客户端证书、仅本机），
   见「查看已注册的实例」。第一次挪到 6667 时同样打不开，就是因为 6667 也在列表里。
8. **配置目录整体挂载，不要单文件挂载。** 编辑器和 `sed -i` 会用新 inode 替换 `httpd.conf`，单文件 bind
   mount 仍指向旧文件，`httpd -k graceful` 直接报 `Could not open configuration file`。现在挂的是
   `./conf` → `/usr/local/apache2/conf-creed`，改完配置执行：
   `docker exec creed-httpd httpd -k graceful -f /usr/local/apache2/conf-creed/httpd.conf`
9. **节点注册成 `127.0.0.1:8096` 时 httpd 连不上它**，看起来像「balancer 没配」：注册成功、context
   ENABLED，但请求 503，日志 `proxy_cluster_pre_request: CLUSTER: (balancer://mycluster). All workers are in
   error state` / `AH00957: attempt to connect to 127.0.0.1:8096 failed`。balancer 是有的（`mycluster` 由
   节点注册时自动创建，httpd 侧不需要 `ProxyPass`）—— 是唯一的 worker 不可达。用 `modcluster` profile 启动
   （它设置 `node.host=192.168.65.254`）。同一 context 下有别的可达节点时，httpd 会跳过 `NOTOK` 的那个。
10. **mod_proxy_cluster 在每个 VirtualHost 上都按已注册 context 转发**，所以
   `https://localhost:16666/simple/...` 也会被转发。业务入口仍按约定用 9443，16666 只用来看状态页。
