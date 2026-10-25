# 03 配置、探针、资源

## 目标

- 配置与镜像分离：同一个镜像，用 ConfigMap/Secret 区分 mock 和真实 Jira。
- 三种探针各用对地方，滚动发布时没有一个请求失败。
- 给容器设置合理的 requests/limits，理解 JVM 堆和容器内存上限的关系。
- 进阶练习：keystore/truststore 和它们的密码怎么进 Pod；ConfigMap 挂成文件让 Spring 启动时读取；
  用 actuator 看清每个配置值到底来自哪里。

## 知识点

### 命令逐段解释
```shell
oc create secret generic veracode-secrets --from-literal=CREED_JIRA_TOKEN=dummy-for-mock
```

| 部分  |  含义 |
|-----|---|
|  `oc create`   |  创建一个资源 |
|  `secret`   | `资源类型是 Secret，用来存放密码、令牌等敏感信息`  |
|  `generic`   | `Secret 的子类型，表示"通用型"，就是普通的键值对。其他类型还有 docker-registry（镜像仓库凭证）、tls（证书）等`  |
|  `veracode-secrets`  |  这个 Secret 的名字，之后在 Deployment 里引用它就用这个名字 |
|  `--from-literal=CREED_JIRA_TOKEN=dummy-for-mock`  |  直接在命令行里写一个键值对：键是 CREED_JIRA_TOKEN，值是 dummy-for-mock |

所以整条命令的意思是：创建一个名叫 veracode-secrets 的 Secret，里面存一条数据，键为 CREED_JIRA_TOKEN，值为 dummy-for-mock。从名字看，dummy-for-mock 是个占位的假值，用来模拟一个 Jira 令牌，真实场景里这里会换成真正的 token。

### 配置注入

- **ConfigMap 映射成环境变量** → Spring 的宽松绑定：`VERACODE_JIRA_PROJECT_KEY` 对应 `veracode.jira.project-key`。
  本仓库的约定是 `${CREED_FOO:fallback}`，所以直接设置 `CREED_JIRA_PROJECT`、`CREED_VERACODE_MOCK` 这类变量最省事。
- **Secret** 用来放 `CREED_JIRA_TOKEN` 这类敏感值。Secret 默认只是 **base64 编码，不是加密**；靠 RBAC 控制谁能读，
  生产环境再配合 etcd 加密或外部密钥管理系统。
- **修改 ConfigMap 不会让 Pod 自动重启**。以环境变量方式注入时，需要 `oc rollout restart`。

### 探针（Spring Boot 在 Kubernetes 上会自动开启探针健康组）

| 探针 | 端点 | 失败时的后果 | 用途 |
|---|---|---|---|
| startupProbe | `/actuator/health/liveness` | 启动期间失败只是继续等，不重启 | JVM 启动慢；避免 liveness 在启动阶段误杀 |
| readinessProbe | `/actuator/health/readiness` | 从 Service 的 EndpointSlice 中**摘除**（不重启） | 决定流量能不能进来，**这就是负载均衡的开关** |
| livenessProbe | `/actuator/health/liveness` | **重启容器** | 只用来检测「进程卡死」，**不要把下游依赖放进去** |

Spring Boot 检测到 `KUBERNETES_SERVICE_HOST` 环境变量时，会自动启用 `liveness` 和 `readiness` 两个健康组。

### 资源

- `requests` 决定调度和资源保证；`limits.memory` 超出就会被 **OOMKilled**；`limits.cpu` 超出不会被杀，只会被限流（throttle）。
- **JVM 默认只用容器内存上限的 25% 做堆**，所以第 02 章设置了 `-XX:MaxRAMPercentage=75`。
  剩下的部分留给元空间、线程栈、直接内存（Tomcat 和 POI 都会用到）。
- **HPA** 需要指标：CRC 上要开启集群监控（第 00 章）才有 CPU 指标。

## 步骤

```bash
# 1. 配置
oc create configmap veracode-config \
  --from-literal=CREED_VERACODE_MOCK=true \
  --from-literal=CREED_JIRA_PROJECT=CREED
oc create secret generic veracode-secrets --from-literal=CREED_JIRA_TOKEN=dummy-for-mock
oc set env deploy/veracode --from=configmap/veracode-config
oc set env deploy/veracode --from=secret/veracode-secrets

# 2. 探针
oc set probe deploy/veracode --startup   --get-url=http://:18097/actuator/health/liveness  --period-seconds=5 --failure-threshold=30
oc set probe deploy/veracode --readiness --get-url=http://:18097/actuator/health/readiness --period-seconds=5
oc set probe deploy/veracode --liveness  --get-url=http://:18097/actuator/health/liveness  --period-seconds=10

# 3. 资源
oc set resources deploy/veracode --requests=cpu=100m,memory=384Mi --limits=memory=768Mi

# 4. 把上面的改动导回 YAML，提交到 manifests/（这是第 08 章 GitOps 的素材）
oc get deploy veracode -o yaml | less    # 手工整理出 spec 部分，删掉 status、uid、resourceVersion 等字段

# 5.（开启了监控时）HPA
oc autoscale deploy/veracode --min=2 --max=4 --cpu-percent=70
oc get hpa -w
```

