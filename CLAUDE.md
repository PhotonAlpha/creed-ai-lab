# creed-ai-lab — project conventions

Multi-module OAuth2 mesh: authorization server, two edge gateways (reactive + servlet) plus a
standalone tunnel proxy, five resource servers, config server, Camel gateway, two reporting apps,
one React frontend, one Node mock server.

**Invariant conventions only** — this file loads in full every turn. Module-specific, changing, or
narrative content belongs in a skill or a `HANDOFF.md` (§6).

## 1. Stack

Java **21** source/target, built with **JDK 25** · Spring Boot **4.1.1** (Spring Framework 7,
Jakarta EE 11, Tomcat 11) · Spring Cloud **2025.1.3** · **Jackson 3** (`tools.jackson.*`; annotations stay
`com.fasterxml.jackson.annotation`) · Camel 4.23.0 (simple-metrics only) · Logbook 4.2.0 (simple-metrics only)
· JasperReports 6.21.3 (jasper-report only)
· RefactoringMiner 3.1.6 + JGit 6.10 + JDT 3.39 + Thymeleaf (migration-risk only; 3.1.x is Java 23 bytecode → run on JDK 25) · httpclient5 5.6.4 (Boot-managed)
· Maven 3.9.16, local repo **`/Users/ethan/Desktop/workspace/repos`**
(not `~/.m2`) · frontend React 19 + TS + Vite 8 + **antd 5** + Ant Design Pro 2 + AntV G6 5 ·
PostgreSQL on the `creed-artifactory-db` container (env-matrix and migration-risk; everything else is in-memory).

Versions live in the root `pom.xml`. Only pin in a module pom when the BOM doesn't manage the
artifact (e.g. POI in creed-report).

Three modules are **Node, not Maven**, and are absent from the root `pom.xml`: `creed-env-matrix-design`
(the frontend) and `creed-mock-buddy` (Fastify 5 + TS, Node ≥22), driven with `npm`; and
`creed-backstage` (Backstage developer portal, Yarn 4 via `corepack yarn` — no global yarn here).

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
- **Scratch files go in `tmp/` at the repo root**, never `/tmp` — test scripts, curl output, heap/log
  dumps, generated exports. The directory is git-ignored (`tmp/.gitignore`) and inside the working
  tree, so writing there needs no approval prompt.
- Monitoring: `docker compose -f monitoring/docker-compose.yml up -d` (Grafana 3000, Prometheus 9090,
  collector 4318/8889, Tempo 3200, Loki 3100).

## 3. Modules & ports

