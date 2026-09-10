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

**In the browser, with devtools**: swap `/dynamic/export/pdf` for **`/dynamic/preview/pdf`** and the
same URL answers `text/html` — the PDF template's own XHTML, the exact string
`PdfExportService.renderTemplateHtml` would have handed the renderer, `${logo}` data URIs and all.
The page adds only `static/css/report-pdf-preview.css`, which draws `<body>` as a real A4-landscape
sheet (page size, `@page` margins as padding, so the content box matches the PDF to the millimetre)
and `@font-face`s the very TTFs the PDF embeds, served at `/fonts/**` by `PdfPreviewConfig`. The
`/dynamic` page has a **Preview PDF layout** button that posts the current definition to it.

Two things the browser cannot reproduce, both by construction: the `@page` margin boxes (no browser
implements `@bottom-right { content: counter(page) }`, so the page counter is missing), and
pagination — on screen the sheet just grows, and the running header/footer appear once instead of
per page. The faint blue rules mark each page's worth of flow (approximate: the repeated chrome
costs the PDF ~2 rows a page), and **Cmd+P is the real check** — Chrome honours the document's own
`@page` and repeats the `.page-frame` `thead`/`tfoot`, like `-fs-table-paginate` does.

Caveat when iterating: `spring-boot:run` serves from `target/classes`, so an edit to
`src/main/resources/static/css/report-pdf-preview.css` needs `mvn -pl creed-report resources:resources`
(or an IDE that copies resources) before a refresh shows it. Live-editing the sheet in devtools
works without any of that.

## Current state

- **Commit diff viewer** (`DiffController`) — git commit diffs as side-by-side HTML, plus offline
  HTML export.
- **Report pages** (`ReportController`) — server info, with **HTML / PDF / Excel** export.
  - PDF via `openpdf-html` (Flying Saucer fork), using dedicated `*-pdf.html` templates in print
    CSS 2.1 with paged media. Bootstrap view templates cannot be reused (no flexbox/JS).
  - **Running header + footer with a logo image, on every page.** Both PDF templates wrap their
    body in a `.page-frame` table whose `<thead>`/`<tfoot>` are chrome fragments from
    `fragments/report-chrome-pdf.html`; `-fs-table-paginate` repeats them per page. That file now
    holds **two chrome sets**, and a template uses one whole: `styles/runningHeader/runningFooter/
    section` is the original dark bar (`report-export-pdf.html`), `formStyles/formHeader/
    formFooter/formSection` is the printed-bank-form look modelled on
    `resources/pdf-template/sample-form-uob-infinity-standard-registration.pdf`
    (`dynamic-report-export-pdf.html`): empty top margin, logo alone top-left with the title under
    it in bold uppercase `#005cb9`, a blue rule + numbered chip opening the section, and a two-line
    footnote in `#414042` opposite the page counter. The meta line (country · generated at · PDF
    snapshot) is header-right in the dark chrome and footnote line 2 in the form chrome, so the form
    report carries **one** logo per page, not two. The logos are `creed.report.pdf.logo` (dark, for the white footer) and
    `creed.report.pdf.logo-inverse` (knockout, for the dark header bar) — PNG/JPEG/GIF only, no
    SVG — read once and injected as `${logo}` / `${logoInverse}` data: URIs by `PdfExportService`
    on **every** render, so no controller can forget them and a missing file just drops the
    `<img>` (warned, never fatal; a missing inverse falls back to the plain logo).
    `PdfRunningChromeTest` renders a 160-row report of each template and asserts, per page, the
    header text, the footer text and the image count that template's chrome should produce (2 for
    the dark bar, 1 for the form). The two bundled PNGs under
    `static/img/` are **placeholders** — swap the files or point the properties elsewhere; they are
    committed (unlike `fonts/`, which is gitignored) because they are small and a logo-less header
    would otherwise be the out-of-the-box look.
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
  names are identical across countries and there is no shared block to edit. Styling is two layers,
  shared first and country last: `static/css/report.css` for the browser (the page links both) and
  `static/css/report-pdf.css` for the PDF, which `CountryStyles.pdf()` concatenates in front of the
  country sheet so a template gets base + country as one `${pdfCss}` string and cannot lose the base
  by forgetting a variable. `CountryStyles` loads all of them eagerly and **fails startup** if one
  is missing — except the two PDF files, which **degrade**: a country shipping no
  `country/<code>/report-pdf.html` or `style-pdf.css` renders the `country/default/` edition
  (`templates/country/default/report-pdf.html`, `static/css/country/default/style-pdf.css`) instead, and the countries doing so are named in the startup log
  (`ReportCountry.pdfContentTemplateFor/pdfStyleSheetFor`, `CountryLayoutTest`). The browser pair
  stays mandatory. The header's country/language switcher links back to the page it is rendered on —
  `header(..., page)` — so switching on `/dynamic` stays on `/dynamic`
  (`ReportChromeSwitcherTest`). The **page header and footer are
  identical in every country** — they live in `templates/fragments/report-chrome.html`, read no
  country field and pull in no country file; `OfflineHtmlExportTemplateTest` asserts it.