## 检查点

- [ ] `oc set env deploy/veracode --list` 能看到来自 ConfigMap 和 Secret 的变量
- [ ] 修改 ConfigMap 后，确认 Pod 里的值**没有**变化；执行 `oc rollout restart deploy/veracode` 后才变
- [ ] `oc describe pod` 里能看到三个探针；人为制造启动慢（例如把 startup 的 `failure-threshold` 改成 1），观察会发生什么
- [ ] 把 `limits.memory` 改成 200Mi，观察 `OOMKilled`（`oc get pod` 的 `RESTARTS` 一列和 `oc describe` 里的 `Last State`）

## 进阶练习

三个练习建议按 3 → 2 → 1 的顺序做：先学会用 actuator 看「值从哪来」，后面两个练习都靠它验证。

### 练习 1：veracode 启用 HTTPS，内部 RestClient 加载 keystore / truststore 及其密码

**背景**：veracode 默认是纯 HTTP，Confluence/Jira 的 mock 走的是「Pod 自己调自己」
（`http://localhost:${server.port}/mock/...`）。服务端一旦开 TLS，这条自调用链路会依次坏掉两次，正好把
「服务端身份」和「客户端信任」两件事分开练：

| 改动 | 现象 | 原因 |
|---|---|---|
| 只开 `server.ssl` | 调用 `/api/veracode/...` 报错，日志里是连接被重置 / `HTTP/1.1 header parser received no bytes` | RestClient 还在用 `http://` 访问一个只说 TLS 的端口 |
| base URL 改成 `https://localhost:18097/...` | `PKIX path building failed` | JDK 默认信任库里没有 Creed 私有 CA |
| 给 RestClient 配上 truststore | 正常 | 证书链可验证，且 SAN 里有 `localhost` |

**要点**

- **证书**：不要重跑 `.support/scripts/CA-Generation.sh`——它每次都新建根 CA，会让所有服务现有的 keystore 失效。
  照 `.support/httpd/issue-cert.sh` 的做法，用**已有的**中间 CA 再签一张叶子证书。SAN 要覆盖集群内的所有访问名：
  `veracode`、`veracode.creed-lab.svc`、`veracode.creed-lab.svc.cluster.local`，以及自调用用的 `localhost`。
- **文件和密码分开放、都放 Secret**：`.p12` 是二进制，用 `--from-file` 放进 Secret 再挂成文件；
  密码绝不进 ConfigMap，也不写进镜像或 `application.yml`。
- **密码进 Spring 的两种方式**，都要会：

  | 方式 | 写法 | 优点 | 缺点 |
  |---|---|---|---|
  | A. 环境变量 | `env[].valueFrom.secretKeyRef` → `CREED_VERACODE_SSL_PASSWORD`，yml 里 `password: ${CREED_VERACODE_SSL_PASSWORD}` | 和本仓库 `${CREED_FOO:fallback}` 约定一致，最直观 | 进程环境对容器内所有进程可见（`/proc/1/environ`）；Secret 轮换必须重启 |
  | B. configtree | Secret 挂成目录，`SPRING_CONFIG_IMPORT=optional:configtree:/etc/veracode/ssl-pass/`，**文件名就是属性名** | 值只以文件存在；Secret 更新后文件会被 kubelet 原地替换 | 文件名必须是合法属性名（如 `veracode.ssl.password`）；Spring 仍只在启动时读一次 |

- **SSL bundle 是唯一的入口**：服务端和客户端都引用同一个 `spring.ssl.bundle.jks.<name>`，密码只写一处。
- **这个模块的 RestClient 不会自动吃 bundle**：`RemoteClientsConfiguration.configure()` 自己 new 了
  `JdkClientHttpRequestFactory`，Boot 关于 HTTP 客户端 SSL 的全局属性对它不生效。必须注入 `SslBundles`，
  把 `bundle.createSslContext()` 交给 `HttpClient.newBuilder().sslContext(...)`。
  这是「自定义了 request factory 就要自己接管 SSL」的典型例子。
- **TLS 之后的连锁改动**：探针要改成 `scheme: HTTPS`（kubelet 不校验证书，所以不需要信任 CA）；
  第 02 章的 edge Route 需要改成 `reencrypt`（带 `destinationCACertificate`）或 `passthrough`，第 06 章细讲。

**步骤**