| Module | Port(s) | Path | Notes |
|---|---|---|---|
| `creed-author-server` | 9000 | `/auth-server` | HTTP. **Issuer of record** |
| `creed-config-management/creed-config-server` | 8443 | `/config-server` | HTTPS, Basic auth, `{cipher}`; native repo in six layers common → type → env-group → env → app → app-env (`config-repo/README.md`); `-pl creed-config-management/creed-config-server` |
| `creed-gateway` | 8080 | — | HTTPS, **reactive** |
| `creed-gateway-partner` | 8095 | — | HTTPS, **servlet** |
| `creed-gateway-proxy` | 8088 / 8089 | — | HTTP, **reactive**, standalone; Env Matrix front door: built frontend from `static/`, **owns the Splunk broker** (moved from the Node BFF; secrets at startup or `ENC(…)` in yml, never plain text), `/api/**` → env-matrix; profile `tunnel` = proxy onto `ssh -R` ports; actuator on loopback 8089 |
| `creed-simple-metrics` | 8096 | `/simple` (+ `/camel/*`) | HTTPS, Camel; **pull-mode** metrics; httpd forwards `/simple/*`; Camel tutorial (8 APIs, `docs/camel-tutorial/`, Swagger UI `/simple/swagger-ui.html`) |
| `creed-report` | 9100 | `/report` | HTTP, standalone (outside the mesh) |
| `creed-jasper-report` | 9110 | `/jasper-report` | HTTP, standalone; **JasperReports jrxml** twin of creed-report's approval-status PDF (+ xlsx/csv/html) |
| `creed-resource-catalog` | 18081 / 18082 | `/api/catalog` | primary / secondary |
| `creed-resource-order` | 18091 / 18092 | `/api/order` | primary / secondary |
| `creed-resource-payment` | 18093 / 18094 | `/api/payment` | primary / secondary |
| `creed-resource-env-matrix` | 18095 / 18096 | `/api/env-matrix` | + `dev` = HTTP 3001 |
| `creed-resource-veracode` | 18097 | `/api/veracode` | HTTP (HTTPS with profile `ssl`, one-way TLS), stateless; Veracode report from Confluence → Jira lookup/create (`@HttpExchange`) → xlsx; Confluence + Jira mocked in-process (`veracode.mock.enabled`) |
| `creed-resource-migration-risk` | 18098 | `/api/migration-risk` | HTTP; migration sync risk: RefactoringMiner on repo1@baseline→HEAD and baseline→repo2 (git branches or plain folders; repo2 may be two repos, merged) → HIGH/MEDIUM/LOW items, config keys compared globally; saved per release in Postgres `migration_risk` (`h2` profile without Docker) for SAFE/ATTENTION review; Thymeleaf AST-diff page `/api/migration-risk/view/diff`; repos only under `migration-risk.allowed-roots` |
| `creed-env-matrix-design` | 5173 / 3002 | — | Vite, proxies `/api` → `VITE_API_TARGET` (`.env`: 18095) and the Splunk API → `VITE_SPLUNK_TARGET` (`.env`: creed-gateway-proxy 8088); legacy Node BFF `npm run bff` on 3002 |
| `creed-mock-buddy` | 5173 / 4000 | — | HTTP / HTTPS (borrows `creed-gateway` cert), **Node/Fastify**; YAML mock server, standalone |
| `creed-backstage` | 3003 / 7007 | — | **Backstage**: Software Catalog (`catalog/**/catalog-info.yaml`) + Swagger UI; one `catalog/<module>/` per functional area; multi-file OpenAPI → Redocly bundle `dist/openapi.yaml` (committed, `$text`), `yarn openapi:build [module]` |
| `.support/httpd` (docker) | 6666 / 16666 / 9443 | `/mod_cluster_manager` | Apache + mod_proxy_cluster: MCMP over **mTLS** / browser status page (loopback, no client cert; 666x is browser-blocked) / HTTPS traffic; `creed-simple-metrics` registers here |
| `creed-common-metrics` | — | — | library: `application-actuator.yml` + OTel helpers; generic request/response envelope + `@CommandMapping` (controller → validator → command, AOP auto-config) |

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
- **`flyway-core` alone runs no migration.** Flyway's auto-configuration moved to `spring-boot-flyway`: declare
  `spring-boot-starter-flyway`. Nothing fails — the schema just stops moving (creed-resource-env-matrix still
  declares only `flyway-core` as of 2026-10-25).
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
- **Jackson 3 also changed deserialization defaults** (`FAIL_ON_NULL_FOR_PRIMITIVES` on: a body that
  omits a `boolean` field is a 400). Every module sets `spring.jackson.use-jackson2-defaults: true`; a new
  module must too. `JsonNode.asText()`/`findValuesAsText` → `asString()`/`findValuesAsString`.
- **Spring Framework 7 `HttpHeaders` is not a `Map`**: `containsKey` → `containsHeader`.
- **Tomcat 11**: anything versioned to the servlet container must follow (mod_cluster →
  `mod_cluster-container-tomcat-11.0`).
- **Stale classes hide a broken upgrade**: maven-compiler skips modules whose sources did not change,
  so a BOM bump "compiles" against the old jars. Verify an upgrade with `clean install`.

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
