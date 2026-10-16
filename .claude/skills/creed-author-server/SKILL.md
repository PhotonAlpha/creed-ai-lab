---
name: creed-author-server
description: The creed-author-server module — Spring Authorization Server (OAuth2/OIDC issuer) on port 9000 context-path /auth-server, plus a mock downstream/gateway RestTemplate+RestClient over mTLS, async/thread-pool demo, and OpenTelemetry logging. Use when working on OAuth2 client registration, token/JWK config, the issuer URI, the mTLS gateway client, or thread-pool/async/OTel concerns in this module.
---

# creed-author-server

OAuth2/OIDC **Authorization Server** — the issuer the whole mesh trusts. Port `9000`, context-path `/auth-server`. Issuer = `${CREED_AUTH_ISSUER:http://127.0.0.1:9000/auth-server}` and MUST equal every resource server's / gateway's `jwt.issuer-uri` (the `.well-known/openid-configuration` is served at `<issuer>/.well-known/openid-configuration`). Plain HTTP (no `TomcatHttpsConfiguration` here).

## Layout (`com.creed.auth`)
- `config/AuthorizationServerConfiguration` — Spring Authorization Server wiring; `InMemoryRegisteredClientRepository` of `RegisteredClient`s, `EnableWebSecurity`, JWK source. This is where OAuth2 clients/scopes/grant types live.
- `config/GatewayRestTemplateConfiguration` — the reference mTLS HTTP client config: `PoolingHttpClientConnectionManager` as `@Bean(destroyMethod="close")` + `setConnectionManagerShared(true)`, `creed-gateway-trust` SSL bundle, `@ConfigurationProperties("creed.gateway")` props, and a `PoolingHttpClientConnectionManagerMetricsBinder` (tag `httpclient=creed-gateway`). Canonical pattern other modules copy.
- `config/ThreadPoolConfig` + `service/AsyncService` — `ThreadPoolTaskExecutor` + `@Async` demo.
- `controller/MockRestController` — mock endpoints (uses `-parameters` so `@RequestParam`/`@PathVariable` bind by name).
- `config/OpenTelemetryLogAppenderConfig` + `metrics/ActuatorMetricsLogger` — OTel log appender + actuator metric logging.
- `json/JacksonUtils`, `controller/dto/HeavyResponse`.

## Notes
- See [[creed-platform]] for the mTLS/SSL-bundle and HttpClient 5 conventions this module exemplifies.
- Config-server import is present but commented out in `application.yml` (runs standalone by default).

## Spring Boot 4 note

Spring Authorization Server is now **7.x**, folded into `spring-security-config`:
- `org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration` → `org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration`
- `…config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer` → `org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer`
- the static `OAuth2AuthorizationServerConfigurer.authorizationServer()` factory is gone — use the public constructor.

`json/JacksonUtils` is on **Jackson 3**: the mapper is built once via `JsonMapper.builder()` (Jackson 3 mappers are immutable), `JavaTimeModule` is dropped (java.time is native), and the `catch (IOException)` blocks are now `catch (JacksonException)` because Jackson 3 exceptions are unchecked.
