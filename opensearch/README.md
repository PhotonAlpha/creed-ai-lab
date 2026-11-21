# logstack —— 内网单机版 OpenSearch 日志平台

一台内网主机（10.0.0.50）上同时跑 OpenSearch、OpenSearch Dashboards、Fluent Bit 汇聚端，业务服务器上的 Fluent Bit 把日志 forward 过来，按 trace ID 查日志。

> 不想用 `install.sh` 的话，看 **[MANUAL.md](MANUAL.md)**：逐个改配置文件、用 `nohup` 手动启动的手把手教程。

> ⚠️ **这套配置一旦暴露到公网就是裸奔。**
> security 插件已关闭，没有 TLS，没有账号密码，谁能连上 9200 谁就能读写、删除全部数据；能连上 5601 就能看全部日志。
> 安全性**完全依赖防火墙**和"主机只在内网"这一前提。主机不能有公网 IP，不能做端口映射，不能放在 DMZ。
> 需要从内网以外访问时，先完成下文的「加固清单」。

## 架构

```
 业务服务器 ×N  (Fluent Bit forwarder: tail + DB 书签 + 磁盘缓冲 + host 字段)
        │  forward 协议 → 10.0.0.50:24224     （防火墙：仅 FORWARDER_IPS）
        │
 内网浏览器 ──── http://10.0.0.50:5601 ───┐    （防火墙：仅 10.0.0.0/24）
        │                                 │
        ▼                                 ▼
 ┌──────────────────── 单机 10.0.0.50 ───────────────────────┐
 │  Fluent Bit 汇聚端 :24224  ── 指标 127.0.0.1:2020          │
 │        │ 磁盘缓冲 /data/opensearch/fluent-bit/buffer       │
 │        ▼ http://127.0.0.1:9200（不走网络）                  │
 │  OpenSearch :9200          （防火墙：仅 127.0.0.1）         │
 │        ▲ ingest pipeline: grok → trace_id/level/msg         │
 │        │ ISM: 日索引 app-logs-YYYY.MM.DD，7 天后删除          │
 │  Dashboards :5601 ──────── http://127.0.0.1:9200            │
 └───────────────────────────┬──────────────────────────────────┘
                             ▼
       /data/opensearch/{data, logs, dashboards, fluent-bit/buffer}
```

| 端口 | 进程 | 谁能访问 |
|---|---|---|
| 9200 | OpenSearch HTTP | 仅本机 |
| 9300 | OpenSearch 传输层 | 只绑定 127.0.0.1 |
| 5601 | Dashboards | 办公网段 `OFFICE_CIDR` |
| 24224 | Fluent Bit forward 输入 | 业务服务器 `FORWARDER_IPS` |
| 2020 | Fluent Bit 指标 | 只绑定 127.0.0.1 |

## 目录

`INSTALL_ROOT` 默认就是 `install.sh` 所在目录：整个文件夹放在哪，就装在哪（下文以服务器上的 `/opt/logstack` 为例）。
服务用户 `logstack` 必须能进入这个目录，所以不要放在 `/root` 或某个用户的 home 下，install.sh 会检查并拒绝。想装到别处：`INSTALL_ROOT=/xxx sudo -E ./install.sh`。

```
/opt/logstack/            ← INSTALL_ROOT（= 本目录）
├── install.sh            一键安装（所有版本号、路径、IP 都在它顶部的 VARS 段）
├── lib/common.sh         公共函数，读取 install.sh 的 VARS
├── packages/             离线包放这里（你自己放）
├── config/               配置模板（@XXX@ 占位符由 install.sh 渲染）
│   ├── opensearch.yml  jvm.options  opensearch_dashboards.yml
│   ├── fluent-bit.yaml  parsers.yaml   Fluent Bit 用 YAML 格式
│   └── forwarder-example.yaml   业务服务器端样例
├── bootstrap/01..04-*.sh  模板 / ISM / pipeline / 索引模式
├── fluent-bit-manual/    手动 nohup 启动用的 Fluent Bit 配置：aggregator.yaml / forwarder.yaml（见 MANUAL.md 第 4 节）
├── systemd/*.service
├── macos/                macOS 本地验证（brew services + LaunchAgent）
├── optional/nginx-basic-auth.conf
├── firewall.sh  healthcheck.sh
└── README.md
```

