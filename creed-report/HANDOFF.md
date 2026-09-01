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

### Previewing the PDF templates

Fastest loop, no server: `PdfSampleDumpTest` renders **both** PDF templates for every country ×
language through the real engine, bundles and fonts.

```bash
mvn -pl creed-report test -Dtest=PdfSampleDumpTest -Dpdf.sample.dir=/tmp/pdf
open /tmp/pdf/dynamic-th-th-TH.pdf     # report-*.pdf and dynamic-*.pdf, 9 editions each
```

It is skipped unless `-Dpdf.sample.dir` is set, so a normal build writes nothing. Timestamps and
counts go through `CountryFormatter` exactly as the endpoints do — otherwise the Thai sample would
show a Gregorian date and hide the thing it exists to preview.

With the app running, the same PDF comes from the endpoint (the dynamic one needs a definition):

```bash
curl -G 'http://localhost:9100/report/dynamic/export/pdf' -o /tmp/d.pdf \
  --data-urlencode 'headers=host,ip,app,uptimeDays' \
  --data-urlencode 'data=[{"host":"a","ip":"10.0.0.1","app":"gw","uptimeDays":1234}]' \
  --data-urlencode 'country=th'
```

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
- **i18n on two axes** — **language** (`?lang=`, cookie `creed-report-locale`): en / zh_CN / zh_TW /
  th / ms / vi, one `MessageSource` bean per domain chained by `setParentMessageSource`; and
  **country** (`?country=`, cookie `creed-report-country`): `global` / `th` / `my` / `vn`.
  `CountryLocaleResolver` folds the two into one `Locale` whose **region subtag is the country**, so
  a country override is just a `report-messages_<lang>_<REGION>.properties` bundle and controllers
  still take a plain `Locale`. `com.creed.report.i18n` holds the axis: `ReportCountry` (defaults),
  `CountryProperties` (overrides), `CountryCatalog` (merge + resolution), `CountryProfile` (the
  resolved per-request object handed to the views), `CountryFormatter` (dates/counts).
  Per country the report differs in **scope** (only that country's servers), **format** (Thailand
  dates in the Buddhist era, Malaysia 12-hour PG/PTG, Vietnam `.` grouping) and **markup + CSS**.
  A country edition owns four files under its own code — `templates/country/<code>/report.html`
  and `report-pdf.html`, `static/css/country/<code>/style.css` and `style-pdf.css` — pulled in
  **by path** (`~{${profile.contentTemplate} :: notice}`, `@{${profile.styleSheet}}`), so fragment
  names are identical across countries and there is no shared block to edit. `CountryStyles` loads
  the stylesheets eagerly and **fails startup** if one is missing. The **page header and footer are
  identical in every country** — they live in `templates/fragments/report-chrome.html`, read no
  country field and pull in no country file; `OfflineHtmlExportTemplateTest` asserts it.
- **Dynamic table report** (`DynamicReportController`, `/dynamic`) — the table's shape comes from
  the request: `headers` split on commas gives the column **keys** (labels resolve as
  `report.col.<key>`, so a dynamic header is still translated), `data` is a JSON array of rows,
  either objects keyed by header or arrays in header order. Same HTML / PDF / Excel exports, same
  country + language handling, same chrome. GET and POST both work — and the page's export buttons
  POST rather than link, because `data` outgrows a query string.
- **Environment Inspector** — REST + Thymeleaf views over a standalone `ConfigurableEnvironment`
  replay. **Use the `env-inspector` skill for anything in this feature.**

## Landmines

- **PDF fonts cost a debugging session.** CFF-flavored OTFs embed but silently drop all CJK glyphs —
  use static glyf TTFs. Flying Saucer does **not** synthesize bold, so without a Bold face bold CJK
  text silently disappears. There is no per-glyph fallback across families, so each locale's
  `pdf.font.family` stack must lead with the face covering its script — and that face must also
  carry the Latin the report is full of. The bundled `NotoSansThai-*.ttf` are the **Google Fonts**
  build (Thai *plus* Latin-1), instanced from the variable font; the `notofonts.github.io` build is
  Thai-only and would blank every host name in a Thai PDF.
- **`th:replace` outranks `th:if`** — both on one tag includes the fragment unconditionally. That is
  why the switcher's `th:if` sits on an outer `<th:block>`: without it the switcher's `@{...}` links
  reached the offline export, which renders on a plain non-web `Context`, and 500'd it.
- **The country default-language rule keys off the country *selection*, not the `?country=`
  parameter.** The exports carry no query string, so keying off the parameter renders the page in
  Thai and its downloads in English.
- **The report page's Excel dropdown is model-driven**, so any new `ReportType` appears in it
  automatically — including one that cannot work from a bare link. `ReportType.linkable()` is what
  keeps `dynamic` out of it; the dropdown reads `ExcelExportService.linkableTypes()`.
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
- Three Excel report types exist (`server`, `environment`, `dynamic`). The strategy plumbing is
  built for more.
- `/dynamic` renders whatever JSON it is given; there is no schema and no persistence. If it ever
  needs to accept a real `application/json` body (rather than a form field), that is a new
  `@RequestBody` entry point, not a change to `DynamicTableService`.
- The country axis covers the **report** pages and their exports only. The commit diff viewer and
  the Environment Inspector are developer-facing and have no country dimension; the
  `EnvironmentExcelExporter` inherits the country's date format through `ExcelExportRequest` but
  nothing else.