```bash
# 1. 用现有 CA 签一张 veracode 证书（产物放 tmp/，不污染 .support/scripts/pki）
PKI=.support/scripts/pki; OUT=tmp/ocp03/pki; mkdir -p $OUT
openssl genrsa -out $OUT/veracode.key 2048
openssl req -new -sha256 -key $OUT/veracode.key -subj "/C=SG/O=Creed/OU=Platform/CN=veracode" -out $OUT/veracode.csr
openssl x509 -req -sha256 -days 825 -in $OUT/veracode.csr \
  -CA $PKI/creed-CA-Public-RSA.crt -CAkey $PKI/creed-CA-Public-RSA.key \
  -CAserial $OUT/ca.srl -CAcreateserial \
  -extfile <(printf "%s\n" "basicConstraints=critical,CA:FALSE" \
      "keyUsage=critical,digitalSignature,keyEncipherment" "extendedKeyUsage=serverAuth" \
      "subjectAltName=DNS:veracode,DNS:veracode.creed-lab.svc,DNS:veracode.creed-lab.svc.cluster.local,DNS:localhost") \
  -out $OUT/veracode.crt
openssl pkcs12 -export -inkey $OUT/veracode.key -in $OUT/veracode.crt -certfile $PKI/ca-chain.crt \
  -name veracode -passout pass:changeit -out $OUT/veracode-keystore.p12
cp $PKI/creed-CA-Public-RSA-truststore.p12 $OUT/veracode-truststore.p12   # 根 + 中间 CA，密码同为 changeit

# 2. 两个 Secret：一个装文件，一个装密码（键名就是 configtree 下的属性名）
oc create secret generic veracode-tls \
  --from-file=keystore.p12=$OUT/veracode-keystore.p12 \
  --from-file=truststore.p12=$OUT/veracode-truststore.p12
oc create secret generic veracode-ssl-pass --from-literal=veracode.ssl.password=changeit

# 3. 挂载
oc set volume deploy/veracode --add --name=tls       --type=secret --secret-name=veracode-tls      --mount-path=/etc/veracode/tls
oc set volume deploy/veracode --add --name=ssl-pass  --type=secret --secret-name=veracode-ssl-pass --mount-path=/etc/veracode/ssl-pass

# 4. 打开 ssl profile（bundle、server.ssl、mock 的 https 地址都在 application-ssl.yml 里），
#    密码用方式 B（configtree）：
oc set env deploy/veracode \
  SPRING_PROFILES_ACTIVE=ssl \
  SPRING_CONFIG_IMPORT=optional:configtree:/etc/veracode/ssl-pass/
#    方式 A：键名带点不能当环境变量名，所以另建一个 Secret，键就叫 CREED_VERACODE_SSL_PASSWORD
#    （两种都设时环境变量赢）：
#      oc create secret generic veracode-ssl-env --from-literal=CREED_VERACODE_SSL_PASSWORD=changeit
#      oc set env deploy/veracode --from=secret/veracode-ssl-env

# 5. 探针改走 HTTPS
oc set probe deploy/veracode --startup   --get-url=https://:18097/actuator/health/liveness  --period-seconds=5 --failure-threshold=30
oc set probe deploy/veracode --readiness --get-url=https://:18097/actuator/health/readiness --period-seconds=5
oc set probe deploy/veracode --liveness  --get-url=https://:18097/actuator/health/liveness  --period-seconds=10
```

**先在本机跑通**（不需要集群；第 1 步的证书已经在 `tmp/ocp03/pki/`）：

```bash
mkdir -p tmp/ocp03/ssl-pass && printf changeit > tmp/ocp03/ssl-pass/veracode.ssl.password
CREED_VERACODE_SSL_KEYSTORE=file:tmp/ocp03/pki/veracode-keystore.p12 \
CREED_VERACODE_SSL_TRUSTSTORE=file:tmp/ocp03/pki/veracode-truststore.p12 \
SPRING_CONFIG_IMPORT=optional:configtree:tmp/ocp03/ssl-pass/ \
mvn -pl creed-resource/creed-resource-veracode spring-boot:run \
  -Dspring-boot.run.profiles=ssl -Dspring-boot.run.workingDirectory="$PWD"

curl --cacert .support/scripts/pki/ca-chain.crt -XPOST https://localhost:18097/api/veracode/analysis \
  -H 'Content-Type: application/json' -d '{}'
```

<details>
<summary>参考答案：已经实现在 veracode 模块里</summary>

| 文件 | 改动 |
|---|---|
| `src/main/resources/application-ssl.yml` | 新 profile：`spring.ssl.bundle.jks.veracode`（keystore + truststore，路径默认 `/etc/veracode/tls/*.p12`）、`server.ssl.bundle: veracode`、`client-auth: none`（单向 TLS，客户端不出示证书）、mock 地址改 https、两个客户端的 `ssl-bundle: veracode` |
| `application.yml` | `veracode.confluence.ssl-bundle` / `veracode.jira.ssl-bundle`，默认空 = JDK 默认信任库 |
| `ConfluenceProperties` / `JiraProperties` | 新字段 `sslBundle` |
| `RemoteClientsConfiguration` | 注入 `SslBundles`；`ssl-bundle` 非空时 `HttpClient.newBuilder().sslContext(bundle.createSslContext())` |

密码写成 `${CREED_VERACODE_SSL_PASSWORD:${veracode.ssl.password}}`：先找环境变量（方式 A），找不到再用 configtree 文件（方式 B）。

为什么 bundle 只声明在 `ssl` profile 里：Boot 启动时会主动打开**所有已声明**的 bundle，
写进 `application.yml` 的话，没有 keystore 的本地纯 HTTP 运行会直接起不来。