安装后在本目录生成：`opensearch -> opensearch-<ver>`、`opensearch-dashboards -> opensearch-dashboards-<ver>`，Fluent Bit 实际使用的配置在 `fluent-bit/`（这几项都在 `.gitignore` 里）。**改配置时只改 `config/` 下的模板**，然后执行 `install.sh --config-only` 重新渲染。

## 离线包准备

需要三个包。架构必须和服务器一致，先在服务器上执行 `uname -m` 确认：`x86_64` 对应 `x64`，`aarch64` 对应 `arm64`。

| 包 | 文件名 | 说明 |
|---|---|---|
| OpenSearch | `opensearch-3.10.0-linux-<x64\|arm64>.tar.gz` | 自带 JDK 25 |
| Dashboards | `opensearch-dashboards-3.10.0-linux-<x64\|arm64>.tar.gz` | 自带 Node，版本必须与 OpenSearch 完全相同 |
| Fluent Bit | `fluent-bit-5.2.0-1.<x86_64\|aarch64>.rpm` | 从 `https://packages.fluentbit.io/centos/<8\|9>/` 下载，RHEL 大版本和架构都要对应。版本号同步写到 `install.sh` 的 `FLUENTBIT_VERSION` |

**常见的下错包**（3.10.0 实测）：
- `fluent-bit-browser-experimental-*.tar.gz` 是 Fluent Bit 的**浏览器 WASM SDK**：Emscripten 编译，只能在网页里跑，需要 HTTPS 和 COOP/COEP。它**不能**当服务器上的汇聚端用，安装时用不到。服务器上要的是上表里的 RPM。
- 包名带 `linux-arm64` 的只能装在 ARM 服务器上。大多数 RHEL 服务器是 x86_64，需要的是 `linux-x64`。架构不符时，`install.sh` 会在解压前或解压后报错并停止，不会装一个起不来的版本。
- Linux 的 tar 包不能在 macOS 上运行。macOS 本地验证用 brew，见下文。
- 在 macOS 上解压出来的 `opensearch-3.10.0/` 等目录**不要拷到服务器**：拷过去以后 `install.sh` 会直接使用这个已解压的目录，不再从 tar 包解压。架构不符时会被拦下，但仍然建议只拷 tar 包，在服务器上解压。

3.10.0 包里和本方案相关的内容（已核对）：
- `opensearch-security` 插件在包里，因此 `plugins.security.disabled: true` 有效。注意 security 插件未安装时这个设置会让节点起不来。
- `opensearch-performance-analyzer` 在包里，但**包里缺它的配置目录** `config/opensearch-performance-analyzer/`，启动时报 `FileNotFoundException ... plugin-stats-metadata`。日志平台用不到它，`install.sh` 会移除这个插件，所以没有 9600 端口。
- 发行版自带的 `jvm.options` 含 `-javaagent:agent/opensearch-agent.jar`，是**相对路径**。systemd 单元的 `WorkingDirectory` 必须是 OpenSearch 目录，现有单元已经满足。
- 发行版 jvm.options 里的 GC 日志写到安装目录，由 `install.sh` 注释掉，堆和 GC 日志都由 `jvm.options.d/logstack.options` 设置。
- **不要用包里的 `opensearch-tar-install.sh`**：它会运行 security 的演示配置，要求设置 `OPENSEARCH_INITIAL_ADMIN_PASSWORD`。本方案由 systemd 直接启动 `bin/opensearch`。
- Dashboards 包里带的 `opensearch_dashboards.yml` 是开启 security 的那一套（https、kibanaserver 账号、多租户），会被 `config/opensearch_dashboards.yml` 整个覆盖。`securityDashboards` 插件由 `install.sh` 移除。`uiSettings.overrides`、`path.data` 以及三个显示预设的 key 在 3.10.0 中均有效。

## 安装步骤（RHEL 8 / 9）

