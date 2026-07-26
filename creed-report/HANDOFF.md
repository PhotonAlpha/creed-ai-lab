# creed-report — handoff

**Purpose** Standalone Spring MVC + Thymeleaf reporting/visualization app. **Not part of the OAuth2
mesh** — no HTTPS listener, no mTLS, no config server.
**Skill** `creed-report` · Environment Inspector: `env-inspector` · user docs: `README.md`

## Run

```bash
mvn -pl creed-report spring-boot:run
open http://localhost:9100/report/
```

HTTP `9100`, context-path `/report`. Runs fully standalone — no other module needs to be up.

## Current state

- **Commit diff viewer** (`DiffController`) — git commit diffs as side-by-side HTML, plus offline
  HTML export.
- **Report pages** (`ReportController`) — server info, with **HTML / PDF / Excel** export.
  - PDF via `openpdf-html` (Flying Saucer fork), using dedicated `*-pdf.html` templates in print
    CSS 2.1 with paged media. Bootstrap view templates cannot be reused (no flexbox/JS).
  - **Excel via a strategy pattern** (`com.creed.report.export`): `ReportType` enum is the key,
    `ExcelReportExporter` the strategy, `ExcelExportService` the context. **A new report type = one
    enum constant + one `@Component` exporter**; the page's dropdown is model-driven and picks it up
    automatically. Implemented: `ServerInventoryExcelExporter`, `EnvironmentExcelExporter`.
- **i18n** en / zh_CN / zh_TW via a cookie locale resolver, with one `MessageSource` bean per domain
  chained by `setParentMessageSource`.
- **Environment Inspector** — REST + Thymeleaf views over a standalone `ConfigurableEnvironment`
  replay. **Use the `env-inspector` skill for anything in this feature.**

## Landmines

- **PDF fonts cost a debugging session.** CFF-flavored OTFs embed but silently drop all CJK glyphs —
  use static glyf TTFs. Flying Saucer does **not** synthesize bold, so without a Bold face bold CJK
  text silently disappears. There is no per-glyph fallback across families, so each locale's
  `pdf.font.family` stack must lead with the face covering its script.
- **The head `MessageSource` bean must be named `messageSource`** — it is the container/Thymeleaf
  lookup name and what `MessageSourceAutoConfiguration` backs off on. That is why `spring.messages.*`
  is absent from `application.yml` and would be inert if added.
- **POI's version is pinned in this module's pom** — Boot's BOM does not manage it.
- POI caps a workbook at 64k cell styles, so styles are workbook-scoped in `ExcelStyles`; never
  create one per cell.
- Actuator is fully exposed here including `env`/`configprops` with `show-values: always`. That is
  intentional for an introspection tool — do **not** copy this posture to a mesh module.

## Open items

- **The root `TODO.md` is stale.** Its unchecked boxes (导出PDF / 导出Excel / 根据策略导出相应格式的报表)
  all describe work that has since landed — see commits `290092a`, `7b2ca7a`, `6ab6df9`. Either tick
  them off or delete the file; as written it misrepresents the module's state.
- Only two Excel report types exist (`server`, `environment`). The strategy plumbing is built for
  more.
