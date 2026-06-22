---
name: creed-platform
description: Cross-cutting conventions and hard-won gotchas for the creed-ai-lab multi-module Spring Boot 3.5 / Spring Cloud 2025.0.2 OAuth2 mesh — mTLS PKI & SSL bundles, the programmatic HTTPS listener pattern, Spring Cloud LoadBalancer + SimpleDiscoveryClient (the lb:// scheme-leak), RestClient/Apache HttpClient 5 wiring (pool, timeouts, metrics, lifecycle), Lombok-on-JDK23+, ports map, and local run/test recipes (the OIDC stub). Use for ANY work touching SSL/mTLS, load balancing, HTTP clients, build/run, or when a module-specific skill needs shared context.
---

# creed-ai-lab platform conventions

Multi-module Maven project. Root pom: `java.version=21` (but build JDK is 25), `spring-boot.version=3.5.14`, `spring-cloud.version=2025.0.2`. Modules: `creed-author-server`, `creed-gateway`, `creed-gateway-partner`, `creed-config-server`, `creed-report`, `creed-resource/{creed-resource-catalog,creed-resource-order}`.

## Ports & identities

| Service | Port(s) | Context-path | Transport |
|---|---|---|---|
| creed-author-server | 9000 | `/auth-server` | HTTP (OAuth2/OIDC issuer) |
| creed-config-server | 8443 | `/config-server` | HTTPS (also probes 8888 default) |
| creed-gateway | 8080 | — | HTTPS, reactive (webflux) |
| creed-gateway-partner | 8095 | — | HTTPS, servlet |
| creed-report | 9100 | `/report` | HTTP |
| creed-resource-catalog | 8081 (primary) / 8082 (secondary) | — | HTTPS |
| creed-resource-order | 8091 (primary) / 8092 (secondary) | — | HTTPS |

Resource & gateway services run profiles `primary`/`secondary` (local two-instance) or `cloud` (config-server driven). OIDC issuer everywhere: `${CREED_AUTH_ISSUER:http://127.0.0.1:9000/auth-server}` — must equal the auth server's `issuer` exactly (incl. context-path).

## mTLS PKI

`.support/scripts/CA-Generation.sh` mints PKCS12 stores into `.support/scripts/pki/`:
- root CA `creed-CA-global-root` → intermediate `creed-CA-Public-RSA` → per-service leaf certs.
- Per identity TWO certs: `<svc>` (serverAuth, inbound) and `<svc>-CLI` (clientAuth, outbound).
- Every `<x>-truststore.p12` = {root, intermediate} → CA-anchored mutual trust across the mesh.
- `STOREPASS=changeit` for all. PKCS12 aliases are **lowercased** (e.g. `creed-gateway-partner-cli`).

## SSL bundles (Spring Boot 3)

Define `spring.ssl.bundle.jks.<name>` with PKCS12 keystore + truststore. Convention: `creed.rootPath` points at the pki dir; locations like `file:${creed.rootPath}/<svc>-keystore.p12`.
- **Gotcha**: `file:./...` / `file:${creed.rootPath}` is relative to the **process working dir**. `mvn spring-boot:run` uses the *module* dir, so add `-Dspring-boot.run.workingDirectory=<repoRoot>`, or use `classpath:certs/...` (config-server/gateway cloud profile ship certs on the classpath).
- A bundle is only materialized when something reads it (`SslBundles.getBundle`), so a defined-but-unused bundle won't fail startup.

## Programmatic HTTPS listener — `TomcatHttpsConfiguration`

Present (identical) in config-server, gateway, gateway-partner, resource-catalog, resource-order. NOT in author-server/report.
- `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` (or the webflux variant in the reactive gateway) sets `Ssl` programmatically instead of `server.ssl.*`.
- Binds to the bundle named by `${creed.https.bundle}`; gated by `@ConditionalOnProperty("creed.https.enabled", matchIfMissing=true)`; `Ordered.LOWEST_PRECEDENCE`; `clientAuth=NONE`.
- To add HTTPS to a servlet module: copy this class + set `creed.https.bundle` in yml. (That's all `creed-gateway-partner` needed.)

## Lombok on JDK 23+

Since JDK 23 javac does **not** auto-run annotation processors found only on the classpath. Lombok is declared in the root pom's `maven-compiler-plugin` `<annotationProcessorPaths>` (version `${lombok.version}`). Symptom if missing: `cannot find symbol: variable log` for `@Slf4j`. Don't remove that block.

## Spring Cloud LoadBalancer (servlet) — the `lb://` scheme leak

Runtime version is **4.3.2** (BOM-pinned; ignore stray 5.0.1 jars in the repo). Static registry via `spring.cloud.discovery.client.simple.instances.<service>: [{uri: https://host:port}]`.

**The trap**: `@LoadBalanced` + `lb://service-id` throws `invalid URI scheme lb`. Root cause: commons 4.3.2 `DefaultServiceInstance.getScheme()` is always `null` (no scheme field), so `LoadBalancerUriTools.reconstructURI` falls back to `computeScheme`, which only knows http/https/ws/wss and keeps the original `lb` scheme.

**Fixes (any one):**
1. Use `https://service-id/...` instead of `lb://...` — `computeScheme` keeps `https`. Simplest; current partner approach.
2. Register a `LoadBalancerRequestTransformer` that rewrites the scheme from `instance.isSecure()` (runs after host/port already rewritten). `@Order` works on transformers.
3. Explicit `loadBalancerClient.choose(serviceId)` in code, build the URI yourself from `instance` (the reactive gateway's `AggregateController` does this).

**Servlet supplier with health checks** (`ServiceInstanceListSupplier.builder()`): use the BLOCKING variants — `.withBlockingDiscoveryClient().withBlockingHealthChecks(restClient|restTemplate).withCaching().build(ctx)`. The non-`Blocking` ones need WebClient/reactive. Register via `@LoadBalancerClients(defaultConfiguration = X.class)`; class `X` must have **no** `@Configuration`/`@Component` stereotype (it's imported into each LB child context — a stereotype would leak it into the main scan).

**Health check props**: `spring.cloud.loadbalancer.health-check.{path.<svc>, interval, initial-delay}`. First request batch may all hit one instance until the first health-check cycle confirms the others.

**Logging the health probe**: the stock blocking alive-probe (`ServiceInstanceListSupplierBuilder#blockingHealthCheckServiceInstanceListSupplier`) wraps the call in `Mono.defer` and **swallows everything** in `catch (Exception ignored) { return Mono.just(false) }` — a down instance is silently dropped. To observe it, don't call `.withBlockingHealthChecks(...)`; instead `.with((ctx, delegate) -> new <Subclass>(delegate, ctx.getBean(LoadBalancerClientFactory.class), aliveFn))`, where the subclass `extends HealthCheckServiceInstanceListSupplier` and overrides `protected Mono<Boolean> isAlive(ServiceInstance)` to log the full `ServiceInstance` then `super.isAlive(...).doOnNext(...)`. Supply your own `aliveFn` (a faithful copy of the framework lambda) to log the HTTP status/latency and the otherwise-ignored exception. `isAlive` short-circuits to `Mono.just(true)` (no HTTP) when a service's health-check path is empty. Reference impl: `creed-gateway-partner` `PartnerLoadBalancerConfiguration`.

## RestClient / RestTemplate interceptor ordering

`ClientHttpRequestInterceptor`s run in **List insertion order** (first = outermost). There is **no `@Order` sorting**. Control via insertion order or `RestClient.Builder.requestInterceptors(Consumer<List>)`. The `@LoadBalanced` post-processor **appends** the LB interceptor: add yours in the `@Bean RestClient.Builder` → outer than LB; add after retrieving the post-processed builder (in the `RestClient` build step) → inner than LB (sees the resolved `host:port`). (`@Order` *does* sort `LoadBalancerRequestTransformer`.) Full writeup: `creed-gateway-partner/README.md`.

## Apache HttpClient 5 (Boot-managed 5.5.2)

Factory: `new HttpComponentsClientHttpRequestFactory(closeableHttpClient)`. Build the client from a `PoolingHttpClientConnectionManager`:
- TLS: `PoolingHttpClientConnectionManagerBuilder.create().setTlsSocketStrategy(new DefaultClientTlsStrategy(sslBundle.createSslContext()))`.
- Pool: `setMaxConnTotal` / `setMaxConnPerRoute` / `setDefaultConnectionConfig(ConnectionConfig with connect+socket timeouts)`.
- `RequestConfig`: `setConnectionRequestTimeout` (pool lease) + `setResponseTimeout`; on `HttpClients.custom().setDefaultRequestConfig(...)`.
- **Lifecycle**: declare the pool as `@Bean(destroyMethod = "close")` and set `.setConnectionManagerShared(true)` on the client so Spring solely owns the pool (no double-close). Convention from `creed-author-server` `GatewayRestTemplateConfiguration`.
- Wrap in `BufferingClientHttpRequestFactory` only when an interceptor must re-read the response body (e.g. audit logging).

**Pool metrics**: use the Micrometer built-in `PoolingHttpClientConnectionManagerMetricsBinder(cm, "<name>")` (it IS a `MeterBinder`; the name becomes the `httpclient` tag). Metrics: `httpcomponents.httpclient.pool.total.{max,connections[state],pending}` + `route.max.default`. Do **not** hand-roll gauges. Expose via `management.endpoints.web.exposure.include: health,info,metrics`.

## Observability — OTel → Prometheus/Grafana

Local LGTM-ish stack in `monitoring/docker-compose.yml` (network `creed-monitoring`): **otel-collector-contrib** (OTLP in on `4317` gRPC / `4318` HTTP; Prometheus exporter scrape on `8889`; health on `13133`), **Prometheus** `9090`, **Grafana** `3000` (provisioned datasource + dashboards from `monitoring/provisioning/` and `monitoring/dashboards/`), **Tempo** `3200` (traces), **Loki** `3100` (logs). Collector pipeline config: `monitoring/otel-collector-config.yaml` (metrics → prometheus exporter with `resource_to_telemetry_conversion: enabled` so resource attrs become labels; traces → Tempo; logs → Loki).

Apps export via `opentelemetry-spring-boot-starter` (config in each module's `application-actuator.yml`, profile `actuator`, included by resource/partner modules). Key settings: `otel.exporter.otlp.endpoint=${CREED_OTEL_ENDPOINT:http://localhost:4318}`, `otel.instrumentation.micrometer.enabled=true` (bridges the whole Micrometer registry to OTLP), and `management.metrics.enable.{jvm,system,process}=false` (OTel runtime-telemetry already emits native `jvm_*` — disabling the Micrometer binders avoids duplicate series). `service.instance.id` is pinned to `${name}:${port}` (else a random UUID per restart piles up dead instances in Grafana).

**Metric naming after the bridge** (Micrometer dot-name → OTel → collector Prometheus exporter): dots→underscores, unit suffix appended, counters get `_total`. So `httpcomponents.httpclient.pool.*` → `httpcomponents_httpclient_pool_total_{max,connections,pending}` (label `state`=available/leased; label `httpclient`=pool name) + `_route_max_default`. Tomcat (needs `server.tomcat.mbeanregistry.enabled=true`) → `tomcat_threads_{busy,current,config_max}`, `tomcat_connections_{current,keepalive_current,config_max}` (label `name`=connector e.g. `https-jsse-nio-8095`), `tomcat_servlet_request_count_total` / `tomcat_servlet_error_total` / `tomcat_servlet_request_max_seconds` (label `name`=servlet e.g. `dispatcherServlet`). OTel-native: `jvm_memory_used_bytes`, `jvm_thread_count`, `http_server_request_duration_seconds_*`. Resource attrs surface as labels `service_name`, `service_instance_id` (Grafana dashboard vars `$service`/`$instance` key off these; collector also maps them to `job`/`instance`).

**Don't guess names — read them live**: `curl -s localhost:8889/metrics | grep <prefix>` (collector scrape), and validate PromQL via `curl -s localhost:9090/api/v1/query --data-urlencode 'query=...'`. Dashboards are provisioned, so editing `monitoring/dashboards/*.json` + `docker compose -f monitoring/docker-compose.yml restart grafana` reloads them (verify uid via `/api/dashboards/uid/<uid>`). Main dashboard: `otel-jvm-statistics.json` (uid `otel-jvm-statistics`).

## Local run & test recipes

- Local Maven repo is `/Users/ethan/Desktop/workspace/repos` (not `~/.m2`). Build JDK 25, Maven 3.9.16.
- `mvn -q -pl <module> spring-boot:run -Dspring-boot.run.profiles=primary -Dspring-boot.run.workingDirectory="$PWD" -Dspring-boot.run.jvmArguments="-D..."`. (Plain `package` does NOT produce an executable jar — use `spring-boot:run`.)
- **OIDC stub for local resource-server startup**: `CachedJwtDecoderConfiguration` eagerly resolves `NimbusJwtDecoder.withIssuerLocation(issuer)` at startup → needs `spring.security.oauth2.resourceserver.jwt.issuer-uri` reachable (only set in `cloud` profile). For `primary`/`secondary`, pass a dummy issuer pointing at a tiny python `http.server` that serves `/.well-known/openid-configuration` `{issuer, jwks_uri}`. Security is `permitAll` (oauth2 resource server is commented out in `SecurityConfiguration`), so calls need no token; `@AuthenticationPrincipal Jwt` is null-safe in controllers.
- **IntelliJ HTTP Client**: `.http` files in `.support/http-client/`; ignore self-signed certs by running the `local` environment in `http-client.private.env.json` (`SSLConfiguration.verifyHostCertificate:false`).
