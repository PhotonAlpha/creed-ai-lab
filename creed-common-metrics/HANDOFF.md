# creed-common-metrics — handoff

**Purpose** Shared **library** module (no application of its own) carrying the observability baseline
every other module includes.
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

Consumers: the four resource servers and `creed-gateway-partner`.

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
- Changing anything here changes **every consuming module at once** — there is no per-module override
  short of redefining the property.

## Open items

- No tests. The module is configuration plus one logger, so breakage only shows up at consumer
  startup.
- `ActuatorMetricsLogger` is always on when the `actuator` profile is active; there is no property to
  silence it short of a logger level change.