```bash
# 0. 在 macOS 上：拷脚本和离线包（只拷 tar.gz 和 rpm，不拷解压目录，也不拷 fluent-bit-browser-experimental）
rsync -av --exclude '*.tar.gz' --exclude 'opensearch-3.10.0/' --exclude 'opensearch-dashboards-3.10.0/' \
      --exclude 'fluent-bit-browser-experimental/' --exclude 'fluent-bit/' --exclude 'fluent-bit-[0-9]*/' --exclude 'fluent-bit-pkg/' --exclude '*.pkg' --exclude '*.rpm' --exclude '.DS_Store' \
      opensearch/ root@10.0.0.50:/opt/logstack/
ssh root@10.0.0.50 mkdir -p /opt/logstack/packages
scp opensearch/opensearch-3.10.0-linux-x64.tar.gz \
    opensearch/opensearch-dashboards-3.10.0-linux-x64.tar.gz \
    opensearch/fluent-bit-5.2.0-1.x86_64.rpm \
    root@10.0.0.50:/opt/logstack/packages/
#   （有 .sha512 校验文件的话一起拷过去，install.sh 会自动校验。包直接放在 /opt/logstack/ 下也能找到）

# 1. 在服务器上：检查 / 修改 install.sh 顶部 VARS
#    必改：FORWARDER_IPS（业务服务器 IP）；核对：FLUENTBIT_VERSION 与 RPM 一致、FLB_BUFFER_LIMIT
vi /opt/logstack/install.sh

# 2. 一键安装：系统参数 → 解压 → 装 RPM → 渲染配置 → systemd → 启动 → bootstrap 01-04
sudo /opt/logstack/install.sh

# 3. 防火墙
sudo /opt/logstack/firewall.sh

# 4. 体检
/opt/logstack/healthcheck.sh

# 5. 业务服务器：按 config/forwarder-example.yaml 配置 Fluent Bit（连同 parsers.yaml，顶部注释里有 systemd drop-in）
```

`install.sh` 可以重复执行：已解压的版本不重复解压，配置和单元文件每次覆盖成模板渲染的结果，bootstrap 都是覆盖写。

安装过程中会自动做三项检查，任何一项不通过都会停止：
- **架构检查**：读取 tar 包或已解压目录的 `manifest.yml`，和本机架构比对。
- **Fluent Bit 配置检查**：渲染完配置后执行 `fluent-bit -c … --dry-run`。Fluent Bit 大版本更新时如果改了配置写法，会在这一步报出来，而不是启动后反复重启。
- **就绪检查**：按 OpenSearch → bootstrap 01-03 → Dashboards → 04 → Fluent Bit 的顺序启动，每一步都等就绪后再继续。

### 首次启动时可能遇到的问题

| 现象 | 原因与处理 |
|---|---|
| `journalctl -u opensearch` 里出现 `Exec format error` | tar 包架构与服务器不符，换 `linux-x64` / `linux-arm64` 中正确的那个 |
| `unknown setting [plugins.security.disabled]` | security 插件被删掉了。3.10.0 官方包里自带这个插件；如果你自己删过，把这一行也从 `config/opensearch.yml` 里删掉 |
| `Unable to lock JVM Memory` | 单元里没有生效 `LimitMEMLOCK=infinity`，执行 `systemctl daemon-reload` 后重启 |
| `max virtual memory areas vm.max_map_count [65530] is too low` | 执行 `sysctl -p /etc/sysctl.d/99-logstack.conf` |
| Dashboards 报 `securityDashboards` 相关错误后退出 | 插件没删干净：`runuser -u logstack -- /opt/logstack/opensearch-dashboards/bin/opensearch-dashboards-plugin remove securityDashboards` |
| Dashboards 提示版本不兼容 | OpenSearch 和 Dashboards 的版本号不一致，两个包必须都是 3.10.0 |
| 在 `fluent-bit --dry-run` 这一步报错 | 按报错修改 `config/fluent-bit.yaml`，然后执行 `install.sh --config-only` |

### macOS 本地验证

```bash
cd opensearch && ./install.sh        # 自动转到 macos/install-macos.sh
./healthcheck.sh
open http://127.0.0.1:5601
```