为什么每个客户端各有一个 `ssl-bundle`：真实的 Confluence 和 Jira 可能由不同的 CA 签发；
现在两者都指向自己，所以都用 `veracode`。客户端用的 SSLContext 里虽然也带着 keystore，
但 `client-auth: none` 时服务端不会要求客户端证书，keystore 不会被发出去。

</details>

**验证**（本机都已实测，结果如下）

- [ ] `curl https://localhost:18097/actuator/health` 不带 CA 时退出码 60（证书不受信任），带 `--cacert .support/scripts/pki/ca-chain.crt` 返回 `UP`，
      并且多了一个 `ssl` 健康组件，列出证书链和到期时间。在 Pod 里同样如此——容器的系统信任库和 Java 的 truststore 是两回事
- [ ] 用 `http://` 访问 18097 返回 `400 This combination of host and port requires TLS.`
- [ ] 分析接口返回 10 条发现（3 条已存在、7 条缺失、1 条版本不在 Jira）。设 `CREED_CONFLUENCE_SSL_BUNDLE=`（空）后，
      接口返回 502 `confluence_error`：`unable to find valid certification path to requested target`，也就是 PKIX
- [ ] 把密码改错，进程在启动期就退出，根因是
      `UnrecoverableKeyException: failed to decrypt safe contents entry ... BadPaddingException`。
      错误出现在启动期，而不是第一次调用时：`SslMeterBinder`、actuator `SslInfo` 和 Tomcat 都会在启动时打开 bundle
- [ ] **两种密码都不设**，报错和密码错误**一模一样**，并不会提示「占位符无法解析」：绑定器会把字面量
      `${veracode.ssl.password}` 原样当作密码。所以看到 `BadPaddingException`，先确认密码的来源存在，再怀疑密码本身
- [ ] `oc set env deploy/veracode --list` 里**看不到**密码明文（方式 B）；`oc exec ... -- cat /etc/veracode/ssl-pass/veracode.ssl.password` 能看到——RBAC 才是真正的边界

**扩展**：OpenShift 自带 service CA。给 Service 加注解
`service.beta.openshift.io/serving-cert-secret-name=veracode-serving-cert`，集群会自动签发 PEM 格式的 `tls.crt`/`tls.key`
并自动轮换；Spring 用 `spring.ssl.bundle.pem.*` 直接读取，**根本不需要 keystore 密码**。对比一下两种方案的运维成本。

### 练习 2：把配置放进 ConfigMap，启动时加载一部分属性

**背景**：本章前面是把 ConfigMap 映射成**环境变量**，适合少量开关。配置一多（如 `veracode.report.*` 这种列表、
`jira.labels`），更自然的做法是把一个 yml 片段挂成**文件**，让 Spring 在启动时和 classpath 里的 `application.yml` 合并：
ConfigMap 里**只写要覆盖的那几个键**，其余仍取镜像里的默认值。

**要点**

- 挂载目录 + `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/etc/veracode/config/`。
  `additional-location` 是在默认位置**之外追加**，优先级高于 classpath；而 `spring.config.location` 会**替换**默认位置，
  classpath 里的 `application.yml` 就不读了——这是最常见的「配置全丢了」原因。
- 挂成**目录**，不要用 `subPath` 挂成单个文件：`subPath` 挂载的文件永远不会随 ConfigMap 更新。
- 挂目录时文件会被 kubelet 原地更新（约一分钟内），但 **Spring 只在启动时读一次**，所以仍然要
  `oc rollout restart`。真想热更新需要 Spring Cloud Kubernetes 的 reload，或者把 ConfigMap 的哈希写进 Pod 模板注解来触发滚动（第 08 章 Kustomize 的 `configMapGenerator` 就是这么做的）。
- 优先级（高 → 低）：命令行参数 > 环境变量 > configtree > `additional-location` 下的文件 > classpath `application.yml`。
  所以同一个键既在 ConfigMap 文件里、又在环境变量里时，**环境变量赢**。
- 也可以给 ConfigMap 起名 `application-ocp.yml`，配合 `SPRING_PROFILES_ACTIVE=ocp`，让「集群专属配置」成为一个 profile。

**步骤**

```bash
cat > tmp/ocp03/application.yml <<'EOF'
veracode:
  jira:
    issue-type: Task          # 默认 Bug
    jql-batch-size: 20        # 默认 40
    labels: [veracode, ocp]
  upload:
    max-entries: 20
EOF
oc create configmap veracode-app-config --from-file=application.yml=tmp/ocp03/application.yml
oc set volume deploy/veracode --add --name=app-config --type=configmap \
  --configmap-name=veracode-app-config --mount-path=/etc/veracode/config
oc set env deploy/veracode SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/etc/veracode/config/
```

最后一行是「应用自己不知道要读这个目录」时的通用做法。veracode 已经把这个目录内置成默认值了（见下面的参考答案），
所以对它来说这一行可以省略；对别的 Spring Boot 应用，这一行就是全部的接入成本。

