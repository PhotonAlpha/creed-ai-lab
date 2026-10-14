# 分层配置仓库（native）

creed-config-server 用 `spring.cloud.config.server.native.search-locations` 把六层配置叠起来，
**越往右优先级越高**，右边的同名 key 覆盖左边：

```
common  →  type  →  env-group  →  env  →  app  →  app-env
所有模块    模块类型    sit/uat/     sit1…    单个模块   单个模块在
所有环境              nft/pdn      pdn      默认值     某环境的差异
```

| 层 | 目录 | 放什么 |
|---|---|---|
| common | `common/application.yml` | 所有模块、所有环境都一样的值 |
| type | `type/<type>/application.yml` | 某一类模块共有的值（超时、线程池、是否走 OAuth2） |
| env-group | `env-group/<group>/application.yml` | 一组环境共有的值（日志级别、暴露的 actuator 端点） |
| env | `env/<env>/application.yml` | 单个环境特有的值（各种地址、issuer） |
| app | `app/<app>/application.yml` | 单个模块在所有环境下的默认值 |
| app-env | `app-env/<app>/<env>/application.yml` | 单个模块在某个环境里和别处不同的值；尽量少用 |

## 目录清单

| type | 模块 |
|---|---|
| `ms` | creed-resource-catalog / -order / -payment / -env-matrix，creed-author-server |
| `gateway` | creed-gateway，creed-gateway-partner |
| `proxy-gateway` | creed-gateway-proxy |
| `aggregator-ms` | creed-simple-metrics |

| env-group | env |
|---|---|
| `sit` | `sit1` `sit2` `sit3` |
| `uat` | `uat1` `uat2` `uat3` |
| `nft` | `nft` |
| `pdn` | `pdn` |

这份清单在 `ConfigRepositoryLayoutTest` 里也有一份，测试以它为准。目录名拼错时服务端不会报错，只是那一层永远不生效，所以由测试来拦。

## 客户端怎么请求

profile 固定传三段：**`<type>,<env-group>,<env>`**。

```
GET /config-server/creed-resource-order/ms,sit,sit1
```

服务端对每个 location 模板，用这三个值分别替换 `{profile}`，只保留真实存在的目录。
比如 `type/{profile}/` 会展开成 `type/ms/`、`type/sit/`、`type/sit1/`，其中只有 `type/ms/` 存在。
结果按层排序，最终是 6 个（或更少）属性源：

```
app-env/creed-resource-order/pdn   ← 最高优先级
app/creed-resource-order
env/pdn
env-group/pdn
type/ms
common                             ← 最低
```

客户端配置（例如放在 `application-cloud.yml`）：

```yaml
spring:
  application:
    name: creed-resource-order          # 对应 app/<name>/、app-env/<name>/
  config:
    import: "configserver:https://localhost:8443/config-server"
  cloud:
    config:
      # <type>,<env-group>,<env>：type 写死在模块里，后两段由部署环境给出
      profile: ms,${CREED_ENV_GROUP},${CREED_ENV}
```

部署时同时设置 `CREED_ENV=sit1` 和 `CREED_ENV_GROUP=sit`，两者要对应上表。nft 和 pdn 的组名与环境名相同，传 `ms,pdn,pdn` 即可。

- **不传这三段**（例如现有模块的 `cloud` profile）时只拿到 `common` + `app`，和分层之前的行为一致。
- **跨层引用**是在客户端合并之后才解析的。所以 `type/ms` 里可以写 `issuer-uri: ${creed.env.auth-issuer}`，实际值由 env 层提供；common 里给了本地默认值，缺少 env 层时也不会解析失败。
- **`{cipher}` 值**在服务端解密后才下发，客户端拿到的是明文。

## 规则（都有测试守着）

1. **每个层目录只放一个 `application.yml`。** 服务端还会在每个目录里读取 `{app}.yml` 和
   `application-{profile}.yml`，而 Spring Boot 先按**文件名**、再按**位置**排序。实测把
   `type/ms/creed-resource-order.yml` 放进去后，它的优先级排到了 env 层和 env-group 层之上。
2. **`app-env/<app>/` 下只能是环境名**，不能放组名或类型名。放组名的话目录也会被读到，
   而且排在 env 值之前，这种顺序没人会想到。
3. **每个文件都要写 `creed.config.layer: <所在层>`**，并在 `creed.config.layers.<层>` 下记录自己。
   客户端打印 `creed.config.*` 就能看到叠了哪些层、最终哪一层生效。
4. **`app-env/<app>` 必须有对应的 `app/<app>`**。

## 新增一个环境 / 类型 / 模块

- **新环境 `sit4`：** 建 `env/sit4/application.yml`，并把 `sit4` 加进 `ConfigRepositoryLayoutTest.ENVS`。
- **新组：** 建 `env-group/<g>/`，加进 `ENV_GROUPS`。
- **新类型：** 建 `type/<t>/`，加进 `TYPES`。
- **新模块：** 只有需要模块级默认值时才建 `app/<name>/`；没有这个目录也能拿到前四层。

## 验证

```bash
curl -k -u creed-config:configpass \
  https://localhost:8443/config-server/creed-gateway/gateway,uat,uat2 | jq '.propertySources[].name'
```

不改 jar、直接编辑某个 checkout：`CREED_CONFIG_REPO_ROOT=file:/opt/creed/config-repo`（末尾不要加 `/`）。
