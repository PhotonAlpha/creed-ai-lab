# 手把手：手动配置 + nohup 启动

不用 `install.sh`，一步一步改配置、用 `nohup ... > xxx.log 2>&1 &` 启动。
做完以后：业务服务器（包括你这台 Mac）上的日志 → Fluent Bit → 日志服务器 → 在 Dashboards 网页里按
`index` / `host` / `source` 过滤查看。

文中用到的值，按你的实际情况替换：

| 写法 | 含义 | 文中的例子 |
|---|---|---|
| 日志服务器 IP | 跑 OpenSearch + Dashboards + Fluent Bit 汇聚端的那台 Linux | `10.0.0.50` |
| `/opt/logstack` | 把整个 `opensearch/` 文件夹拷到日志服务器上的位置 | |
| `/data/opensearch` | 数据目录（索引数据、日志、缓冲），要放在大磁盘上 | |
| `sg-sit` | 环境名，写进每条日志的 `index` 字段 | |

```
业务服务器 A（或你的 Mac）            日志服务器 10.0.0.50
┌─────────────────────────┐          ┌───────────────────────────────────────────────┐
│ *.log                   │          │  Fluent Bit 汇聚端 :24224                       │
│   ↓ tail                │ forward  │     ↓                                          │
│ Fluent Bit forwarder    │────────→ │  OpenSearch :9200 (ingest pipeline 解析字段)     │
│  加 index/host/source   │  :24224  │     ↓                                          │
└─────────────────────────┘          │  Dashboards :5601  ←── 浏览器                   │
                                     └───────────────────────────────────────────────┘
```

启动顺序：**OpenSearch → Dashboards → 初始化（模板 / pipeline / 索引模式）→ Fluent Bit 汇聚端 → 各台 forwarder**。

---

## 0. 准备（日志服务器上，只做一次）

### 0.1 包的架构要对

```bash
uname -m        # x86_64 → 要 linux-x64 的包；aarch64 → 要 linux-arm64 的包
```

`opensearch/` 文件夹里现在的 Linux 包都是 **arm64 / aarch64**（`opensearch-3.10.0-linux-arm64.tar.gz`、
`fluent-bit-5.2.0-1.aarch64.rpm`）。服务器是 x86_64 的话，都要重新下载 x64 / x86_64 版本。

### 0.2 拷文件

在 Mac 上执行（解压目录很大，拷 tar 包到服务器上再解压更快）：

```bash
cd ~/Desktop/workspace/sourcecode/github_space/creed-ai-lab
rsync -av --exclude 'opensearch-3.10.0/' --exclude 'opensearch-dashboards-3.10.0/' \
      --exclude 'fluent-bit/' --exclude 'fluent-bit-[0-9]*/' --exclude 'fluent-bit-pkg/' --exclude '*.pkg' \
      --exclude 'fluent-bit-browser-experimental/' --exclude 'fluent-bit-manual/state/' --exclude '.DS_Store' \
      opensearch/ root@10.0.0.50:/opt/logstack/
```

在日志服务器上解压：

```bash
cd /opt/logstack
tar -xzf opensearch-3.10.0-linux-x64.tar.gz
tar -xzf opensearch-dashboards-3.10.0-linux-x64.tar.gz
# Fluent Bit 的安装在第 4 节
```

### 0.3 用户、目录、系统参数

**OpenSearch 和 Dashboards 都不能用 root 启动**：OpenSearch 直接拒绝，Dashboards 要加 `--allow-root`。
下面用一个普通用户 `logstack`；也可以直接用你自己的登录用户，把 `logstack` 换成你的用户名即可。

```bash
sudo useradd -m logstack                      # 已有普通用户可跳过
sudo mkdir -p /data/opensearch/{data,logs,dashboards,fluent-bit/buffer}
sudo chown -R logstack: /opt/logstack /data/opensearch

# OpenSearch 必需：mmap 数量。不改的话启动日志里会报 max virtual memory areas vm.max_map_count [65530] is too low
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-opensearch.conf
sudo sysctl --system

# 文件句柄数 ≥ 65535
echo 'logstack - nofile 65535' | sudo tee /etc/security/limits.d/99-opensearch.conf
sudo su - logstack -c 'ulimit -n'             # 重新登录后应输出 65535
```

以后所有启动命令都在 `logstack` 用户下执行：`sudo su - logstack`。

---

## 1. OpenSearch（`opensearch-3.10.0/`）

只改两个文件、删一个插件，其他（`log4j2.properties`、`opensearch-security/` 等）都不动。

### 1.0 删除 Performance Analyzer 插件（必须）