<details>
<summary>参考答案：已经实现在 veracode 模块和 manifests 里</summary>

| 文件 | 改动 |
|---|---|
| `CreedResourceVeracodeApplication` | `setDefaultProperties(spring.config.additional-location = optional:file:${CREED_VERACODE_CONFIG_DIR:/etc/veracode/config/})` |
| `manifests/veracode/base/configmap.yaml` | ConfigMap `veracode-app-config`，只含要覆盖的四个键 |
| `manifests/veracode/base/deployment.yaml` | 挂载到 `/etc/veracode/config`（整个目录，`optional: true`） |
| `ExternalConfigDirTest` | 目录里的键覆盖生效、没写的键保持打包值；目录不存在不报错 |

```bash
oc apply -f openshift-learning/manifests/veracode/base/configmap.yaml
oc apply -f openshift-learning/manifests/veracode/base/deployment.yaml
```

几个实现上的取舍：

- **为什么写成 default property，而不是写在 `application.yml` 里**：`spring.config.additional-location`
  在读取任何配置文件**之前**就要确定，写在配置文件里已经来不及。default property 是优先级最低的属性源，
  所以 `SPRING_CONFIG_ADDITIONAL_LOCATION` 环境变量仍然可以整体替换它。
- **为什么测试不用 `@SpringBootTest`**：它不会执行 `main()`，default property 就不会生效。
  所以 `main()` 和测试共用同一个 `application()` 工厂方法。
- **`optional:` 必须写**：本地运行时没有 `/etc/veracode/config/`；不加 `optional:` 应用会直接启动失败。

本机实测（`ssl` profile，配置目录里放了 `application.yml` 和 `application-ssl.yml`）得到的属性源顺序：

```text
systemEnvironment
Config resource 'file [.../application-ssl.yml]'        ← 目录里的 profile 文件也会被读
Config resource 'file [.../application.yml]'            ← ConfigMap
Config resource 'class path resource [application-ssl.yml]'   ← 镜像里的 profile 文件，排在 ConfigMap 之后
Config resource 'class path resource [application-actuator.yml]'
Config resource 'class path resource [application.yml]'
defaultProperties                                        ← additional-location 本身就放在这里
```

也就是说，**ConfigMap 的 `application.yml` 连镜像里 profile 专属的 `application-ssl.yml` 都能覆盖**：
`browse-url` 同时写在 ConfigMap 和 `application-ssl.yml` 里时，ConfigMap 的值生效。

</details>

**验证**（用练习 3 的 actuator）

- [ ] `/actuator/env/veracode.jira.issue-type` 的 `property.source` 是
      `Config resource 'file [/etc/veracode/config/application.yml]' ...`，值为 `Task`
- [ ] 没写进 ConfigMap 的 `veracode.jira.page-size` 仍来自 `class path resource [application.yml]`
- [ ] 再加一个环境变量 `VERACODE_JIRA_ISSUE_TYPE=Story`，先预测再验证：最终值是 `Story`，来源是 `systemEnvironment`
- [ ] `oc edit configmap veracode-app-config` 改 `jql-batch-size`，`oc exec ... -- cat /etc/veracode/config/application.yml`
      一分钟内看到新值，但 actuator 里的值不变；`oc rollout restart` 后才变
- [ ] 把 `ADDITIONAL_LOCATION` 换成 `SPRING_CONFIG_LOCATION`，观察哪些默认值消失了，再改回来

### 练习 3：actuator 显示 env 的值，并能看出值的来源

**背景**：Boot 3 起 `/actuator/env` 和 `/actuator/configprops` 默认把所有值显示成 `******`。
veracode 的 `application.yml` 已经打开了：

```yaml
management:
  endpoint:
    env:            { access: unrestricted, show-values: always }
    configprops:    { show-values: always }
  endpoints.web.exposure.include: "*"
```

**三个端点，各回答一个问题**

| 端点 | 回答 | 关键字段 |
|---|---|---|
| `/actuator/env/{key}` | 这个键**最终**是什么值、被哪个 property source 赢下 | `property.source`；`propertySources[].property.origin`（文件 + 行:列，或 `System Environment Property "XXX"`） |
| `/actuator/env` | 所有 property source 的**优先级顺序** | 数组顺序就是优先级，靠前的赢 |
| `/actuator/configprops` | `@ConfigurationProperties` bean 实际绑定了什么 | `beans.*.inputs.<field>.value/origin`，能看到**占位符原文** |

**一个必须亲眼看到的坑：占位符会「掩盖」真实来源**。下面是在本机实际跑出来的结果
（`CREED_JIRA_PROJECT=ENVPROJ`、`VERACODE_JIRA_PAGE_SIZE=7`）：

```text
GET /actuator/env/veracode.jira.project-key
  property.source = Config resource 'class path resource [application.yml]' ...   ← 说是 yml
  value           = ENVPROJ                                                        ← 值却来自环境变量
  origin          = class path resource [application.yml] - 45:18

GET /actuator/env/veracode.jira.page-size
  property.source = systemEnvironment
  origin          = System Environment Property "VERACODE_JIRA_PAGE_SIZE"

GET /actuator/configprops  → JiraProperties.inputs.projectKey
  value  = ${CREED_JIRA_PROJECT:CREED}      ← 原文暴露了它依赖哪个环境变量
  origin = class path resource [application.yml] - 45:18
```