- **Dynamic table report** (`DynamicReportController`, `/dynamic`) — the table's shape comes from
  the request: `headers` split on commas gives the column **keys** (labels resolve as
  `report.col.<key>`, so a dynamic header is still translated), `data` is a JSON array of rows,
  either objects keyed by header or arrays in header order. Same HTML / PDF / Excel exports, same
  country + language handling, same chrome. GET and POST both work — and the page's export buttons
  POST rather than link, because `data` outgrows a query string.
  - **`/dynamic/preview/pdf`** serves the PDF template to a browser instead of to bytes, for
    devtools. It is the *same string* by construction — `PdfExportService.renderTemplateHtml` is
    the export path stopped before Flying Saucer — plus one `${previewCss}` link;
    `PdfPreviewHtmlTest` strips the shim and asserts the remainder is byte-identical to the export's
    markup, and that a PDF render carries no preview markup at all. See the run section above.
- **Environment Inspector** — REST + Thymeleaf views over a standalone `ConfigurableEnvironment`
  replay. **Use the `env-inspector` skill for anything in this feature.**

## Landmines

- **What CSS the PDF renderer supports is written down** — `.claude/skills/creed-report/flying-saucer-css.md`
  catalogues the properties, selectors, units and at-rules openpdf-html 2.2.2 actually implements,
  each behavioural claim rendered and eyeballed rather than remembered. Check it before adding a rule
  to `report-pdf.css`, a `country/<code>/style-pdf.css`, or a `<style>` in a `*-pdf.html`.
- **Never put `@media` in CSS that reaches the renderer.** Verified: the block never applies (`print`,
  `screen` and `all` all fail) **and every rule after it in that stylesheet is swallowed too** — a
  parser bug drops the whole media rule at EOF. Nothing silently degrades; a third of your sheet just
  stops existing. The PDF-side sheets are currently clean; `static/css/report-pdf-preview.css` uses
  `@media print` legitimately, because it is *linked by the browser preview and never inlined into a
  PDF*. Do not merge it into `report-pdf.css`.
- **`linear-gradient()` parses and draws nothing** in PDF output — `ITextOutputDevice.drawLinearGradient`
  is an empty method. The declaration is accepted, so there is no error to find; you just get no
  paint. Same class of trap: `calc()`, `var()`, `rem`, `box-shadow` and `transform` are dropped.
- **Getting an image onto every PDF page has exactly one working mechanism**, and the two obvious
  ones fail silently. An `@page` margin box **cannot** hold an image — `content: url(...)` draws
  nothing (so margin boxes stay text-only, which is also the only place `counter(page)` works).
  `position: fixed` **does** repeat per page, but it is positioned against the page's *content* box
  and clipped to it, so the negative offsets that would park a logo in the page margin render
  nothing at all. What works is the `.page-frame` wrapper table with `-fs-table-paginate` — and, as
  probing for the CSS reference established, `position: running(name)` + `content: element(name)` in
  an `@page` margin box, which does repeat an image and is the only way to reach the page *margin*.
  The table stays: it is what `PdfRunningChromeTest` pins. But "exactly one" was "exactly one of the
  three we tried". A new
  PDF template that forgets that wrapper silently loses its header and footer.
- **Do not put a `<tfoot>` on the data table.** The wrapper table is fine, but a `<tfoot>` added to
  the paginating `report-table` itself blew a 60-row table up to **122 pages** with its content
  dropped. Running footers belong to the frame.
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

- **`PdfSampleDumpTest` does not create `-Dpdf.sample.dir`** — it fails with `NoSuchFileException`
  if the directory does not already exist. `mkdir -p` first, or add one `Files.createDirectories`.
- The `@page` `@top-left` box still prints `pdf.page.header` **in `report-export-pdf.html`**, which
  says nearly the same thing as the running header bar one line below it on every page. Harmless,
  but it is duplication that only made sense while the brand bar appeared on page 1 only; dropping
  the box (and the key from the seven bundles) would tidy it. `formStyles` already omits it, so the
  dynamic report is clean.
- **The PDF preview is a browser, not the renderer.** `/dynamic/preview/pdf` guarantees the same
  *input* (markup, CSS, fonts, page box), never the same *output*: Flying Saucer and Blink are two
  layout engines, and they disagree most about table auto-layout and page breaks. Trust it for
  "does this fit / wrap / measure right", verify the finished thing with the PDF. The shim's include
  in `dynamic-report-export-pdf.html` also has the `th:if` on an **outer** `<th:block>` — `th:replace`
  on the same tag outranks `th:if`, and the fragment's `<link>` would then reach the PDF path.
- **The form chrome stops at the chrome.** `dynamic-report-export-pdf.html` still renders its table
  with the shared `.card` + `#212529` `thead` from `report-pdf.css`, and the country notice keeps
  the country's own accent, so a dark table and a red notice sit under blue form chrome. Restyling
  the table to match (light rules, blue header row) is the obvious follow-up — it was left out
  because it changes the *other* report too unless `.report-table` is split per chrome set.
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