3.10.0 的包里有这个插件，却没带它的配置目录 `config/opensearch-performance-analyzer/`，启动时会报
`FileNotFoundException: .../config/opensearch-performance-analyzer/plugin-stats-metadata`。日志平台用不到它（9600 端口的性能指标），删掉：

```bash
cd /opt/logstack/opensearch-3.10.0
./bin/opensearch-plugin remove opensearch-performance-analyzer
```

### 1.1 `opensearch-3.10.0/config/opensearch.yml` —— 整个文件替换成：

```yaml
cluster.name: logstack
node.name: log-01                       # 随意，显示用

# 9200 对外（业务查询 / Dashboards），9300 只在本机（单节点不需要节点间通信）
network.host: 0.0.0.0
http.port: 9200
transport.host: 127.0.0.1
transport.port: 9300
discovery.type: single-node

path.data: /data/opensearch/data
path.logs: /data/opensearch/logs

# 手动启动时没有 systemd 的 LimitMEMLOCK，开了会启动失败，所以关掉
bootstrap.memory_lock: false

# 关闭 security 插件：不用证书、不用账号密码。安全完全靠防火墙，见 README 顶部警告
plugins.security.disabled: true

# 磁盘水位：85% 不再分配新分片，90% 迁移，95% 索引只读
cluster.routing.allocation.disk.watermark.low: 85%
cluster.routing.allocation.disk.watermark.high: 90%
cluster.routing.allocation.disk.watermark.flood_stage: 95%

# 删索引必须写全名，防止 DELETE /* 误删
action.destructive_requires_name: true
```

### 1.2 新建 `opensearch-3.10.0/config/jvm.options.d/heap.options` —— 堆大小

```
-Xms4g
-Xmx4g
```

取物理内存的一半，最大 31g，两个值必须相同（`free -g` 看内存）。不要去改 `config/jvm.options` 本身：
`jvm.options.d/` 里的设置会覆盖它，以后换版本只需拷这一个小文件。

### 1.3 启动

```bash
cd /opt/logstack/opensearch-3.10.0          # 必须先 cd 进来：jvm.options 里有相对路径 agent/opensearch-agent.jar
nohup ./bin/opensearch > /data/opensearch/logs/opensearch-console.log 2>&1 &
echo $! > /data/opensearch/opensearch.pid
```

等 20～60 秒，验证：

```bash
curl -s http://127.0.0.1:9200                                   # 返回 JSON，number = 3.10.0
curl -s 'http://127.0.0.1:9200/_cluster/health?pretty'          # status: green
```

> **在 macOS 上跑 Linux 包**会报 `could not find java in bundled jdk at .../jdk.app/Contents/Home/bin/java`：
> 包里自带的是 Linux 的 JDK，macOS 上启动脚本去找 `jdk.app`，找不到。用 `OPENSEARCH_JAVA_HOME` 指定一个本机 JDK（21 或以上）：
>
> ```bash
> export OPENSEARCH_JAVA_HOME=/Users/ethan/Desktop/workspace/tools/jdk/jdk-25.0.3+9/Contents/Home
> nohup ./bin/opensearch > opensearch-console.log 2>&1 &
> ```
>
> Linux 上不需要设，用自带的 `jdk/`。

起不来先看 `/data/opensearch/logs/opensearch-console.log` 的最后几十行。详细日志在
`/data/opensearch/logs/logstack.log`（文件名就是 `cluster.name`）。

---

## 2. Dashboards（`opensearch-dashboards-3.10.0/`）

### 2.1 删除 security 插件（必须）

OpenSearch 那边关了 security，Dashboards 的 security 插件还在的话会一直连不上、报错退出：

```bash
cd /opt/logstack/opensearch-dashboards-3.10.0
./bin/opensearch-dashboards-plugin remove securityDashboards
```

### 2.2 `opensearch-dashboards-3.10.0/config/opensearch_dashboards.yml` —— 整个文件替换成：

```yaml
server.host: "0.0.0.0"
server.port: 5601
server.name: "logstack"

# 默认文件里是 https://localhost:9200 + kibanaserver 账号 + 一堆 opensearch_security.* 配置，
# 插件删掉以后这些键都不认识，Dashboards 会报 Unknown configuration key 退出 —— 所以整个文件替换，不要只改一行
opensearch.hosts: ["http://127.0.0.1:9200"]
opensearch.requestTimeout: 60000

path.data: "/data/opensearch/dashboards"

uiSettings:
  overrides:
    "dateFormat": "YYYY-MM-DD HH:mm:ss.SSS"
    "dateFormat:tz": "Asia/Singapore"
    "timepicker:timeDefaults": '{ "from": "now-7d", "to": "now" }'
```