本地和服务器的差异：
- 用 brew 安装，**无法锁定版本**，版本与服务器不同时会给出警告。目录里下载的 Linux tar 包在 macOS 上跑不起来，本地验证不使用它们。
- 所有服务只监听 127.0.0.1，不锁内存，默认堆 2g（`MAC_HEAP_GB=4 ./install.sh` 可调）。
- 程序配置渲染到本目录的 `fluent-bit/`，数据放在 `~/logstack/data`。
- OpenSearch 和 Dashboards 用 `brew services` 管理，Fluent Bit 用 `~/Library/LaunchAgents/com.logstack.fluent-bit.plist`。

## 启停命令

启动顺序：OpenSearch → Dashboards → Fluent Bit。停止反过来。

```bash
# 启动
sudo systemctl start opensearch
sudo systemctl start opensearch-dashboards
sudo systemctl start fluent-bit

# 停止（先停 Fluent Bit：停掉以后它收到的数据都在磁盘缓冲里，不会丢）
sudo systemctl stop fluent-bit opensearch-dashboards opensearch

# 状态 / 日志
systemctl status opensearch opensearch-dashboards fluent-bit
journalctl -u opensearch -f            # OpenSearch 的详细日志另在 /data/opensearch/logs/logstack.log
journalctl -u fluent-bit -f

# 改了 config/ 以后
sudo /opt/logstack/install.sh --config-only && sudo systemctl restart <服务>
```

macOS：

```bash
brew services start|stop opensearch
brew services start|stop opensearch-dashboards
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.logstack.fluent-bit.plist   # 启动
launchctl bootout  gui/$(id -u)/com.logstack.fluent-bit                                # 停止
```

## 日志格式与字段

| 字段 | 类型 | 来源 |
|---|---|---|
| `@timestamp` | date | pipeline 从日志行解析出的时间（按 Asia/Singapore 解释）；解析失败时保留 Fluent Bit 读取时间 |
| `ingested_at` | date | 入库时间（体检用它判断"最近 5 分钟有没有写入"） |
| `trace_id` | keyword + lowercase normalizer | grok，查询时不区分大小写 |
| `level` | keyword | grok，统一转大写 |
| `index` | keyword | forwarder 的 record_modifier，环境 / 数据集名（如 `sg-sit`），对应 Splunk 的 `index=` |
| `host` | keyword | forwarder 的 record_modifier，取启动时的 `HOSTNAME` 环境变量 |
| `source` | keyword | forwarder tail 的 `path_key`，日志文件完整路径 |
| `offset` | long | forwarder tail 的 `offset_key`，这一条在文件里的字节位置；Discover 上下文视图（≈ Splunk Show Source）里同一毫秒的日志按它排序 |
| `show_source` | keyword（不索引） | pipeline 生成的上下文视图链接，带 `source` + `host` 过滤条件；索引模式把它显示成 **Show Source** 链接（≈ Splunk Show Source） |
| `app` | keyword | grok，日志行里的应用名（logback `[app,trace,span]` / Spring Boot `--- [app]`） |
| `thread` / `logger` | keyword | grok |
| `log` | text | 原始整行，保留，全文搜索用 |
| `msg` | text | grok 解析出的消息部分 |
| `parse_error` | keyword | 解析失败原因；只有失败时才有这个字段 |

pipeline 能识别五种行格式，前三种就是 `creed-ai-lab/logs` 里实际出现的（不符合时改 `bootstrap/03-ingest-pipeline.sh` 里的 `patterns`）：

```
20-11-2026 09:24:02.299  INFO [creed-resource-veracode,6affa131...,c19c3a42...] [http-nio-18097-exec-4] creed.tracker.http.outbound   [82] : msg
20-11-2026 00:03:08.324  INFO [http-nio-8600-exec-2][,] c.creed.parent.service.DictLoadService  [41] : msg
2026-10-17T00:50:05.534+08:00  INFO 84164 --- [creed-author-server] [main] [traceId-spanId] c.c.auth.CreedAuthorServerApplication : msg
2026-11-20 10:15:30.123 INFO [main] [trace_id=4bf92f3577b34da6a3ce929d0e0e4736] com.example.OrderService - created order 42
2026-11-20 10:15:30,123 ERROR something failed traceId=4bf92f35-77b3-4da6-a3ce-929d0e0e4736
```

