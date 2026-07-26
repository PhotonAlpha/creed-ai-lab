# creed-author-server — handoff

**Purpose** OAuth2/OIDC Authorization Server — the issuer the whole mesh trusts.
**Skill** `creed-author-server` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-author-server spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"
```

HTTP `9000`, context-path `/auth-server`. No HTTPS listener here. Discovery document:
`http://127.0.0.1:9000/auth-server/.well-known/openid-configuration`.

## Current state

- Spring Authorization Server wired in `config/AuthorizationServerConfiguration` with an
  **`InMemoryRegisteredClientRepository`** — clients, scopes and grant types are code, not config.
- `config/GatewayRestTemplateConfiguration` is the **canonical mTLS HTTP-client pattern** other
  modules copy: pooled Apache HttpClient 5 as `@Bean(destroyMethod="close")` with
  `setConnectionManagerShared(true)`, the `creed-gateway-trust` bundle, `@ConfigurationProperties`
  tunables, and a Micrometer pool binder tagged `httpclient=creed-gateway`.
- `config/ThreadPoolConfig` + `service/AsyncService` — `@Async` / thread-pool demo.
- `controller/MockRestController` — mock downstream endpoints.
- OTel log appender via `config/OpenTelemetryLogAppenderConfig`.

## Landmines

- **The issuer is load-bearing.** `${CREED_AUTH_ISSUER:http://127.0.0.1:9000/auth-server}` must equal
  every resource server's and gateway's `jwt.issuer-uri` **including the context-path**. A mismatch
  surfaces as opaque JWT validation failures downstream, not as an error here.
- Compiled with `-parameters` (root pom) — `MockRestController` relies on it for `@RequestParam`
  binding without explicit names.

## Open items

- `spring.config.import` for the config server is **present but commented out** in `application.yml`;
  the module runs standalone by default. Decide whether it should join the `cloud` story like the
  resource servers.
- Registered clients are in-memory, so every restart resets issued state. Fine locally; a real
  deployment needs a persistent `RegisteredClientRepository`.