### 2.3 启动

```bash
cd /opt/logstack/opensearch-dashboards-3.10.0
nohup ./bin/opensearch-dashboards > /data/opensearch/logs/dashboards.log 2>&1 &
echo $! > /data/opensearch/dashboards.pid
```

第一次启动要 1～2 分钟。日志里出现 `http server running at http://0.0.0.0:5601` 就好了：

```bash
tail -f /data/opensearch/logs/dashboards.log | grep -m1 'server running'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5601/api/status      # 200
```

---

## 3. 初始化：索引模板、生命周期、解析规则、索引模式

用 `bootstrap/` 里的四个脚本，都是普通的 curl 调用，可以重复执行。

```bash
cd /opt/logstack
./bootstrap/01-index-template.sh     # 索引模板 app-logs-*：字段类型（index/host/source 等是 keyword），1 分片 0 副本
./bootstrap/02-ism-policy.sh         # 生命周期：索引建好 7 天后自动删除
./bootstrap/03-ingest-pipeline.sh    # 解析规则：从原始行拆出时间 / level / app / thread / logger / trace_id / msg
./bootstrap/04-index-pattern.sh      # Dashboards 里的索引模式 app-logs-*、Show Source 链接、上下文视图设置（必须在 Dashboards 起来之后；有数据以后再执行一次）
```

保留天数和时区取自 `install.sh` 顶部的 `RETENTION_DAYS`、`TIMEZONE`，改那两行即可，不用改脚本。

先确认解析规则能认出你的日志格式（只演示，不写数据）：

```bash
./bootstrap/03-ingest-pipeline.sh --simulate
```

`creed-ai-lab/logs` 里实际出现的三种格式都能解析：

| 格式 | 例子 | 出处 |
|---|---|---|
| logback 自定义 | `20-11-2026 09:24:02.299  INFO [creed-resource-veracode,<traceId>,<spanId>] [http-nio-18097-exec-4] creed.tracker.http.outbound [82] : ...` | veracode 等 |
| logback 自定义 | `20-11-2026 00:03:08.324  INFO [http-nio-8600-exec-2][,] c.creed.parent.service.DictLoadService  [41] : ...` | comparison-console |
| Spring Boot 默认 | `2026-10-17T00:50:05.534+08:00  INFO 84164 --- [creed-author-server] [main] [...] c.c.auth.App : ...` | author-server、catalog、order… |

---

## 4. Fluent Bit：两个包、两份配置

配置都在 **`fluent-bit-manual/`**，已经写好，汇聚端和 forwarder 各一份，两个平台通用：

| 文件 | 作用 | 跑在哪 |
|---|---|---|
| `aggregator.yaml` | 汇聚端：收 forward(:24224) → 写 OpenSearch(:9200) | 日志服务器（和 OpenSearch 同一台） |
| `forwarder.yaml` | 读日志文件，加 `index` / `host` / `source`，发给汇聚端 | 每台有日志的机器 |
| `parsers.yaml` | 多行合并规则（指向 `../config/parsers.yaml` 的软链接） | 两者都用 |

用哪个包：

| 包 | 平台 | `fluent-bit` 可执行文件 | 用来跑 |
|---|---|---|---|
| `fluent-bit-5.2.0-1.aarch64.rpm` | Linux（RHEL 9，ARM） | `/opt/fluent-bit/bin/fluent-bit` | Linux 日志服务器上的汇聚端（4.1）；Linux 业务服务器上的 forwarder（4.4） |
| `fluent-bit-pkg/`（`fluent-bit-5.1.5-apple.pkg` 解开的） | macOS（Apple 芯片） | `fluent-bit-pkg/fluent-bit-5.1.5-apple-binary.pkg/Payload/usr/local/bin/fluent-bit` | Mac 当日志服务器时的汇聚端（4.2）；Mac 上的 forwarder（4.3） |

**两条规则，两个包都一样：**

1. **启动前先 `cd fluent-bit-manual`。** 配置里的路径全是相对路径：`parsers_file` 按配置文件所在目录找，
   `storage.path` / `db` 按当前目录找。在这个目录下启动，两者才一致；缓冲和书签会落在 `fluent-bit-manual/state/`（已在 `.gitignore`）。
2. **forwarder 启动时显式给 `HOSTNAME`**，它就是 `host` 字段的值。bash 的 `HOSTNAME` 不导出，不给就是空的。

### 4.1 Linux：RPM 包（日志服务器上的汇聚端）

```bash
cd /opt/logstack
sudo dnf install -y ./fluent-bit-5.2.0-1.aarch64.rpm      # 依赖由 dnf 补齐；x86_64 服务器换 x86_64 的包
/opt/fluent-bit/bin/fluent-bit --version
```