最后一种格式里，`trace_id=` / `traceId:` 出现在行内任何位置都能提取出来。

### 调试解析：`_simulate`

```bash
/opt/logstack/bootstrap/03-ingest-pipeline.sh --simulate

# 或者手写：
curl -s -XPOST 'http://127.0.0.1:9200/_ingest/pipeline/app-logs-pipeline/_simulate?verbose=true&pretty' \
  -H 'Content-Type: application/json' -d '{
  "docs": [ { "_source": { "log": "2026-11-20 10:15:30.123 INFO [main] [trace_id=abc123def4567890] com.x.Y - hello" } } ]
}'
```

`verbose=true` 会逐个 processor 显示结果，可以看到在哪一步失败。

### 找出没解析上的日志

解析失败的日志不会丢弃，照常入库，`log` 原文还在，另外多一个 `parse_error` 字段。

- Discover（DQL）：`parse_error:*`
- 按原因分组：在 Visualize 里对 `parse_error` 做 Terms 聚合；或者用 curl：

```bash
curl -s 'http://127.0.0.1:9200/app-logs-*/_search?size=0&pretty' -H 'Content-Type: application/json' -d '{
  "query": { "exists": { "field": "parse_error" } },
  "aggs": { "reasons": { "terms": { "field": "parse_error", "size": 10 } },
            "samples": { "top_hits": { "size": 3, "_source": ["log", "host", "source"] } } }
}'
```

拿 `samples` 里的原文去跑上面的 `_simulate`，改好 pattern 后重跑 `03-ingest-pipeline.sh`。修改只对之后新写入的数据生效；已经入库的失败行可以按新 pipeline 重新解析一遍：

```bash
curl -s -XPOST 'http://127.0.0.1:9200/app-logs-*/_update_by_query?pipeline=app-logs-pipeline&conflicts=proceed&pretty' \
  -H 'Content-Type: application/json' -d '{
  "query": { "exists": { "field": "parse_error" } },
  "script": { "source": "ctx._source.remove(\"parse_error\")" }
}'
```

## 按 trace ID 查询

1. **Discover 里直接输入字符串**：`4bf92f3577b34da6a3ce929d0e0e4736`
   这是全字段搜索，会匹配到 `trace_id`（精确匹配），也会匹配到 `log` 原文里出现这个 ID 的行，比如没解析出 trace_id 的行。ID 里带 `-` 时加引号：`"4bf92f35-77b3-4da6-a3ce-929d0e0e4736"`。
2. **字段精确查询**：`trace_id:"4bf92f3577b34da6a3ce929d0e0e4736"`
   走 keyword 字段，速度最快。lowercase normalizer 使大小写都能命中。时间默认按正序排，从上往下读就是调用顺序。
3. **curl**：

```bash
curl -s 'http://127.0.0.1:9200/app-logs-*/_search?pretty' -H 'Content-Type: application/json' -d '{
  "size": 500,
  "sort": [ { "@timestamp": "asc" } ],
  "_source": ["@timestamp", "host", "level", "msg", "source"],
  "query": { "term": { "trace_id": "4BF92F3577B34DA6A3CE929D0E0E4736" } }
}'
```

## 生命周期（ISM）

- Fluent Bit 按天写入 `app-logs-YYYY.MM.DD`（`Logstash_Format On`）。日期按 UTC 计算，对应新加坡时间每天 08:00 切换到新索引。
- 策略 `app-logs-policy` 自动挂到新建的 `app-logs-*` 上：处于 hot 状态，索引创建满 7 天后删除。实际能查到 7 到 8 天的数据。ISM 每 5 分钟左右检查一次。
- **为什么没有"满 7 天或 10GB 时 rollover"**：ISM 的 rollover 动作必须配合写别名使用，而 `Logstash_Format` 写的是带日期的具体索引名，没有写别名。在按天索引上配置 rollover 会让每个索引的 ISM 卡在 failed 状态，后面的 delete 也就永远不会执行，磁盘最终被写满。按天索引本身已经相当于"每天 rollover 一次"。
- 如果单日数据会超过 10GB，改用别名模式：
  - Fluent Bit 设 `Logstash_Format Off`，`Index app-logs-write`；
  - 先建 `app-logs-000001`，并把它设为别名 `app-logs-write` 的写索引；
  - 模板里加 `plugins.index_state_management.rollover_alias: app-logs-write`；
  - 策略 hot 状态加 `{"rollover": {"min_index_age": "7d", "min_size": "10gb"}}`，删除条件改为 rollover 之后 7 天。

  这种方案需要另外改脚本，目前未实现。

