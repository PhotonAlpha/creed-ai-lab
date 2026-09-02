# creed-ai-lab — project conventions

Multi-module OAuth2 mesh: authorization server, two edge gateways (reactive + servlet), four resource
servers, config server, Camel gateway, reporting app, one React frontend, one Node mock server.

**Invariant conventions only** — this file loads in full every turn. Module-specific, changing, or
narrative content belongs in a skill or a `HANDOFF.md` (§6).

## 1. Stack

Java **21** source/target, built with **JDK 25** · Spring Boot **4.0.8** (Spring Framework 7,
Jakarta EE 11) · Spring Cloud **2025.1.3** · **Jackson 3** (`tools.jackson.*`, not
`com.fasterxml.jackson.*`) · Camel 4.22.0 (simple-metrics only) · Logbook 4.1.0 (simple-metrics only) · httpclient5 **5.6.4**
(pinned above Boot's 5.5.2 by direct `dependencyManagement`, for `httpclient5-observation`)
· Maven 3.9.16, local repo **`/Users/ethan/Desktop/workspace/repos`**
(not `~/.m2`) · frontend React 19 + TS + Vite 8 + **antd 5** + Ant Design Pro 2 + AntV G6 5 ·
PostgreSQL on the `creed-artifactory-db` container (env-matrix only; everything else is in-memory).

Versions live in the root `pom.xml`. Only pin in a module pom when the BOM doesn't manage the
artifact (e.g. POI in creed-report).

Two modules are **Node, not Maven**, and are absent from the root `pom.xml`: `creed-env-matrix-design`
(the frontend) and `creed-mock-buddy` (Fastify 5 + TS, Node ≥22). Both are driven with `npm`.

## 2. Build & run

```bash
mvn -q -pl <module> -am -DskipTests install
mvn -pl <module> test
mvn -pl <module> spring-boot:run -Dspring-boot.run.profiles=primary \
  -Dspring-boot.run.workingDirectory="$PWD"        # run from the REPO ROOT
```

- **`spring-boot:run`, not `package`** — plain `package` produces no executable jar here.
- **`-Dspring-boot.run.workingDirectory="$PWD"` is mandatory** for modules whose SSL bundle uses
  `file:${creed.rootPath}`; the plugin otherwise runs in the module dir and the keystore path
  resolves to nothing. Config-server and gateway `cloud` ship certs on the classpath and don't need it.
- mTLS material: `.support/scripts/CA-Generation.sh` → `.support/scripts/pki/`. A new HTTPS service
  must be added to that script's `SERVICES` array.
- Monitoring: `docker compose -f monitoring/docker-compose.yml up -d` (Grafana 3000, Prometheus 9090,
  collector 4318/8889, Tempo 3200, Loki 3100).

## 3. Modules & ports

| Module | Port(s) | Path | Notes |
|---|---|---|---|
| `creed-author-server` | 9000 | `/auth-server` | HTTP. **Issuer of record** |
| `creed-config-server` | 8443 | `/config-server` | HTTPS, Basic auth, `{cipher}` |
| `creed-gateway` | 8080 | — | HTTPS, **reactive** |
| `creed-gateway-partner` | 8095 | — | HTTPS, **servlet** |
| `creed-simple-metrics` | 8096 | `/camel/*` | HTTPS, Camel; **pull-mode** metrics |
| `creed-report` | 9100 | `/report` | HTTP, standalone (outside the mesh) |
| `creed-resource-catalog` | 18081 / 18082 | `/api/catalog` | primary / secondary |
| `creed-resource-order` | 18091 / 18092 | `/api/order` | primary / secondary |
| `creed-resource-payment` | 18093 / 18094 | `/api/payment` | primary / secondary |
| `creed-resource-env-matrix` | 18095 / 18096 | `/api/env-matrix` | + `dev` = HTTP 3001 |
| `creed-env-matrix-design` | 5173 | — | Vite, proxies `/api` → `VITE_API_TARGET` (`.env`: 18095) |
| `creed-mock-buddy` | 18100 | — | HTTP, **Node/Fastify**; YAML mock server, standalone |
| `creed-common-metrics` | — | — | library: `application-actuator.yml` + OTel helpers |

Resource servers and gateways run `primary` / `secondary` (local two-instance) or `cloud`
(config-server driven). OIDC issuer everywhere is
`${CREED_AUTH_ISSUER:http://127.0.0.1:9000/auth-server}` and **must match the auth server exactly,
context-path included**.

## 4. Code style

**Java** — packages `com.creed.<module>`; layers `api/` (+`api/dto/`), `service/`, `domain/`,
`config/`, `web/`. DTOs are `record`s, entities are classes with `@Getter/@Setter`. `@Slf4j` for
logging. Config via `@Value`/`@ConfigurationProperties`, never hard-coded. Comments explain **why**,
not what — most existing ones document a landmine someone already hit.

**Lombok** is a root dependency *and* is declared in the root `maven-compiler-plugin`
`annotationProcessorPaths` — since JDK 23 javac ignores processors found only on the classpath. Don't
remove that block; the symptom is `cannot find symbol: variable log`.

**Config** — every externally-varying value gets `${CREED_FOO:fallback}`. Profile files are
`application-<profile>.yml`. `spring.profiles.include: actuator` pulls in `creed-common-metrics` and
**cannot be un-included from a profile-specific file**.

**Frontend** — one root `ConfigProvider`, design tokens over CSS, never `.ant-*` selectors, and query
`antd info <Component> --format json --version 5.29.3` before writing component code.

**Tests** — JUnit 5 + AssertJ; standalone MockMvc where no context is needed, `@SpringBootTest` where
it is. A test must never require Docker, the PKI, or a reachable issuer.

## 5. Cross-cutting landmines

Details in the `creed-platform` skill.

- **`lb://` scheme leak** — `@LoadBalanced` + `lb://service-id` throws `invalid URI scheme lb`. Use
  `https://service-id/...`, or resolve instances explicitly.
- **`ClientHttpRequestInterceptor` order is List insertion order, not `@Order`.**
- **Boot's `SslMeterBinder` eagerly opens every declared SSL bundle at startup** and has no disable
  property — a declared-but-missing keystore fails startup even with HTTPS off.
- Connection pools: `@Bean(destroyMethod="close")` + `setConnectionManagerShared(true)`.
- **Don't guess metric names** — read them from `localhost:8889/metrics`.

### Spring Boot 4 module split (the upgrade's recurring trap)

Boot 4 broke the monolithic starters into per-technology modules, so things that used to arrive
transitively now need declaring. Symptom is always the same: `package … does not exist`, or a bean
that silently isn't auto-configured.

- **`spring-boot-starter-actuator` no longer brings tracing.** Add `spring-boot-micrometer-tracing`
  or nothing injects `io.micrometer.tracing.Tracer` and the context fails at startup.
- **`spring-boot-starter-test` no longer bundles MockMvc / TestRestTemplate.** Add
  `spring-boot-starter-webmvc-test` and/or `spring-boot-resttestclient` (or
  `spring-boot-starter-test-classic` for the old all-in-one behaviour).
- **`spring-boot-starter-web` no longer brings RestTemplate/RestClient.** Add
  `spring-boot-starter-restclient` for `RestTemplateBuilder` and the
  `org.springframework.boot.http.client` builders.
- **Classes moved**: `…boot.DefaultBootstrapContext` → `…boot.bootstrap.…`;
  `…boot.web.embedded.tomcat.TomcatServletWebServerFactory` → `…boot.tomcat.servlet.…`;
  `…boot.web.client.RestTemplateBuilder` → `…boot.restclient.…`;
  `…boot.actuate.metrics.MetricsEndpoint` → `…boot.micrometer.metrics.actuate.endpoint.…`;
  `…boot.actuate.health.HealthEndpoint` → `…boot.health.actuate.endpoint.…`;
  `ClientHttpRequestFactorySettings` → `HttpClientSettings`. To find one, index the jars rather than
  guess: `unzip -l <jar> | grep <SimpleName>.class` across `~/repos/org/springframework/**`.
- **SSL bundles now have a THIRD eager reader.** On top of `SslMeterBinder` (whose autoconfiguration
  is renamed `…micrometer.metrics.autoconfigure.ssl.SslMetricsAutoConfiguration`), the actuator's
  `SslInfo` opens every declared bundle. A declared-but-missing keystore still fails startup — the
  test/dev profiles need `management.info.ssl.enabled: false` as well as the health switch and the
  autoconfigure exclusion.
- **Jackson 3 is the default** and its exceptions are **unchecked**: `JsonProcessingException` →
  `tools.jackson.core.JacksonException`, so `catch (IOException)` around mapper calls becomes dead
  code and fails compilation. Mappers are immutable — configure via `JsonMapper.builder()`, and drop
  `JavaTimeModule` (java.time is built in). Jackson 2 is still BOM-managed if a third-party library
  needs it.

## 6. Where the rest of the docs live

| Kind | Location |
|---|---|
| Deep module knowledge, gotchas, rationale | `.claude/skills/<name>/SKILL.md` — **load the skill before working on a module** |
| Current state, run recipe, open items | `<module>/HANDOFF.md` |
| User-facing docs | `<module>/README.md` |

Skills cross-reference with `[[skill-name]]`; `creed-platform` is the shared one — start there for
SSL/mTLS, load balancing, HTTP clients, or observability.

**Keep in sync**: behaviour change → that module's `HANDOFF.md`; new gotcha or design rule → its
skill; stack/ports/commands/style change → this file.