这个 RPM 依赖 `libssl.so.3`，是 **RHEL 9** 系的包。RHEL 8 要下载 `centos/8` 目录下的包。
不想装 RPM、直接用 Mac 上解开的 `fluent-bit-5.2.0-1.aarch64/opt/fluent-bit/bin/fluent-bit` 也行，但它需要系统里有
libyaml、libpq、cyrus-sasl-lib、openssl 3、systemd-libs。先 `ldd <那个文件> | grep 'not found'`，有缺的就 dnf 装上（或者干脆装 RPM）。

RPM 自带的 systemd 服务（读 `/etc/fluent-bit/fluent-bit.conf`）不用启动，这里用 nohup 手动启动：

```bash
cd /opt/logstack/fluent-bit-manual
/opt/fluent-bit/bin/fluent-bit -c aggregator.yaml --dry-run            # configuration test is successful
nohup /opt/fluent-bit/bin/fluent-bit -c aggregator.yaml > aggregator.log 2>&1 &
echo $! > aggregator.pid
```

`aggregator.yaml` 默认就写 `127.0.0.1:9200`，不用改。唯一可能要调的是最后的缓冲上限 `storage.total_limit_size`（默认 5G，只在 OpenSearch 停掉期间占用）。

防火墙：业务服务器要能连日志服务器的 24224，办公网要能连 5601：

```bash
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<业务服务器IP>/32 port port=24224 protocol=tcp accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<办公网段>/24 port port=5601 protocol=tcp accept'
sudo firewall-cmd --reload
```

（`firewall.sh` 做的就是这个，按 `install.sh` 顶部的 `FORWARDER_IPS`、`OFFICE_CIDR` 生成规则。）

### 4.2 macOS：pkg 包（Mac 当日志服务器时的汇聚端）

Mac 当日志服务器，就是 OpenSearch、Dashboards、汇聚端都跑在这台 Mac 上，其他机器（或本机）的 forwarder 往这台 Mac 的 24224 发。

**先确认前面几步按 Mac 的方式做了**（和 Linux 的差异）：

| 步骤 | Mac 上的做法 |
|---|---|
| 0.3 系统参数 | `vm.max_map_count`、`limits.d` 都不用做（Linux 专有）。用自己的登录用户启动即可，同样不能用 root |
| 1.1 `opensearch.yml` | `path.data` / `path.logs` 改成 Mac 上存在的目录，例如 `opensearch-3.10.0/data`、`opensearch-3.10.0/logs` |
| 1.3 启动 OpenSearch | 包里自带的是 Linux JDK：`export OPENSEARCH_JAVA_HOME=<本机 JDK>/Contents/Home`，或者建 `jdk.app/Contents/Home` 软链接指向本机 JDK |
| 2.2 `opensearch_dashboards.yml` | `server.host` 必须写 IP（`"127.0.0.1"` 或 `"0.0.0.0"`），写 `localhost` 会只绑 IPv6，bootstrap 脚本连不上；`path.data` 改成 Mac 上的目录 |
| 2.3 启动 Dashboards | 包里自带的是 Linux Node：`mv node node.linux`，让它用 Homebrew 的 `node` |

**准备 pkg 里的 `fluent-bit`**，两种用法任选一种：

```bash
# A. 不安装，直接用解开的文件（下文用这个）
FB=~/Desktop/workspace/sourcecode/github_space/creed-ai-lab/opensearch/fluent-bit-pkg/fluent-bit-5.1.5-apple-binary.pkg/Payload/usr/local/bin/fluent-bit
$FB --version                                   # Fluent Bit v5.1.5

# B. 正式安装到 /usr/local/bin/fluent-bit，之后 FB=/usr/local/bin/fluent-bit
#    （会同时注册一个开机启动的 LaunchDaemon，读它自己的 .conf；用不上可以不管）
sudo installer -pkg fluent-bit-5.1.5-apple.pkg -target /
```

这个 pkg 里的 `fluent-bit` 依赖 Homebrew 的 `libyaml`、`openssl@4`、`zstd`（你这台 Mac 上都有）。
换一台没有 Homebrew 的 Mac 会报 `Library not loaded`，先 `brew install libyaml openssl@4 zstd`。

**启动汇聚端**（`aggregator.yaml` 不用改：收 `0.0.0.0:24224`，写 `127.0.0.1:9200`）：