`project-key` 在 yml 里写的是 `${CREED_JIRA_PROJECT:CREED}`：赢下这个键的是 yml 那一行，占位符是之后才从环境变量里解析出来的，
`origin` 只记录「键在哪定义」，不记录「占位符从哪解析」。所以排查本仓库的 `CREED_*` 变量时要走两步：
先看 `configprops` 的 `inputs` 找到占位符名，再查 `/actuator/env/CREED_JIRA_PROJECT`（来源 `systemEnvironment`）。
而像 `VERACODE_JIRA_PAGE_SIZE` 这种按宽松绑定**直接**覆盖键的变量，来源一步就能看到。

**步骤**

```bash
oc port-forward deploy/veracode 18097:18097 &      # 不经过 Route；做完练习 1 后改用 https 并加 -k
curl -s localhost:18097/actuator/env/veracode.jira.project-key | jq '.property, [.propertySources[] | select(.property)]'
curl -s localhost:18097/actuator/env | jq -r '.propertySources[].name'           # 优先级顺序
curl -s localhost:18097/actuator/configprops \
  | jq '.contexts[].beans[] | select(.prefix=="veracode.jira") | .inputs'
curl -s localhost:18097/actuator/env/CREED_JIRA_TOKEN | jq .property              # 来自 Secret 的变量
curl -s localhost:18097/actuator/env/veracode.ssl.password | jq .property         # 练习 1 的 configtree
```

**然后修掉 `show-values: always` 带来的泄露**：上面最后两条会**明文**返回 token 和 keystore 密码——`always` 不做任何脱敏。
写一个 `SanitizingFunction` bean，只遮住敏感键，其余照常显示：

<details>
<summary>参考答案：已经实现在 veracode 模块里（<code>config/ActuatorSanitizingConfiguration</code>）</summary>

```java
@Bean
SanitizingFunction maskSecrets() {
    return SanitizingFunction.sanitizeValue()
            .ifKeyEndsWith("password", "secret", "token")
            .ifKeyContains("credentials")
            .ifLikelySensitiveProperty();     // sun.java.command、spring.application.json
}
```

- 链式的 `ifXxx` 之间是**或**的关系：命中任意一条就遮住。键的匹配**不区分大小写**，
  所以环境变量 `CREED_JIRA_TOKEN`、configprops 里的 `veracode.jira.auth.token`、configtree 文件 `veracode.ssl.password` 都会被遮住。
- **为什么不用现成的 `ifLikelySensitive()`**：它的后缀规则里有 `key`、`url`、`address`，
  会把 `veracode.jira.project-key` 和所有 base URL 一起遮住，而这些恰好是排查时最需要看的值。用之前先看它到底匹配哪些键。
- 自定义函数先执行；没被它遮住的值，再由 `show-values` 决定是否显示。所以 `always` 加上这个函数，效果就是「敏感值遮住、其余明文」。
- 单元测试 `ActuatorSanitizingConfigurationTest` 把两份键列表都写死了：一份必须被遮住，另一份必须保持明文。

不想改代码的话：`show-values: when-authorized` + `roles`，只有登录并且有对应角色的人才能看到明文。

</details>

**验证**

- [ ] 能对 `issue-type`、`page-size`、`project-key`、`veracode.ssl.password` 四个键分别说出值来源（yml / 环境变量 / ConfigMap 文件 / configtree），并用 actuator 证实
- [ ] `/actuator/env` 里 `Config tree '...'` 排在 `Config resource 'file [...]'` 之前，解释为什么 Secret 覆盖得了 ConfigMap 文件
- [ ] 加上 `SanitizingFunction` 后（本机已实测）：`CREED_JIRA_TOKEN`、`veracode.ssl.password`、
      `spring.ssl.bundle.jks.veracode.keystore.password`、configprops 里的 `auth.token` 都是 `******`；
      `project-key`（`ENVPROJ`）和 `base-url` 仍是明文；在完整的 `/actuator/env` 输出里 grep 不到 token 和密码原文
- [ ] actuator 只通过 `oc port-forward` 访问；`curl https://<route>/actuator/env` 应该拿不到（见常见坑）

## 深入练习：看清机制

上面三个进阶练习讲的是配置；这一节讲探针和资源背后的机制。格式：**要弄清的问题 → 命令 → 观察 / 解释**。

### 实验 1：ConfigMap 卷是怎么「原子更新」的

```bash
oc exec deploy/veracode -- ls -la /etc/veracode/config/
oc edit configmap veracode-app-config          # 随便改一个值
sleep 70; oc exec deploy/veracode -- ls -la /etc/veracode/config/
```

