---
name: env-inspector
description: Requirements and design for the creed-report "Environment Inspector" feature — standalone ConfigurableEnvironment inspection that replays Spring Boot's config-loading pipeline (profiles.active / config.location / config.additional-location) without beans or a web server, exposed via REST APIs and Thymeleaf pages, including effective-properties rendering to YAML/.properties. Use when extending, debugging, or reviewing this feature in the creed-report module.
---

# Environment Inspector (creed-report)

Inspect exactly which properties Spring Boot would activate at startup for a given set of
launch arguments — by assembling a throw-away `ConfigurableEnvironment` and replaying the
real config-loading pipeline on it. **No beans are created and no web server is started**
for the inspection itself (the inspection runs inside the already-running creed-report app).

## Module layout

- `service/EnvironmentInspectionService.java` — all logic (build env, read props, render).
- `controller/EnvironmentInspectionController.java` — `@RestController`, JSON APIs.
- `controller/EnvironmentViewController.java` — `@Controller`, Thymeleaf pages.
- `model/` — `EnvironmentSnapshot`, `PropertySourceView`, `PropertyEntry`, `RenderedEnvironment` (records).
- `templates/environment.html`, `templates/environment-rendered.html`.
- Context path is `/report` (e.g. `http://localhost:9100/report/...`). Spring Boot 3.5.x.

## Requirements (in implementation order)

### R1 — Standalone environment inspection (core)
- Manually `new StandardEnvironment()` — do **not** build a bean container or start a web server.
- Seed the launch arguments (`spring.profiles.active`, `spring.config.location`,
  `spring.config.additional-location`) as the highest-priority `MapPropertySource`
  (`addFirst`, source name `manualLaunchArguments`).
- Replay the real pipeline with
  `ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), new DefaultBootstrapContext())`.
  This is Spring Boot's own post-processor: it resolves locations, imports files, activates
  profiles (including cascaded `spring.profiles.active`/`include` found inside loaded files),
  and orders sources by precedence.
- Read every property back: per-source listing + an **effective** view (first source to define
  a key wins, mirroring Spring precedence). Capture per property: `name`, `raw`, `resolved`
  (placeholders resolved against the whole env; fall back to raw on unresolvable placeholders),
  `source`, `unresolved` (resolved still contains `${`).
- Default launch arguments under inspection (each overridable via query param of the same name):
  - `spring.profiles.active=adc,jpa,global`
  - `spring.config.location=classpath:/,file:.../configs/global/,file:.../configs/application/,file:.../configs/application/creed-resource-catalog/`
  - `spring.config.additional-location=file:.../configs/ssl/ssl-config.properties`
- API: `GET /api/environment` → `EnvironmentSnapshot` JSON.

### R2 — Browser page
- `GET /environment` → Thymeleaf page (Bootstrap 5, matching the existing report style).
- Editable form for the three launch args (submitting re-runs the pipeline live).
- Shows active/default profiles, the effective-properties table (with an "unresolved" badge),
  and a per-source accordion in highest→lowest precedence order.
- Thymeleaf gotcha: a literal `${` inside an expression breaks the parser — compute such flags
  in Java (e.g. the `unresolved` boolean on `PropertyEntry`) rather than in the template.

### R3 — Exclude JVM/OS sources
- Omit `systemProperties` and `systemEnvironment` from both the source list **and** the effective
  view (use `StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME` /
  `SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME`). Only launch args + loaded config files remain.

### R4 — Effective properties rendered to YAML + .properties
- `GET /api/environment/rendered` → `RenderedEnvironment` JSON; `GET /environment/rendered` → page.
- Take only the **effective (active)** properties, **sort alphabetically by key**.
- Render two textual forms shown side-by-side on the page (each with a Copy button):
  - `.properties`: plain `key=value` lines — this is the **lossless / raw** view.
  - YAML: dotted/indexed keys (`a.b`, `a.b[0]`) expanded into a nested map/list tree, dumped
    via SnakeYAML in BLOCK style.

### R5 — YAML leaf+branch collision
- When a key is both a leaf and a parent (e.g. `x.keystore=JKS` **and** `x.keystore.location=...`),
  YAML cannot make one node both scalar and map. Preserve the scalar under the marker key
  `(value)` (constant `SELF_VALUE_KEY`) instead of dropping it; handle both insertion orders
  (leaf-before-branch and branch-before-leaf) and the list case. The `.properties` view stays
  lossless. The page carries a one-line legend explaining `(value)`.

### R6 — YAML scalar quoting
- Values must not all be wrapped in `'...'`. In the YAML path only (`toScalar`):
  - coerce strict integers → `Long`, strict decimals → `Double`, `true`/`false` → `Boolean`
    (so they render unquoted: `5000`, `true`); leave leading-zero / oversized numbers as strings.
  - strip a single surrounding quote pair (a `.properties` authoring artifact, e.g.
    `password="${...}"` → `${creed.ssl.keystore-password}`).
- The `.properties` view is unaffected (keeps the raw literal value).

## Endpoint summary

| Path | Type | Returns |
|------|------|---------|
| `GET /api/environment` | JSON | full snapshot: profiles, all sources, effective view |
| `GET /api/environment/rendered` | JSON | sorted effective props + `propertiesText` + `yamlText` |
| `GET /environment` | HTML | full inspector with editable launch-args form |
| `GET /environment/rendered` | HTML | side-by-side YAML / .properties |

All four accept overrides via query params: `spring.profiles.active`,
`spring.config.location`, `spring.config.additional-location`.

## Verify after changes

```bash
mvn -q -pl creed-report spring-boot:run -o      # context path /report, port 9100
curl -s "http://localhost:9100/report/api/environment/rendered" | python3 -m json.tool
```
Spot-checks: effective keys are sorted; `systemProperties`/`systemEnvironment` absent; numbers
and booleans render unquoted in YAML; a leaf+branch key shows `(value)` rather than being lost.