```bash
cd ~/Desktop/workspace/sourcecode/github_space/creed-ai-lab/opensearch/fluent-bit-manual
$FB -c aggregator.yaml --dry-run                                   # configuration test is successful
nohup $FB -c aggregator.yaml > aggregator.log 2>&1 &
echo $! > aggregator.pid
lsof -nP -iTCP:24224 -sTCP:LISTEN                                  # 应看到 fluent-bit 在 *:24224 监听
```

**让别的机器能发进来：**

- **IP**：forwarder 里 `outputs[0].host` 写这台 Mac 的 IP（`ipconfig getifaddr en0` 查）。Mac 的 IP 常常是 DHCP 分配的，
  变了以后各台 forwarder 都要改，最好在路由器上给它固定 IP。
- **防火墙**：系统设置 → 网络 → 防火墙。关着就不用管（你这台现在是关的）。开着的话，放行 fluent-bit：

  ```bash
  sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add "$FB"
  sudo /usr/libexec/ApplicationFirewall/socketfilterfw --unblockapp "$FB"
  ```

  5601（Dashboards，也就是 Node）同理：要让别人从浏览器访问，`server.host` 写 `"0.0.0.0"`，并放行 Homebrew 的 `node`。
- **别让 Mac 睡眠**：睡眠期间收不到数据（forwarder 会先存在自己的磁盘缓冲里，Mac 醒来后补发，不会丢，只是延迟）。
  当服务器用时让它保持唤醒：

  ```bash
  nohup caffeinate -ims > /dev/null 2>&1 &  echo $! > caffeinate.pid      # 停：kill $(cat caffeinate.pid)
  ```

**本机的日志也要收**：在同一台 Mac 上再起一个 forwarder（4.3），`outputs[0].host` 保持 `127.0.0.1`。
两份配置的指标端口不同（2020 / 2021），缓冲目录不同（`state/aggregator`、`state/forwarder`），互不冲突。先起汇聚端，再起 forwarder。

### 4.3 macOS：pkg 包（Mac 上的 forwarder）

`fluent-bit` 的准备同 4.2（`FB=...`）。打开 `fluent-bit-manual/forwarder.yaml`，看三处 `TODO`：

| 位置 | 现在的值 | 什么时候改 |
|---|---|---|
| `inputs[0].path` | `/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/logs/*.log` | 读别的目录时 |
| `filters[0].record` 的 `index` | `index sg-sit` | 别的环境 |
| `outputs[0].host` | `127.0.0.1` | 汇聚端在另一台机器（如 `10.0.0.50`）时 |

启动：

```bash
cd ~/Desktop/workspace/sourcecode/github_space/creed-ai-lab/opensearch/fluent-bit-manual
$FB -c forwarder.yaml --dry-run
HOSTNAME=ethan-mac nohup $FB -c forwarder.yaml > forwarder.log 2>&1 &
echo $! > forwarder.pid
```

> 这台 Mac 的主机名是 `192.168.5.11`，`hostname -s` 只会得到 `192`，所以这里直接写一个好认的名字。
> Linux 服务器一般用 `HOSTNAME=$(hostname -s)` 就行。

### 4.4 Linux 业务服务器上的 forwarder

和 4.3 一样，只是可执行文件换成 RPM 的：

```bash
# 在 Mac 上：把配置拷过去（-L：parsers.yaml 是软链接，拷实体文件）
rsync -avL --exclude state --exclude '*.log' --exclude '*.pid' \
      opensearch/fluent-bit-manual/ root@<业务服务器>:/opt/logstack-forwarder/

# 在业务服务器上
sudo dnf install -y ./fluent-bit-5.2.0-1.aarch64.rpm
cd /opt/logstack-forwarder
vi forwarder.yaml            # path 改成这台机器的日志目录；outputs.host 改成 10.0.0.50
/opt/fluent-bit/bin/fluent-bit -c forwarder.yaml --dry-run
HOSTNAME=$(hostname -s) nohup /opt/fluent-bit/bin/fluent-bit -c forwarder.yaml > forwarder.log 2>&1 &
echo $! > forwarder.pid
```

启动用户要对 `/opt/logstack-forwarder/` 有写权限（`state/` 建在这里），对日志文件有读权限。

## 5. 确认数据到了

```bash
tail -f fluent-bit-manual/forwarder.log           # 不应有 [error]
tail -f fluent-bit-manual/aggregator.log          # 被 OpenSearch 拒收时这里有原因（trace_error）
curl -s 'http://127.0.0.1:9200/_cat/indices/app-logs-*?v'          # docs.count 在涨
curl -s 'http://127.0.0.1:9200/app-logs-*/_search?size=0&pretty' -H 'Content-Type: application/json' -d '{
  "aggs": { "index":  { "terms": { "field": "index" } },
            "host":   { "terms": { "field": "host" } },
            "source": { "terms": { "field": "source", "size": 50 } } } }'
```

