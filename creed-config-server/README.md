# creed HTTPS / config / load-balancer setup

## 需求 (original requirements)

1. creed-config-server -> 使用 `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 启用 https
2. creed-resource/creed-resource-catalog 启用 https，从 creed-config-server 获取 ssl.bundle
3. creed-resource/creed-resource-order 启用 https，从 creed-config-server 获取 ssl.bundle
4. creed-gateway 启用 https，从 creed-config-server 获取 ssl.bundle
5. creed-gateway 使用 spring cloud loadbalancer，并加载 ssl.bundle 从 creed-resource/creed-resource-catalog & creed-resource/creed-resource-order 获取数据
6. creed-resource/creed-resource-order 和 creed-resource/creed-resource-catalog 中的 properties 从 creed-config-server 获取密码，该密码可以使用 public key 加密，获取的值 creed-config-server 自动解密

## Topology

| Service                | Port | Transport | Config source            |
|------------------------|------|-----------|--------------------------|
| creed-config-server    | 8443 | HTTPS     | native `classpath:/config-repo` |
| creed-resource-catalog | 8081 | HTTPS     | config-server (HTTPS)    |
| creed-resource-order   | 8082 | HTTPS     | config-server (HTTPS)    |
| creed-gateway          | 8080 | HTTPS     | config-server (HTTPS)    |
| creed-author-server    | 9000 | HTTP      | local (OAuth2 issuer, context-path `/auth-server`) |

## Architecture

```
                                        ┌──────────────────────────────────────┐
                                        │   creed-config-server   :8443 (HTTPS) │
                                        │   listener bundle: creed-pem-server   │
   ┌────────────────────────────┐      │   (WebServerFactoryCustomizer 编程式)  │
   │  开发 PKI / scripts          │ 生成  ├──────────────────────────────────────┤
   │  generate-certs.ps1         │─────▶│  config-repo/*.yml (native)           │
   │  ca / server / client / ts  │      │   ├ ssl.bundle.jks.*  (server/client) │
   │  config-encrypt.p12 (RSA)   │      │   ├ discovery.instances (lb 实例)      │
   └────────────────────────────┘      │   └ keystore-password: {cipher}…       │
                                        │  encrypt.key-store (RSA, alias=config-key)
                                        │   └─▶ 服务端自动解密 {cipher} → 明文     │
                                        └───────────────┬──────────────────────┘
                                                        │  ① 各应用启动时拉取配置
                          spring.config.import=configserver:https://…:8443
                          (客户端用 PKCS12 truststore 信任自签 CA —— 唯一本地TLS材料)
                                                        │
          ┌─────────────────────────────┬──────────────┴───────────────┐
          │ 下发 creed-jks-server/client │ 下发 bundle + 解密后的口令       │ 下发 bundle + lb 实例 + 路由
          ▼                             ▼                              ▼
┌───────────────────────┐   ┌───────────────────────┐    ┌─────────────────────────────────┐
│ creed-resource-catalog│   │ creed-resource-order   │    │      creed-gateway  :8080 (HTTPS) │
│ :8081 (HTTPS)         │   │ :8082 (HTTPS)          │    │ listener bundle: creed-jks-server │
│ listener: jks-server  │   │ listener: jks-server   │    ├─────────────────────────────────┤
│ OAuth2 Resource Server│   │ OAuth2 Resource Server │    │ ② lb:// 路由 (route filter)        │
└───────────▲───────────┘   └───────────▲────────────┘    │ ③ /api/aggregate (聚合)            │
            │                           │                 │    ReactiveLoadBalancer.Factory   │
            │   ④ Gateway 出站 HTTPS      │                 │    显式解析 lb://→https://host:port│
            │   client bundle: creed-jks-client (信任资源服务器证书)            │    resourceWebClient (Netty SSL) │
            └───────────────────────────┴─────────────────┤                                   │
                                                          └─────────────────▲────────────────┘
                                                                            │ ⑤ Bearer JWT
                          ┌─────────────────────────────────────────────────┴───────┐
                          │  creed-author-server  :9000  (context-path /auth-server)  │
                          │  issuer = http://127.0.0.1:9000/auth-server               │
                          │  /oauth2/token · /oauth2/jwks · /.well-known/openid-config│
                          └───────────────────────────────────────────────────────────┘
   说明：所有 Resource Server / Gateway 用 issuer-uri 拉 JWKS 校验 JWT；
        三者 issuer-uri 必须 == authorization server issuer（含 /auth-server 上下文）。
```

**关键链路**

1. 各应用以 HTTPS 从 config-server 拉取自身配置（含 SSL Bundle 定义、`{cipher}` 口令）。
2. Gateway 的 `lb://` 路由由 route filter 解析到具体实例。
3. 聚合接口在 controller 内用 `ReactiveLoadBalancer.Factory` 显式把 `lb://` 解析成 `https://host:port`（避免 lb scheme 漏到 Netty）。
4. Gateway 出站用 `creed-jks-client` bundle 信任资源服务器自签证书。
5. 资源服务器/Gateway 用 `issuer-uri` 向授权服务器拉 JWKS 校验 JWT。

## How each requirement is implemented

**(1) config-server HTTPS via customizer** — `com.creed.config.web.TomcatHttpsConfiguration`
implements `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` and sets the listener's
`Ssl` (bundle `creed-pem-server`) programmatically rather than via `server.ssl.*`. It is gated by
`creed.https.enabled` (default `true`; disabled in tests).

**(2)(3)(4) HTTPS from a config-server bundle** — each downstream app imports its config with
`spring.config.import=configserver:https://localhost:8443/config-server` and trusts the config
server's self-signed CA via `spring.cloud.config.tls.trust-store` (Spring Cloud Config 4.3 has no
SSL-bundle support on the client side, so a PKCS12 truststore is used for that one bootstrap hop).
The HTTPS *listener* of each app is then driven by `server.ssl.bundle: creed-jks-server`, whose
`spring.ssl.bundle.jks.*` definition is served **by the config server** (see
`config-repo/creed-*.yml`).

**(5) gateway load balancing + outbound SSL bundle** — two facets, both verified:
- **Routes**: `spring.cloud.gateway.server.webflux.routes` use `lb://catalog-resource` /
  `lb://order-resource`; outbound TLS trust comes from
  `spring.cloud.gateway.server.webflux.httpclient.ssl.ssl-bundle: creed-jks-client`.
- **Aggregator** (`/api/aggregate/summary`): `AggregateController` resolves each
  `lb://<service-id>/...` URL to a concrete instance URL itself, via
  `ReactiveLoadBalancer.Factory`, then calls it over `resourceWebClient` — a plain `WebClient`
  whose Reactor Netty connector trusts the resource servers through the `creed-jks-client` bundle
  (`GatewayWebClientConfiguration`).

  > **Why explicit resolution and not a load-balancer `ExchangeFilterFunction`?** With a custom
  > Netty SSL connector, the LB exchange filter did not rewrite the request before it reached the
  > connector, so the `lb` scheme leaked down to Reactor Netty
  > (`UriEndpointFactory: Invalid scheme [lb]`). Note also that a naive
  > `LoadBalancerUriTools.reconstructURI(instance, uri)` keeps the **original** scheme when the
  > `SimpleDiscoveryClient` instance carries none — so it would *still* yield `lb://`. The
  > controller therefore forces the transport scheme from `instance.isSecure()`
  > (`https` / `http`), exactly as the gateway's own route filter does.

  Discovery instances are static, served from `config-repo/creed-gateway.yml`:
  `spring.cloud.discovery.client.simple.instances.{catalog-resource,order-resource}[0].uri`.

**(6) encrypted password, auto-decrypted by config-server** — the resource servers' / gateway's
keystore password is stored in `config-repo/*.yml` as
`creed.ssl.keystore-password: '{cipher}...'`. The config server holds the RSA key
(`encrypt.key-store` -> `classpath:certs/config-encrypt.p12`, alias `config-key`) and decrypts
`{cipher}` values before serving them, so clients receive the plaintext (`changeit`) and the
`spring.ssl.bundle.jks.*.{keystore,truststore}.password: ${creed.ssl.keystore-password}`
references resolve and the keystores open.

## SSL Bundle 的优势

| 优势 | 具体体现（对应本项目） |
|------|----------------------|
| **集中声明、按名引用** | `spring.ssl.bundle.jks.creed-jks-server` 一处定义，`server.ssl.bundle: creed-jks-server`、出站 `httpclient.ssl.ssl-bundle: creed-jks-client` 按名引用，不再到处写 keystore 路径/口令。 |
| **入站/出站统一抽象** | 同一套 Bundle 机制既给 Tomcat/Netty 监听器做服务端 TLS，又给 `WebClient`/`RestClient` 做客户端信任，模型一致。 |
| **与编程式定制无缝结合** | `WebServerFactoryCustomizer` 里直接 `ssl.setBundle("creed-pem-server")` + `factory.setSslBundles(...)`，无需手工加载 KeyStore/TrustManager。 |
| **可热重载** | Bundle 支持 `reload-on-update` / `SslBundle` 重新加载，证书轮换无需重启（相比硬编码 `KeyManagerFactory` 更友好）。 |
| **与 Netty/底层互通** | 通过 `bundle.getManagers()` 拿到 `KeyManagerFactory` / `TrustManagerFactory` 喂给 `io.netty SslContextBuilder`，把 Spring 抽象桥接到底层 SSL。 |
| **PEM / JKS 双格式** | config-server 监听用 PEM bundle，资源/网关用 JKS(PKCS12) bundle，按场景选格式而上层用法不变。 |
| **职责清晰、最小本地材料** | 客户端本地只需一个 truststore 信任 config-server 的自签 CA（bootstrap 那一跳），其余 bundle 全部由 config-server 下发。 |

## 配置集中管理（Config Server）的优势

| 优势 | 具体体现 |
|------|---------|
| **单一事实来源** | bundle 定义、`lb://` 实例、路由、口令都集中在 `config-repo/*.yml`，5 个服务不各自维护一份。 |
| **密钥安全：非对称加密 + 服务端自动解密** | 口令以 `{cipher}…`（RSA 公钥加密）入库，明文不进仓库；config-server 持私钥（`config-encrypt.p12`）在下发时自动解密，客户端拿到的是明文 `${creed.ssl.keystore-password}` 直接可用。 |
| **传输安全** | 配置经 HTTPS（8443）+ Basic Auth 下发，配置内容在链路上不裸奔。 |
| **改配置免改代码 / 免重打包** | 改证书 bundle、加资源实例、调路由只动 config-repo，应用侧零改动。 |
| **环境隔离** | 同一套机制可按 profile/label 分环境（dev/prod）下发不同 bundle、实例、口令。 |
| **与服务发现/负载均衡解耦** | `discovery.client.simple.instances` 由 config-server 下发，Gateway 的 LoadBalancer 实例清单可集中调整，无需改网关代码。 |
| **可审计、可回滚** | 配置在仓库中有版本历史，问题可追溯、可回退。 |
| **环境变量可覆盖** | 关键值（如 `CREED_AUTH_ISSUER`、`CREED_CONFIG_*`）仍可用环境变量覆盖，兼顾集中管理与部署灵活性。 |

## Dev PKI

`creed-config-server/scripts/generate-certs.ps1` generates the development PKI and the RSA
encryption keypair, then copies the artifacts into each module's `src/main/resources/certs/`:

- `ca.crt`, `server-keystore.p12`, `client-keystore.p12`, `truststore.p12` — TLS material.
- `config-encrypt.p12` (alias `config-key`) — RSA keypair backing `/encrypt` & `/decrypt`.

All dev passwords are `changeit`. `openssl` and `keytool` must be on `PATH` (e.g. Git's
`mingw64\bin` and a JDK's `bin`).

To (re)encrypt the keystore password after regenerating keys, with the config server running:

```powershell
curl.exe -sk -u creed-config:configpass -d "changeit" https://localhost:8443/config-server/encrypt
# paste the result as '{cipher}...' into config-repo/*.yml
```

## Run locally

```powershell
$env:JAVA_HOME = "<jdk-21>"
mvn -o -DskipTests install
# start in order; downstream apps need config-server up first
mvn -o -pl creed-config-server                       spring-boot:run
mvn -o -pl creed-author-server                       spring-boot:run
mvn -o -pl creed-resource/creed-resource-catalog     spring-boot:run
mvn -o -pl creed-resource/creed-resource-order       spring-boot:run
mvn -o -pl creed-gateway                             spring-boot:run
```

## Verify

```powershell
# token endpoint lives under the /auth-server context-path (= the issuer)
$tok = (curl.exe -s -u creed-client:creed-secret -d "grant_type=client_credentials&scope=api.read" `
        http://127.0.0.1:9000/auth-server/oauth2/token | ConvertFrom-Json).access_token

# aggregator (lb:// resolved in-controller, fetched over HTTPS, merged):
curl.exe -sk -H "Authorization: Bearer $tok" https://localhost:8080/api/aggregate/summary
# {"catalog":{...,"service":"creed-resource-catalog"},
#  "orders":{...,"service":"creed-resource-order"},"aggregatedBy":"creed-gateway"}

# gateway lb:// routes:
curl.exe -sk -H "Authorization: Bearer $tok" https://localhost:8080/api/catalog/items
curl.exe -sk -H "Authorization: Bearer $tok" https://localhost:8080/api/order/items
```

## OAuth2 issuer

The author server is deployed under context-path `/auth-server`, so its OIDC discovery document is
served at `http://127.0.0.1:9000/auth-server/.well-known/openid-configuration`. The issuer is set to
match that base URL — `creed.auth.issuer` (default `http://127.0.0.1:9000/auth-server`) feeds
`AuthorizationServerSettings.issuer(...)` — so the `iss` claim, the metadata endpoint URLs, and the
served discovery path all agree. The resource servers and gateway use that same value as their
`spring.security.oauth2.resourceserver.jwt.issuer-uri` (overridable via `CREED_AUTH_ISSUER`), so the
eager `NimbusJwtDecoder.withIssuerLocation(...)` resolves cleanly. No context-path override is
needed; the token endpoint is `http://127.0.0.1:9000/auth-server/oauth2/token`.
