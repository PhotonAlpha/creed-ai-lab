# creed-common-metrics — handoff

**Purpose** Shared **library** module (no application of its own) carrying the observability baseline
every other module includes, plus the generic request/response envelope and the `@CommandMapping`
command dispatch.
**Skill** none of its own — the conventions live in `creed-platform` ("Observability").

## Run

Nothing to run. It is a dependency:

```xml
<dependency>
    <groupId>com.creed</groupId>
    <artifactId>creed-common-metrics</artifactId>
</dependency>
```

…plus `spring.profiles.include: actuator` in the consuming module's `application.yml`.

## Current state

- **`src/main/resources/application-actuator.yml`** — the whole point of the module. Sets actuator
  exposure, Micrometer tags and histograms, the OTel exporter config (`otel.*`), and the resource
  attributes (`service.name`, `service.namespace`, `deployment.environment`,
  `service.instance.id = ${spring.application.name}:${server.port}`).
- **`ActuatorMetricsLogger`** — scheduled `@Async` job that reads the `MetricsEndpoint` and logs
  metric snapshots.
- **`config/ThreadPoolConfig`** — shared `ThreadPoolTaskExecutor`.
- **`logback-spring.xml`** — shared logging config including the OTel appender wiring.

Consumers: all five resource servers, `creed-author-server`, `creed-gateway-partner`, `creed-simple-metrics`.

### Generic envelope (`model/`)

`{"serviceResponseHeader": {requestContext, responseContext}, "serviceResponseBody": {profile, ...}}`.

- **`Generic*`** classes are generic all the way down; bounds use `<?>`, never raw types, so Jackson
  reading `GenericResponse.class` without a `TypeReference` resolves each field to its bound class.
- **`Default*`** (`DefaultResponse`, `DefaultResponseHeader`, `DefaultResponseBody`, `DefaultProfile`)
  pin the type arguments once. A new body is `class XxxResponseBody extends DefaultResponseBody` + its
  fields; return it inside a `DefaultResponse`. Only a client that must read it back typed needs a
  one-line `XxxResponse extends GenericResponse<DefaultResponseHeader, XxxResponseBody>`
  (`EnhancedResponseBody` / `EnhancedResponse` are the worked example).
- **Ad-hoc body fields**: `body.with("accounts", list)` — `@JsonAnyGetter/@JsonAnySetter`, flattened next
  to `profile`. Read back they are plain `Map`/`List`; promote to a field in a subclass to get types.
- Default types are built with **constructors, not static `of`**: a subclass `of` has the same erasure as
  the generic parent's and does not compile.

### `@CommandMapping` (`command/`, `command/web/`)

A controller method annotated `@CommandMapping(command = "accountCommand", validator = "accountValidator")`
never runs its body: `CommandMappingPostProcessor` (Spring's advising-post-processor pattern, like
`@Async`/`@Validated`) wraps the bean in a CGLIB proxy, and `CommandMappingInterceptor` takes the
`GenericRequest` argument, runs the `Validator` bean (false → `CommandValidationException`, 400), then
returns what the `Command` bean returns. Registered by `config/CommandMappingAutoConfiguration`
(`AutoConfiguration.imports`) — no `@Enable…`, no aspectjweaver, independent of the app's AOP settings.
`AccountCommandController` / `AccountCommand` / `AccountValidator` are the sample.

## Landmines

- **`spring.profiles.include: actuator` cannot be un-included from a profile-specific file.** Any
  profile that must run without telemetry has to neutralize it instead — set
  `otel.sdk.disabled: true` and provide `spring.application.name` / `server.port`, because the OTel
  config interpolates both and an unresolvable placeholder fails the whole context. See
  `creed-resource-env-matrix`'s `application-test.yml` for the worked example.
- **`service.instance.id` is pinned on purpose.** Without it the OTel SDK generates a random UUID per
  start and Grafana's instance dropdown fills with dead instances.
- **JVM/system/process Micrometer binders are disabled** (`management.metrics.enable.*: false`)
  because OTel's runtime telemetry already emits native `jvm_*` series; re-enabling them produces
  duplicate series with different labels in the collector.
- **A `@CommandMapping` method must be overridable** — package-private or wider, not `private` / `final` /
  `static`. Otherwise the CGLIB proxy cannot intercept it and the placeholder body (`return null`) is
  served silently; the post-processor fails startup on those modifiers instead. Same for a command or
  validator bean name that does not exist.
- **Command/validator are referenced by bean name.** In tests, `@Import(AccountCommand.class)` registers
  it under its FQCN, so the mapping misses — component-scan it (see `CommandControllerTest`).
- **The sample controllers ship in `com.creed.metrics`**, and `creed-author-server`,
  `creed-simple-metrics` and `creed-gateway-partner` scan all of `com.creed` — they all expose
  `GET` + `POST /account/list` (`AccountController`, `AccountCommandController`) and get the
  `accountCommand` / `accountValidator` beans.
- **`spring-boot-starter-web` is now a compile dependency** of this library; fine for today's consumers
  (all servlet), but it would flip a WebFlux app to servlet if one ever depends on this module.
- Changing anything here changes **every consuming module at once** — there is no per-module override
  short of redefining the property.

## Open items

- Tests cover only `@CommandMapping` (`command/web/CommandControllerTest`, plain web context + MockMvc,
  deliberately not `@SpringBootTest` so the OTel starter stays out). The actuator config still only
  breaks at consumer startup.
- `@CommandMapping.skipResponseHandler` is declared but not implemented — there is no response handler
  yet (e.g. filling `responseContext.traceId` / `responseDateTime` after the command).
- Decide whether the sample controllers/command/validator stay in the library (see Landmines).
- Request header field is `requesterContext`, response header is `requestContext` — confirm intended.
- `ActuatorMetricsLogger` is always on when the `actuator` profile is active; there is no property to
  silence it short of a logger level change.