`logs/` 里只有 `*.log` 会被读。`.log.gz` 归档和 `comparison-console.log-2026-11-07.0` 这类改名归档不会被读；
要导入它们，解压或拷成 `*.log` 放进被监控的目录，例如 `gunzip -c x.log.gz > logs/import-x.log`。

## 6. 在网页上看日志

1. 浏览器打开 `http://10.0.0.50:5601`，左上角 ☰ → **Discover**。索引模式默认是 `app-logs-*`。
2. **右上角时间范围改大。** 时间轴用的是日志行里的时间，不是导入时间：`logs/` 里有 10 月中旬的日志，
   默认的「最近 7 天」看不到它们。选 **Last 60 days**，或者点日期写绝对时间。
3. 顶部搜索框输入查询（DQL）。带 `-`、`/` 的值要加双引号：

| 想找 | Splunk 写法 | Discover（DQL）写法 |
|---|---|---|
| 某个环境 | `index=sg-sit` | `index:"sg-sit"` |
| 某台机器 | `index=sg-sit host=app-01` | `index:"sg-sit" and host:"app-01"` |
| 某个文件 | `source=/Users/.../logs/creed-resource-veracode.log` | `source:"/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/logs/creed-resource-veracode.log"` |
| 文件名模糊匹配 | `source=*veracode*` | `source:*veracode*` |
| 某个应用的错误 | | `app:"creed-resource-veracode" and level:ERROR` |
| 一次请求的全链路 | | `trace_id:"6affa131fbb0b2f38e6e5933eb6f59be"` |
| 原文全文搜索 | | `"Application run failed"` |
| 解析失败的行 | | `parse_error:*` |

4. 左侧字段列表里，把 `index`、`host`、`source`、`level`、`msg` 点 **+** 加成列，表格就清楚了。
   点任意字段名能看到它的取值分布（比如有哪些 host）。
5. 右上角 **Save** 把查询存成「已保存的搜索」，下次直接打开。
6. 展开一行（最左边的 ↗ / ▸）弹出 **Document Details**：**Table** 页逐个字段，**JSON** 页整条原始文档。
   `log` 是原文整行，Java 堆栈合在一条里，不会被拆开。只想看原文时把 `log` 加成列。
   长堆栈在列表里不折叠，是 04 脚本设的 `truncate:maxHeight = 0`。

### 6.1 Splunk 的 Show Source：同一个文件里前后 100 条，像看日志文件一样

04 脚本已经把上下文视图设好了（Advanced settings 里能看到、能改）：

| 设置 | 值 | 效果 |
|---|---|---|
| `context:defaultSize` | 100 | 打开时，选中那条的前后各 100 条（默认 5） |
| `context:step` | 100 | **Load newer / Load older** 每点一次再加 100 条 |
| `context:tieBreakerFields` | `offset` | 先按 `@timestamp` 排，同一毫秒再按文件里的位置排，顺序和文件一致 |
| `defaultColumns` | `log`, `show_source` | Discover 默认显示原文和 Show Source 链接两列，不用展开就能点（Show Source 打开的上下文视图只显示 `log` 一列，由链接指定） |
| `truncate:maxHeight` | 0 | 长堆栈完整展开，不折叠 |

**用法：点表格里 `show_source` 列的 Show Source 链接（或展开一条，点 `show_source` 那一行）。**
**要在新标签页打开：Cmd+点击（Windows / Linux 用 Ctrl+点击）或鼠标中键。** 直接点击会在当前标签页打开：
Url 格式生成的是 `target="_blank"`，但 Discover 把单元格内容交给 DOMPurify 清洗后再显示，`target` 属性被去掉了，没有设置项能改。

1. Discover 里照常搜索，比如 `"6aff23516b3ce7d2bb6dfe1649dad5b1"` 或 `level:ERROR`。
2. 展开要看的那一条（最左边的 ↗ / ▸），在字段表里找到 `show_source`，点 **Show Source**。
3. 新标签页打开上下文视图：上面 10 条是之后的日志，接着是高亮的这一条，下面 100 条是之前的日志。
   全部来自同一个文件（`source`）、同一台机器（`host`），这两个过滤条件已经带好、是生效的，顶部能看到。
   往下滚动会继续显示；想看更多就点顶部 **Load newer** / 底部 **Load older**，每次加 100 条。
   上面只放 10 条是有意的：上下文视图是倒序（新的在上），上面放 100 条会把点的这一条挤出第一屏。
   这个数写在 03 的 show_source 链接里（`predecessorCount:10,successorCount:100`）。

