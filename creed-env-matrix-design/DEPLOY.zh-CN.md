# Env Matrix Viewer —— 编译并在 Node 中运行

[English](./DEPLOY.md)

本文说明如何把本项目变成**一个 Node 进程**：托管 UI、负责 Splunk 会话代理，并把其余请求反向代理到
`creed-resource-env-matrix`。本地开发请看 [README.zh-CN.md](./README.zh-CN.md) 第 1 节。

```
浏览器 ──► BFF（node server/bff.js，:3002）
             ├─ /api/env-matrix/splunk/*  在此处理 ── Splunk Web（HTTPS 表单登录）
             │                                    └── 审计：内存（默认）| PostgreSQL | MySQL（SPLUNK_AUDIT_STORE）
             ├─ /api/*                    反向代理 ── creed-resource-env-matrix（:18095）
             └─ 其余路径                  dist/（前端路由回落到 index.html）
```

编译产物是静态文件，真正**运行**的只有 `server/bff.js`，它唯一的运行时依赖是 `pg`。React、antd、
G6 都已打包进 `dist/`，服务器上不需要。

---

## 1. 前置条件

| | 构建机 | 运行主机 |
|---|---|---|
| Node | ≥ 22.9（需要 `--env-file-if-exists`）；已在 24.16 上验证 | 同左 |
| npm | 随 Node 安装 | 同左 |
| 网络 | npm 仓库 | PostgreSQL、`creed-resource-env-matrix`、Splunk Web |
| openssl | 可选 —— 仅 `test:server` 中的一个用例需要 | —— |

**只有 `SPLUNK_AUDIT_STORE=pg` 或 `mysql` 时才需要数据库。** 默认审计存在内存中（保留最新 500 条，重启即丢失），
此时完全不涉及数据库。使用 pg 时，数据库 `env_matrix` 必须已存在，且数据库用户需要该库的 `CREATE`
权限；没有的话请 DBA 先建好 schema（见第 5 节）。

## 2. 编译

在 `creed-env-matrix-design/` 下执行：

```bash
npm ci                    # 严格按 package-lock.json 安装
npm run typecheck         # tsc -b —— 不要用 `tsc --noEmit`，在本项目里它什么都不检查
npm run test:server       # Splunk 会话代理的测试（node:test，不联网、不连数据库）
npm run build             # tsc -b && vite build  ->  dist/
```

`dist/` 就是完整的 UI：`index.html` + 带哈希指纹的 `assets/`。`vite.config.ts` 开启了
`sourcemap: true`，所以 `dist/` 里还有 `.map` 文件（占 18 MB 中的大部分），会暴露 TypeScript 源码。
如有顾虑可删除：`find dist -name '*.map' -delete`。

## 3. 打包

```bash
npm run package:bff                                   # -> release/env-matrix-bff/
RELEASE_DIR=/some/path npm run package:bff            # 或输出到任意目录
```

`scripts/package-bff.mjs` 只复制运行所需的文件，只安装 `pg`：

```
release/env-matrix-bff/
├── dist/                  编译好的 UI
├── server/bff.js
├── server/splunk/         会话代理代码（不含 *.test.js）
├── package.json           "type": "module"，dependencies: { pg }，scripts.start
├── package-lock.json
├── node_modules/          只有 pg（约 1 MB）
└── .env.server.example    全部配置项及说明
```

**不想用脚本也可以手工打包。** 两个容易漏的点：`"type": "module"`（服务端是 `.js` 后缀的 ES 模块
—— Node 22.9–22.11 没有它会直接报错，更新的版本会告警后重新解析），以及 `dist/` 必须与 `server/`
同级（除非用 `BFF_STATIC_DIR` 指定，BFF 会在 `bff.js` 的 `../dist` 查找）：

```bash
R=release/env-matrix-bff && mkdir -p $R/server
cp -R dist $R/ && cp server/bff.js $R/server/ && cp -R server/splunk $R/server/ && rm $R/server/splunk/*.test.js
cp .env.server.example $R/
cat > $R/package.json <<'EOF'
{ "name": "env-matrix-bff", "private": true, "type": "module",
  "engines": { "node": ">=22.9" },
  "scripts": { "start": "node --env-file-if-exists=.env.server.local server/bff.js" },
  "dependencies": { "pg": "8.23.1" } }
EOF
(cd $R && npm install --omit=dev)
```

`pg` 是纯 JavaScript 实现，所以在 macOS 上打的包可以直接在 Linux 上运行。打成压缩包分发：

```bash
tar -C release -czf env-matrix-bff.tgz env-matrix-bff
# 在目标主机上
tar -xzf env-matrix-bff.tgz -C /opt
```