查看状态：

```bash
curl -s 'http://127.0.0.1:9200/_plugins/_ism/explain/app-logs-*?pretty' | grep -E '"index"|policy_id|"state"|failed'
```

## 常见故障排查

### 查不到数据：按这四步顺序排查

1. **业务服务器到汇聚端**：在业务服务器上执行 `curl -s 127.0.0.1:2020/api/v1/metrics`，看 `output.forward.0` 的 `proc_records` 是否在增长、`errors` / `retries` 是否也在增长。网络问题用 `nc -vz 10.0.0.50 24224` 测；不通时检查 `firewall.sh` 的 `FORWARDER_IPS` 里有没有这台机器。另外确认 tail 的 `Path` 写对了，以及 DB 书签没有停在文件末尾（`Read_from_Head false` 时只读新写入的内容）。
2. **汇聚端到 OpenSearch**：在服务器上执行 `healthcheck.sh`，看第 6 项；用 `journalctl -u fluent-bit | grep -iE 'error|warn'` 看报错。被 OpenSearch 拒收的文档会因为 `Trace_Error On` 打印出具体原因，比如 mapping 冲突。
3. **索引有没有数据**：`curl -s '127.0.0.1:9200/_cat/indices/app-logs-*?v&s=index'` 看 `docs.count`。有索引但文档数为 0，多半是 pipeline 报错：先跑 `03-ingest-pipeline.sh --simulate`。
4. **Dashboards 时间范围**：`@timestamp` 是日志自身的时间，不是入库时间。补发的历史日志、时区不对的日志可能落在当前时间范围之外。先把范围放宽到"最近 30 天"，再检查日志里的时间是不是新加坡时间：pipeline 按 Asia/Singapore 解释不带时区的时间。

### 集群 yellow

单节点上，任何带副本的索引都会让集群变黄。

```bash
curl -s '127.0.0.1:9200/_cat/shards?h=index,shard,prirep,state,unassigned.reason' | grep UNASSIGNED
curl -s -XPUT '127.0.0.1:9200/_all/_settings?expand_wildcards=all' -H 'Content-Type: application/json' \
  -d '{"index.number_of_replicas":0}'
```

`01-index-template.sh` 里的 `logstack-default-replicas` 兜底模板会让之后新建的索引副本数都为 0。如果 yellow 反复出现，检查是不是有人手动建了索引时指定了副本。

### 磁盘水位

| 已用 | 水位线 | 后果 |
|---|---|---|
| 85% | low | 不再向本节点分配新分片。新的日索引建不出来，当天的写入失败 |
| 90% | high | 尝试迁走分片。单节点上无处可迁 |
| 95% | flood_stage | 所有索引被置为 `read_only_allow_delete`，写入全部失败，Fluent Bit 开始在磁盘上堆积缓冲 |

处理方法：
1. 删除最老的索引腾出空间：`curl -XDELETE 127.0.0.1:9200/app-logs-2026.11.01`。必须写完整索引名，`action.destructive_requires_name` 不允许用通配符。
2. 或者缩短 `RETENTION_DAYS` 后重跑 `02-ism-policy.sh`。
3. 磁盘降到水位线以下后，新版 OpenSearch 会自动解除只读。如果没有自动解除，手动执行：

```bash
curl -XPUT '127.0.0.1:9200/app-logs-*/_settings' -H 'Content-Type: application/json' \
  -d '{"index.blocks.read_only_allow_delete": null}'
```

注意 Fluent Bit 的缓冲（`FLB_BUFFER_LIMIT`）和 OpenSearch 的数据在同一块盘上。缓冲上限要算进磁盘预算。

### Fluent Bit 不发数据