这个链接是入库时生成的：解析规则（03）给每条日志写一个 `show_source` 字段，内容是这条日志的上下文视图地址，
已经带上了 `source` + `host` 过滤条件。索引模式（04）把这个字段显示成名为 Show Source 的链接。

为什么不直接用 flyout 顶部自带的 **View surrounding documents**：
- 上下文视图不认顶部搜索框里的查询（query），只认过滤条件（filter）。
- 它把 Discover 里的普通过滤条件带过去后，会变成**禁用**的（灰色）。

所以直接用 View surrounding documents，前后会混进同一时间别的服务写的日志。
要用的话，进去以后点 **+ Add filter** 加 `source` `is` `<路径>`（以及 `host`）。

和 Splunk 不同的一点：上下文视图**新的在上、旧的在下**。Dashboards 源码里写死了倒序，没有设置能改。

还想更像日志文件：Advanced settings 把 `doc_table:hideTimeColumn` 打开，表格就不再单独显示时间列
（`log` 原文里本身有时间）。这个设置对 Discover 也生效，所以 04 脚本没有改它。

**`offset` 只有加了 `offset_key` 之后新导入的数据才有**，旧数据按下面「重新导入」做一遍。
`show_source` 不用重新导入，旧数据重跑一遍解析规则就会有：
`curl -XPOST 'http://127.0.0.1:9200/app-logs-*/_update_by_query?pipeline=app-logs-pipeline&conflicts=proceed'`。

`show_source` 只显示成一长串 URL、不是链接时，说明索引模式的字段列表是空的。
- 再执行一次 `./bootstrap/04-index-pattern.sh`，或者 Dashboards Management → Index patterns → app-logs-* → 右上角 **Reload field list**。
- 第一次装、还没有数据时执行 04 就会这样，所以有数据以后要再执行一次 04。

### 6.2 重新导入（删书签 + 删旧数据）

改了 forwarder 里影响字段的配置（加字段、改 index 名等）以后，用这个办法让已有日志按新配置重来一遍。

```bash
cd ~/Desktop/workspace/sourcecode/github_space/creed-ai-lab/opensearch/fluent-bit-manual
kill $(cat forwarder.pid)                                   # 1. 停 forwarder
rm -f state/forwarder/tail.db*                              # 2. 删书签：下次从头读每个文件
rm -rf state/forwarder/storage                              #    还没发出去的缓冲也不要了
# 3. 删旧索引。OpenSearch 不允许 DELETE app-logs-*，要逐个写名字
for i in $(curl -s 'http://127.0.0.1:9200/_cat/indices/app-logs-*?h=index'); do
  curl -s -XDELETE "http://127.0.0.1:9200/$i"; echo
done
HOSTNAME=ethan-mac nohup $FB -c forwarder.yaml > forwarder.log 2>&1 &   # 4. 重新启动 forwarder（同 4.3）
echo $! > forwarder.pid
```

- 第 3 步不能省：`generate_id` 按记录内容生成 `_id`。多了 `offset` 字段以后，同一行日志的 `_id` 也变了。
  旧索引留着的话，每条日志会有新旧两份。
- 多台机器都往同一个汇聚端发数据时，删索引会把所有机器的数据都删掉。所以每台 forwarder 都要做第 1、2、4 步，
  不然别的机器的历史日志就没了。

---

## 7. 停止 / 重启 / 常见问题

### 查看是否在运行

```bash
cd ~/Desktop/workspace/sourcecode/github_space/creed-ai-lab/opensearch/fluent-bit-manual   # Linux 业务服务器上是 /opt/logstack-forwarder

# pid 文件里记的进程还在不在（在就打印进程，不在就提示）
for f in forwarder aggregator; do
  [ -f $f.pid ] || { echo "$f: 没有 $f.pid"; continue; }
  ps -o pid,etime,command -p "$(cat $f.pid)" | tail -n +2 | grep . || echo "$f: pid $(cat $f.pid) 已经不在了"
done

# 实际在跑的 Fluent Bit 进程（pid 文件丢了 / 对不上时用这个找）
ps aux | grep '[f]luent-bit -c'

# 端口：汇聚端监听 24224；本机指标 2020（汇聚端）/ 2021（forwarder）
lsof -nP -iTCP:24224 -sTCP:LISTEN
curl -s http://127.0.0.1:2020/api/v1/health; echo        # 汇聚端：ok
curl -s http://127.0.0.1:2021/api/v1/uptime; echo        # forwarder
```

pid 文件和实际进程对不上（比如另一个终端里重新启动过）时，用 `ps` 找到的 pid 覆盖它：
`echo <pid> > forwarder.pid`。同一目录只能有一个 forwarder：书签被占用会报 `database is locked`（见下面常见问题）。