**观察**：
- `application.yml` 是一个符号链接，指向 `..data/application.yml`；`..data` 又指向一个带时间戳的隐藏目录，例如 `..2026_10_26_...`。
- 更新时，kubelet 先写好一个新的时间戳目录，再**一次性把 `..data` 链接切过去**。所以应用永远不会读到写了一半的文件。
- `subPath` 挂载绕过了这套链接，直接绑定当时的那个文件，所以永远不会更新。
- 第二个机制：Spring 的 configtree 会跳过 `..` 开头的条目，所以练习 1 的 Secret 挂载不会把这些隐藏目录当成属性。

### 实验 2：环境变量是 Pod 创建那一刻的快照

```bash
oc exec deploy/veracode -- printenv CREED_JIRA_PROJECT
oc patch configmap veracode-config -p '{"data":{"CREED_JIRA_PROJECT":"CHANGED"}}'
oc exec deploy/veracode -- printenv CREED_JIRA_PROJECT     # 不变
oc delete pod -l app=veracode --wait=false; oc rollout status deploy/veracode
oc exec deploy/veracode -- printenv CREED_JIRA_PROJECT     # CHANGED：新 Pod 才读到
```

**解释**：
- 环境变量是 kubelet 在**创建容器时**从 ConfigMap 读取、写进进程环境的，之后与 ConfigMap 再无关系。
- 卷会被同步更新，环境变量不会。但对 Spring 来说两者效果一样，因为它只在启动时读一次（练习 2）。

### 实验 3：探针是谁发起的，失败了留下什么痕迹

```bash
oc set probe deploy/veracode --readiness --get-url=http://:18097/actuator/health/nope --period-seconds=5
oc get pod -l app=veracode -w                              # READY 0/1，但 RESTARTS 不变
oc get events --field-selector reason=Unhealthy --sort-by=.lastTimestamp | tail -3
oc get endpointslices -l kubernetes.io/service-name=veracode -o jsonpath='{..conditions}'; echo   # ready:false
oc set probe deploy/veracode --readiness --get-url=http://:18097/actuator/health/readiness --period-seconds=5
```

**观察 / 解释**：
- 探针由**节点上的 kubelet** 发起，请求打到 Pod IP 上，不经过 Service 和 Route。所以探针能通，不代表外部能访问。
- readiness 失败只会把 EndpointSlice 里这个端点的 `ready` 改成 false；liveness 失败才会重启容器。
- 把同样的错误路径用在 `--liveness` 上，`RESTARTS` 会递增，并很快进入 `CrashLoopBackOff`。
  重启的间隔按 10s、20s、40s……翻倍，上限 5 分钟。
- 这就是「liveness 里不要放下游依赖」的根本原因：一个下游故障，会被放大成全体 Pod 的重启风暴。

### 实验 4：容器看到的内存和 CPU 上限，JVM 是怎么读到的

```bash
oc exec deploy/veracode -- sh -c 'cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/cpu.max 2>/dev/null || echo "not cgroup v2"'
oc exec deploy/veracode -- java -XshowSettings:system -version 2>&1 | head -12
oc exec deploy/veracode -- java -XX:MaxRAMPercentage=75 -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E ' MaxHeapSize | ActiveProcessorCount '
```

**观察**：
- `memory.max` 正好等于 `limits.memory`（换算成字节）。`cpu.max` 形如 `max 100000`（没设 CPU limit），或 `50000 100000`（limit 为 500m）。
- `-XshowSettings:system` 显示 JVM 识别到的 cgroup 版本和内存上限。`MaxHeapSize` ≈ 内存上限 × 75%。
- **JVM 本身并不知道 Kubernetes 的存在**，它只读 cgroup 文件。limits 就是通过 cgroup 落到进程上的。
- 没设 `requests.cpu`/`limits.cpu` 时，`ActiveProcessorCount` 是节点的核数。GC 线程数、Tomcat 的默认线程数等都会照这个数取值。

### 实验 5：CPU limit 是「限流」而不是「杀进程」

```bash
oc set resources deploy/veracode --limits=cpu=200m,memory=768Mi --requests=cpu=100m,memory=384Mi
oc rollout status deploy/veracode
oc logs deploy/veracode | grep 'Started CreedResourceVeracodeApplication'          # 记下启动耗时
oc exec deploy/veracode -- cat /sys/fs/cgroup/cpu.stat | grep -E 'nr_periods|nr_throttled|throttled_usec'
oc set resources deploy/veracode --limits=cpu- --requests=cpu=100m                  # 去掉 CPU limit，再对比启动耗时
```

**观察**：
- 启动耗时明显变长，`nr_throttled` 很大：JVM 启动时多线程做类加载和 JIT，每个 100ms 周期只能用 20ms 的 CPU。
- 如果 startupProbe 的总时长（`failure-threshold × period`）不够，**这里会出现「limit 太小 → 启动慢 → 探针杀掉 → 再启动」的死循环**。
- 内存超限是 OOMKilled，CPU 超限只是变慢。很多团队对 Java 服务只设内存 limit，不设 CPU limit，原因就在这里。

### 实验 6：QoS 等级由 requests/limits 的写法决定

