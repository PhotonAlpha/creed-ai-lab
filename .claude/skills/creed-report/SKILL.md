---
name: creed-report
description: The creed-report module — a standalone Spring MVC + Thymeleaf reporting app on HTTP 9100 context-path /report, with a git commit-diff viewer, server-info/report pages, and the Environment Inspector feature, all supporting offline HTML export. Use when working on the diff/report/environment pages, Thymeleaf templates, offline export, or server-info rendering.
---

# creed-report

Standalone **Spring MVC + Thymeleaf** reporting/visualization app. Plain HTTP `9100`, context-path `/report` (no HTTPS listener, no mTLS — it's a viewer, not part of the OAuth2 mesh). Thymeleaf templates in `classpath:/templates/`, cache off. Actuator fully exposed (`env`/`configprops` with `show-values: always`) — it's an introspection tool.

## Features / layout (`com.creed.report`)
- **Commit diff viewer** — `controller/DiffController` (`@Controller`, `GET /` & `/commit`, `GET /export/commit` offline HTML). Models `Commit`, `DiffFile`, `DiffRow`, `Cell`. Renders git commit diffs as side-by-side HTML.
- **Report pages** — `controller/ReportController` (`GET /report`, `GET /export` offline HTML), backed by `service/ServerInfoService` (`model/ServerInfo`) and `service/AssetService` (inlines CSS/JS for self-contained export).
- **Environment Inspector** — `controller/EnvironmentInspectionController` (REST: `GET /api/environment`, `/api/environment/rendered`) + `controller/EnvironmentViewController` (Thymeleaf: `GET /environment`, `/environment/rendered`), backed by `service/EnvironmentInspectionService`. Models `EnvironmentSnapshot`, `PropertySourceView`, `PropertyEntry`, `RenderedEnvironment`. **For anything in this feature, use the [[env-inspector]] skill** — it has the full requirements/design (replays Spring Boot's config-loading pipeline standalone, renders effective properties to YAML/.properties).

## Conventions
- **Offline HTML export** is a recurring pattern: controllers produce `MediaType.TEXT_HTML_VALUE` with assets inlined (via `AssetService`) so the output renders with no server. Templates ending `-export.html` (`commit-export.html`, `report-export.html`) are the self-contained variants.
- Templates: `commit.html`/`commit-export.html`, `report.html`/`report-export.html`, `environment.html`/`environment-rendered.html`.

## Notes
- No config-server / SSL dependency; runs fully standalone. See [[creed-platform]] only for build/run basics (local Maven repo, JDK).