## 4. 配置

配置来源的优先级：真实环境变量 → 发布目录下的 `.env.server.local`（由 `npm start` 加载）→ 内置
默认值。`.env.server.example` 列出了全部变量。任何密钥都可以改用 `NAME_FILE=/path/to/file` 提供
（Docker/Kubernetes secret、Vault agent 输出文件），同时设置时文件优先。

```bash
cd /opt/env-matrix-bff
cp .env.server.example .env.server.local && chmod 600 .env.server.local
```

**生产环境必须设置以下变量 —— 默认值只适合本机开发：**

| 变量 | 默认值 | 生产环境 |
|---|---|---|
| `ENV_MATRIX_API_TARGET` | `https://localhost:18095` | 后端的真实地址 |
| `ENV_MATRIX_API_INSECURE` | `true` | 后端证书正规时设为 `false`，并配置 `ENV_MATRIX_API_CA_FILE` |
| `ENV_MATRIX_TOTP_SECRET`（`_FILE`） | `JBSWY3DPEHPK3PXP` —— **演示用** | 新生成的 Base32 密钥 |
| `ENV_MATRIX_TOTP_EXPOSE_CODE` | `true` —— 页面直接显示验证码 | 若 OTP 要真正起到门禁作用，设为 `false` |
| `SPLUNK_ENABLED` | `false` —— 返回伪造 cookie | `true` |
| `SPLUNK_LOGIN_URL` | `splunk.example.invalid` | `https://<splunk>:8000/en-US/account/login` —— 未设置 `SPLUNK_TARGETS` 时的唯一目标 |
| `SPLUNK_USERNAME` / `SPLUNK_PASSWORD`（`_FILE`） | `admin` / `admin` | 该唯一目标的共享账号 |
| `SPLUNK_TARGETS` | 未设置 —— 只有一个目标 `default` | 页面下拉框中的多个实例，如 `SIT,UAT` |
| `SPLUNK_TARGET_<ID>_LOGIN_URL` / `_USERNAME` / `_PASSWORD`（`_FILE`）/ `_LABEL` | — | 每个目标各自配置；**不会回退**到单目标变量 |
| `SPLUNK_SESSION_COOKIE` / `SPLUNK_SCRIPT_COOKIE_NAME` / `SPLUNK_SCRIPT_COOKIE_PATH` | `splunkd_8000` / `splunkd_8089` / `/` | 所有目标的默认值；`splunkd_<端口>` 跟随 Splunk Web 的端口 |
| `SPLUNK_TARGET_<ID>_SESSION_COOKIE` / `_SCRIPT_COOKIE_NAME` / `_SCRIPT_COOKIE_PATH` | 上面的全局值 | 每个目标各自配置；页面上仍可为单次登录修改这两个 cookie 名 |
| `SPLUNK_TARGET_<ID>_TUNNEL`（单目标时为 `SPLUNK_TUNNEL`） | 未设置 | 通往该 Splunk 的 TCP 转发 `host:port`（如 `<server-host>:3000` → `<uat-host>:3000`）；登录时连接到这里，但 `Host`/SNI 仍为登录地址的主机。页面显示「通过 tunnel」开关 |
| `SPLUNK_TARGET_<ID>_TUNNEL_DEFAULT` | `false` —— 开关默认关闭（直连） | `true` 时开关默认开启（走 tunnel） |
| `SPLUNK_DEFAULT_TARGET` | `SPLUNK_TARGETS` 中的第一个 | 下拉框的初始选项 |
| `SPLUNK_TLS_INSECURE` | `true` —— **不校验**证书 | 按需求；校验时设为 `false` + `SPLUNK_CA_FILE` |
| `SPLUNK_AUDIT_STORE` | `memory` —— 保留最新 500 条，**重启即丢失** | 需要保留审计时设为 `pg` 或 `mysql` |
| `SPLUNK_DB_URL`（pg / mysql） | `postgres://127.0.0.1:5432/env_matrix`；mysql 时为 `mysql://127.0.0.1:3306/env_matrix` | 真实主机；与所选数据库不符的 URL 启动时即报错 |
| `SPLUNK_DB_USER` / `SPLUNK_DB_PASSWORD`（`_FILE`，pg / mysql） | `artifactory` / `artifactory_pw` | 真实凭据 |
| `BFF_PORT` / `BFF_HOST` | `3002` / 所有网卡 | 在反向代理之后时设 `BFF_HOST=127.0.0.1` |

生成 TOTP 密钥：`node -e "const a='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';console.log([...require('crypto').randomBytes(20)].map(b=>a[b&31]).join(''))"`。