### 停止和重启

```bash
# 停（顺序反过来：forwarder → 汇聚端 → Dashboards → OpenSearch）。SIGTERM 会正常关闭，Fluent Bit 会先把缓冲写出去
kill $(cat fluent-bit-manual/forwarder.pid)              # 各台 forwarder（Linux 业务服务器上是 /opt/logstack-forwarder/forwarder.pid）
kill $(cat fluent-bit-manual/aggregator.pid)                # 汇聚端（Linux 和 Mac 都在 fluent-bit-manual/ 下）
kill $(cat /data/opensearch/dashboards.pid)
kill $(cat /data/opensearch/opensearch.pid)
```

再启动就是按 1.3 → 2.3 → 汇聚端（4.1 或 4.2）→ forwarder（4.3 / 4.4）重新执行一遍 nohup 命令（第 3 步的初始化不用再做）。
`nohup` 启动的进程**服务器重启后不会自动起来**；要开机自启，用 `install.sh` 的 systemd 方式。

### 改了配置以后

| 改了什么 | 要做什么 |
|---|---|
| `opensearch.yml` / `heap.options` | 重启 OpenSearch |
| `opensearch_dashboards.yml` | 重启 Dashboards |
| Fluent Bit 的 yaml | 先 `--dry-run`，再重启那个 Fluent Bit |
| 解析规则 `bootstrap/03-ingest-pipeline.sh` | 重新执行它（不用重启任何服务）。已入库的旧数据按新规则重解析见 README「解析失败」一节 |
| forwarder 加 / 改字段（如 `offset_key`） | 只影响之后读到的日志；旧数据要重新导入（6.2） |

### 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| OpenSearch 日志 `can not run opensearch as root` | 换普通用户（0.3） |
| `max virtual memory areas vm.max_map_count [65530] is too low` | 没做 0.3 的 sysctl |
| `FileNotFoundException ... opensearch-performance-analyzer/plugin-stats-metadata` | 没删 Performance Analyzer 插件（1.0） |
| OpenSearch 报 `agent/opensearch-agent.jar` 找不到 | 启动前没 `cd` 进 `opensearch-3.10.0/` |
| Dashboards 报 `Unknown configuration key(s): "opensearch_security..."` | `opensearch_dashboards.yml` 没整个替换（2.2） |
| Dashboards 一直 `Unable to connect` / security 相关报错 | 没删 `securityDashboards` 插件（2.1） |
| `04-index-pattern.sh` 卡住几分钟后失败 | 脚本连 `127.0.0.1:5601`，而 Dashboards 只听在 IPv6 `[::1]`（`server.host` 没写或写成 `localhost` 时，macOS 上会这样）。`server.host` 写成 IP（`"127.0.0.1"` 或 `"0.0.0.0"`）后重启 Dashboards；或者临时 `OSD_URL=http://localhost:5601 ./bootstrap/04-index-pattern.sh` |
| OpenSearch 日志里有 `RestPPLQueryAction ... 'source = ' <-- HERE` | Dashboards 页面发了一条空的 PPL 查询（还没有索引模式时打开 Discover 等页面会这样），无害，忽略 |
| Discover 里没数据 | ① 时间范围不够大（第 6 节第 2 步）；② `curl .../_cat/indices/app-logs-*?v` 看有没有索引；③ 看 `fluent-bit-manual/forwarder.log` 和 `aggregator.log` |
| `host` 字段是空的 | 启动 forwarder 时没写 `HOSTNAME=...`（4.3） |
| forwarder 启动报 `[sqldb] error=database is locked` / `could not open/create database` | 同一目录下已经有一个 forwarder 在跑，书签 `tail.db` 被它独占（`db.locking: true`）。`ps aux \| grep '[f]luent-bit -c forwarder'` 看一下：要么别再启动，要么先 `kill $(cat forwarder.pid)` 再启动 |
| `index` / `host` 显示 `unknown` | 那条数据不是经过 forwarder 来的，或者 forwarder 配置里漏了 record_modifier |
| 一条日志被拆成很多条 / 很多条被合成一条 | 日志行首格式和 `parsers.yaml` 里的正则对不上，改那两条正则，重启 forwarder |
| 很多 `parse_error:*` | 解析规则不认识这种格式：用 `03-ingest-pipeline.sh --simulate` 调试，改 patterns |
| 想把某台机器的日志全部重新导一遍 | 停 forwarder → 删除它的 `db` 书签文件 → 启动。会产生重复数据，先删掉对应日期的 `app-logs-*` 索引 |