- `systemctl status fluent-bit`、`journalctl -u fluent-bit -n 200`。
- 指标：`curl -s 127.0.0.1:2020/api/v1/metrics/prometheus | grep -E 'output_(retries|errors|dropped)'`。
- 磁盘缓冲：`curl -s 127.0.0.1:2020/api/v1/storage`，同时看 `du -sh /data/opensearch/fluent-bit/buffer`。缓冲持续增长说明 OpenSearch 写不进去，回到"磁盘水位"和"集群 yellow"两节排查。
- `--dry-run` 能查出写错的配置项名，但**查不出写错的解析器名**（例如 `multiline.parser` 指向不存在的解析器），这类错误要到启动时才暴露。改了 `parsers.yaml` 或解析器引用后，用 `journalctl -u fluent-bit -n 50` 确认启动时没有报错。
- 业务服务器上 `host` 字段为空：forwarder 的 systemd 里没有 `HOSTNAME` 环境变量，按 `forwarder-example.yaml` 顶部的说明加一个 drop-in。
- SELinux（RHEL）：`ausearch -m avc -ts recent | grep fluent`。默认安装下 Fluent Bit 以 `unconfined_service_t` 运行，一般不受影响。

## 加固清单：启用 security 插件 + TLS

按以下顺序操作，每一步做完都执行一次 `healthcheck.sh`（体检脚本需要同步改成带证书和账号的 curl）。

1. **证书**：用内部 CA 签发节点证书（SAN 包含 `10.0.0.50` 和主机名）和管理员证书，放到 `opensearch/config/`，属主为 logstack，权限 0600。
2. **opensearch.yml**：删掉 `plugins.security.disabled: true`，加上：

   ```yaml
   plugins.security.ssl.http.enabled: true
   plugins.security.ssl.http.pemcert_filepath: node.pem
   plugins.security.ssl.http.pemkey_filepath: node-key.pem
   plugins.security.ssl.http.pemtrustedcas_filepath: root-ca.pem
   plugins.security.ssl.transport.pemcert_filepath: node.pem
   plugins.security.ssl.transport.pemkey_filepath: node-key.pem
   plugins.security.ssl.transport.pemtrustedcas_filepath: root-ca.pem
   plugins.security.authcz.admin_dn: ["CN=admin,OU=logstack,O=example"]
   plugins.security.allow_default_init_securityindex: true
   ```
3. **初始化安全索引**：先在 `opensearch-security/internal_users.yml` 里设置 admin、dashboards、fluentbit 三个用户（哈希用 `plugins/opensearch-security/tools/hash.sh` 生成），再执行 `securityadmin.sh`。
4. **角色最小化**：fluentbit 用户只给 `app-logs-*` 的 `create_index` 和 `write` 权限；查日志的人用只读角色。
5. **Dashboards**：
   - 重新安装 `securityDashboards` 插件（版本必须和 OpenSearch 一致）；
   - `opensearch.hosts` 改成 `https://127.0.0.1:9200`；
   - 配置 `opensearch.username`/`opensearch.password`（dashboards 用户）和 `opensearch.ssl.certificateAuthorities`；
   - 配置 `server.ssl.enabled: true` 及证书。
6. **Fluent Bit 汇聚端输出**：加上 `tls On`、`tls.verify On`、`tls.ca_file`、`HTTP_User fluentbit`、`HTTP_Passwd`。密码通过 systemd 的 `Environment=` 注入，用 `${FLB_OS_PASSWD}` 引用，不要写死在配置文件里。
7. **forward 链路**：汇聚端 forward 输入配置 `tls On` 和 `Shared_Key`，所有 forwarder 用相同的 `Shared_Key` 加上 `tls On`。
8. **收紧监听**：`network.host: 127.0.0.1`。Dashboards 仍然直接对外，或者放到 `optional/nginx-basic-auth.conf` 后面。
9. 即便完成以上加固，也不建议把 9200 暴露到办公网以外。

## 不在本方案范围内

- 多节点或高可用：单机故障期间数据只能缓冲在各 forwarder 上（`storage.total_limit_size 2G`）。
- 快照备份：需要的话配置 `path.repo`，用 snapshot API 定期备份到 NFS 或对象存储。