非法取值（非 Base32 的密钥、非法 schema 名、越界数字）会在**启动时**报错并指出变量名，而不是等到
第一个请求。

## 5. 准备数据库（`SPLUNK_AUDIT_STORE=pg` 或 `mysql`）

使用默认的内存审计时跳过本节。

**MySQL**（8.0+，或 MariaDB 10.5+）：建好数据库和一个能在其中建表的账号；BFF 首次启动时会创建
`splunk_audit`（InnoDB，utf8mb4）。时间一律以 UTC 存入 `datetime(3)`，与服务器时区无关。

```sql
create database env_matrix character set utf8mb4;
create user 'artifactory'@'%' identified by '…';
grant create, select, insert on env_matrix.* to 'artifactory'@'%';
```

**PostgreSQL**：如果数据库用户有建 schema 的权限，则无需任何操作：BFF 首次启动时会执行
`create schema if not exists splunk_broker`，建表和两个索引，并把旧表 `public.splunk_audit`
（Java 模块的 Flyway V6）中已有的记录复制一次。否则请用有权限的用户执行：

```sql
create schema splunk_broker authorization artifactory;   -- 其余由 BFF 完成
```

审计放在独立 schema 中是有意为之 —— 原因见 README 的「为什么用独立 schema」。

## 6. 启动与验证

```bash
cd /opt/env-matrix-bff
npm start
# 不经过 npm 的等价写法：
node --env-file-if-exists=.env.server.local server/bff.js
```

正常启动会先打印生效的配置（所有密钥均打码），然后输出：

```
[bff] listening on http://localhost:3002 — splunk=real, audit=pg, /api -> https://…:18095, static=/opt/env-matrix-bff/dist
```

`audit=memory`（默认）前会先打印一条告警，提示审计重启即丢失。`audit=pg` 时 PostgreSQL 连不上则
**拒绝启动**：会话代理绝不签发无法审计的会话。开启 Splunk 且
`SPLUNK_TLS_INSECURE=true` 时，还会打印一条告警说明证书未校验。

```bash
B=http://localhost:3002
curl -s -o /dev/null -w '%{http_code}\n' $B/                          # 200  UI
curl -s -o /dev/null -w '%{http_code}\n' $B/topology                  # 200  前端路由 -> index.html
curl -s $B/api/env-matrix/ping                                        # 代理到 Java 后端
curl -s $B/api/env-matrix/splunk/totp                                 # 会话代理：splunkMode、splunkConfigured
curl -s "$B/api/env-matrix/splunk/audit?limit=5"                      # 读审计存储（内存或 pg）
```

然后在浏览器打开 `/splunk` 申请一次会话。`SPLUNK_ENABLED=false` 时 cookie 为 `mock-…`；开启后若返回
502 `splunk_no_session_cookie`，通常是凭据错误（Splunk 返回 401）或缺少 `cval`（返回 200）。

用 Ctrl-C / `SIGTERM` 停止：服务停止接收连接并关闭数据库连接池。

## 7. 常驻运行

**只能运行一个实例。** 防重放和输错锁定的状态保存在进程内存里，两个实例（或 pm2 cluster 模式）
会各自接受同一个验证码一次。要横向扩展，必须先把这部分状态移到共享存储。

以下两个模板是为本文编写的，**未在本机实际运行过**，请按需调整路径和运行用户。

### systemd

```ini
# /etc/systemd/system/env-matrix-bff.service
[Unit]
Description=Env Matrix BFF
After=network-online.target
Wants=network-online.target

[Service]
User=envmatrix
WorkingDirectory=/opt/env-matrix-bff
ExecStart=/usr/bin/node --env-file-if-exists=.env.server.local server/bff.js
Restart=on-failure
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now env-matrix-bff
journalctl -u env-matrix-bff -f
```

`Restart=on-failure` 也能覆盖开机时「数据库还没起来」的情况。

### Docker

```dockerfile
# 构建上下文：release/env-matrix-bff
FROM node:24-alpine
WORKDIR /app
COPY . .
RUN rm -f .env.server.local
USER node
EXPOSE 3002
CMD ["node", "server/bff.js"]
```

配置用 `-e` / `--env-file` 传入，密钥用 `*_FILE` + 挂载的 secret。容器内的 `127.0.0.1` 指向容器
自己 —— `SPLUNK_DB_URL` 和 `ENV_MATRIX_API_TARGET` 要指向真实主机。

## 8. 放在反向代理之后（TLS）

BFF 只提供明文 HTTP。请在它前面终结 TLS，并让它只监听回环地址（`BFF_HOST=127.0.0.1`）：