```bash
oc get pod -l app=veracode -o jsonpath='{.items[0].status.qosClass}'; echo         # Burstable
oc set resources deploy/veracode --requests=cpu=500m,memory=768Mi --limits=cpu=500m,memory=768Mi
oc get pod -l app=veracode -o jsonpath='{.items[0].status.qosClass}'; echo         # Guaranteed
```

**解释**：
- 每个容器都满足 requests == limits（CPU 和内存都要）→ Guaranteed；一个都没写 → BestEffort；其余 → Burstable。
- 节点内存紧张时，kubelet 按 BestEffort → Burstable → Guaranteed 的顺序驱逐 Pod。
- OOM killer 的打分（`oom_score_adj`）也照 QoS 设置，可以用 `oc exec ... -- cat /proc/1/oom_score_adj` 看到。

### 实验 7：两种「内存不够」——Java OOM 和容器 OOMKilled

```bash
# A：堆满了（JVM 自己抛错）。把堆压得很小：启动阶段或者导出 xlsx 时会抛 OutOfMemoryError
oc set env deploy/veracode JAVA_OPTS_APPEND="-Xmx48m"
oc logs deploy/veracode | grep -m1 OutOfMemoryError
# B：容器超限（内核杀进程）。堆上限高于容器上限。
#    注意不能用 MaxRAMPercentage=150：它只接受 0–100，JVM 会直接拒绝启动
oc set env deploy/veracode JAVA_OPTS_APPEND="-Xmx512m -XX:+AlwaysPreTouch"   # PreTouch 让堆在启动时就真正占用物理内存
oc set resources deploy/veracode --limits=memory=300Mi
oc get pod -l app=veracode -o jsonpath='{.items[0].status.containerStatuses[0].lastState}'; echo
oc set env deploy/veracode JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75"; oc set resources deploy/veracode --limits=memory=768Mi
```

**区别**：

| | Java `OutOfMemoryError` | 容器 `OOMKilled` |
|---|---|---|
| 谁触发 | JVM：堆满了 | Linux 内核：cgroup 内存超过 `memory.max` |
| 日志里 | 有堆栈 | **什么都没有**，进程直接消失 |
| 退出码 | 进程不一定退出 | 137（SIGKILL） |
| 怎么查 | `oc logs` | `oc describe pod` 里的 `Last State: Terminated, Reason: OOMKilled` |

### 实验 8：HPA 的计算公式

```bash
oc describe hpa veracode           # 看 current / target 以及 Events 里的 New size
```

**解释**：
- `期望副本数 = ceil(当前副本数 × 当前利用率 / 目标利用率)`，利用率是**相对 requests 的百分比**。
- 所以 `requests.cpu` 写得越小，同样的负载算出来的利用率越高，扩容越激进。
- 没写 `requests.cpu` 时，HPA 根本算不出利用率，`TARGETS` 一列显示 `<unknown>`。

### 检查点（机制）

- [ ] 看到了 `..data` 符号链接在 ConfigMap 更新前后的切换
- [ ] 能解释 readiness 失败和 liveness 失败分别改了哪个对象的哪个字段
- [ ] 在容器里读到 `memory.max`，并和 JVM 的 `MaxHeapSize` 对上
- [ ] 复现了 CPU limit 造成的启动变慢，并读出 `nr_throttled`
- [ ] 能区分 Java OOM 和 OOMKilled 的现象与排查入口

## 常见坑

- **liveness 依赖了外部系统**：Jira 一挂，所有 Pod 都被重启，故障被放大。liveness 只检查进程本身。
- **没有 readinessProbe**：滚动发布时，新 Pod 端口刚打开、Spring 上下文还没就绪就开始接流量，会出现几秒的 502/503。
- **requests 设得太大**：单节点的 CRC 上，后面的 Pod 会一直 Pending，`oc describe pod` 会显示 `Insufficient memory`。
- **actuator 跟着 Route 暴露出去**：`exposure.include: "*"` + `show-values: always` + 一个公网 Route = 谁都能读到全部配置和密钥。
  生产上把管理端口拆开（`management.server.port`），Service 和 Route 都不暴露它，探针直接打 Pod 的管理端口。
- **`spring.config.location` 当成 `additional-location` 用**：classpath 默认配置整份消失，表现为一堆奇怪的默认值。
- **ConfigMap 用 `subPath` 挂载**：改了 ConfigMap，Pod 里的文件永远不变，重启也没用——直到 Pod 被重新创建。
- **只看 `property.source` 判断来源**：值写成 `${CREED_FOO:fallback}` 时，source 永远显示 yml，真正的来源要去查占位符里的变量（练习 3）。

## 参考文档

- OCP 文档：*Building applications → Application health*（探针）
- OCP 文档：*Nodes → Working with pods → Automatically scaling pods with the horizontal pod autoscaler*
- Kubernetes 文档：*Configure Liveness, Readiness and Startup Probes*；*Resource Management for Pods and Containers*
- Spring Boot 文档：*Actuator → Kubernetes Probes*（https://docs.spring.io/spring-boot/reference/actuator/endpoints.html）