```nginx
location / {
    proxy_pass http://127.0.0.1:3002;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

审计会记录 `X-Forwarded-For`，但不信任它。客户端地址和锁定计数都按 TCP 对端计算 —— 在代理之后，
对端就是代理本身，因此**所有用户共用一个锁定计数**（任何人输错 5 次，所有人都会被锁 1 分钟）。

## 9. 升级与回滚

1. 按第 2–3 节编译、打包新版本到新目录，例如 `/opt/env-matrix-bff-1.1.0`。
2. 把 `.env.server.local` 复制过去。
3. 让软链接指向新目录（`ln -sfn /opt/env-matrix-bff-1.1.0 /opt/env-matrix-bff`）并重启。

回滚就是把软链接改回去再重启。审计 schema 用的是 `create … if not exists`，版本之间可以随意切换。
重启会清空防重放记录和锁定计数。

## 10. 部署前检查清单

- [ ] `server/splunk/` 里没有打印 `splunk` 配置对象的调试 `console.log` —— 该对象包含 `password`。
      日志里只允许出现指纹和打码后的配置。
- [ ] `ENV_MATRIX_TOTP_SECRET` 不是演示值；`ENV_MATRIX_TOTP_EXPOSE_CODE` 是有意识的选择。
- [ ] `SPLUNK_TLS_INSECURE` 是有意识的选择（默认：不校验证书）。
- [ ] `.env.server.local` 权限为 `chmod 600`，且不在任何对外分享的压缩包里；优先使用 `*_FILE`。
- [ ] 如果源码不能公开，已从 `dist/` 删除 source map。
- [ ] `SPLUNK_AUDIT_STORE` 是有意识的选择：默认的 `memory` 每次重启都会丢失审计。
- [ ] 只运行了一个实例。

## 11. 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| `SyntaxError: Cannot use import statement outside a module`（Node 22.9–22.11），或 `MODULE_TYPELESS_PACKAGE_JSON` 告警（Node ≥ 22.12，会重新解析并继续运行） | `server/` 同级的 `package.json` 缺少 `"type": "module"` |
| `Cannot find package 'pg'` | 没有在发布目录执行 `npm install --omit=dev` |
| `SASL: … client password must be a string` | 数据库密码没有传到 `pg` —— 设置 `SPLUNK_DB_PASSWORD`（或 `_FILE`） |
| 启动失败：`ECONNREFUSED …:5432` / `permission denied for database` | `SPLUNK_AUDIT_STORE=pg` 且数据库不可达 / 没有 `CREATE` 权限 —— 见第 5 节 |
| 启动失败：`ECONNREFUSED …:3306` / `Access denied for user` / `Unknown database` | `SPLUNK_AUDIT_STORE=mysql` 且数据库不可达 / 凭据错误 / 数据库未创建 —— 见第 5 节 |
| `SPLUNK_DB_URL '…' does not fit SPLUNK_AUDIT_STORE=…` | `mysql` 配了 `postgres://` 的 URL，或反过来 |
| 重启后审计为空 | 默认 `SPLUNK_AUDIT_STORE=memory` —— 设为 `pg` 或 `mysql` 才会持久化 |
| `EADDRINUSE :::3002` | 已有一个 BFF 在运行；`lsof -nP -iTCP:3002 -sTCP:LISTEN` |
| 访问 `/` 返回 ``no build at … — run `npm run build` first`` | 缺少 `dist/`，或 `BFF_STATIC_DIR` 设错 |
| `/api/...` → `502 bad_gateway` | `ENV_MATRIX_API_TARGET` 不可达，或在 `ENV_MATRIX_API_INSECURE=false` 时证书被拒 |
| `/splunk/session` → `503 not_configured` | 开启了 Splunk 但缺 URL/用户名/密码 —— 验证码**未**被消耗 |
| `/splunk/session` → `502 splunk_io_error` | Splunk 不可达 / 超时 / TLS 被拒（`SPLUNK_TLS_INSECURE=false`） |
| `/splunk/session` → `502 splunk_no_session_cookie` | Splunk 返回 401 = 凭据错误；返回 200 = `cval` 被拒或 `SPLUNK_SESSION_COOKIE` 配错 |
| `/splunk/session` → `401 otp_replayed` | 该验证码已用过 —— 等下一个 |
| `/splunk/session` → `429` | 同一地址 60 秒内输错 5 次；在代理之后则是任何人（见第 8 节） |
| `.env.server.local` 没有生效 | 它是从**当前工作目录**读取的 —— 请在发布目录下启动 |
