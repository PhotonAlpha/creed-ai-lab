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

Fastest loop, no server: `PdfSampleDumpTest` renders **every** PDF template — the server report
plus both dynamic layouts — for every country × language through the real engine, bundles and fonts;
`ApprovalStatusPdfTest` dumps the fixed listing the same way, under the same system property.
Scratch output goes in the repo's own `tmp/` (git-ignored), not `/tmp`.

```bash
mvn -pl creed-report test -Dtest=PdfSampleDumpTest -Dpdf.sample.dir="$PWD/tmp/pdf"
open tmp/pdf/dynamic-statement-th-th-TH.pdf   # report-*, dynamic-form-*, dynamic-statement-*
mvn -pl creed-report test -Dtest=ApprovalStatusPdfTest -Dpdf.sample.dir="$PWD/tmp/pdf"
open tmp/pdf/approval-status.pdf              # the docs/template.jpg facsimile, 2 pages
```

It is skipped unless `-Dpdf.sample.dir` is set, so a normal build writes nothing. Timestamps and
counts go through `CountryFormatter` exactly as the endpoints do — otherwise the Thai sample would
show a Gregorian date and hide the thing it exists to preview.

With the app running, the same PDF comes from the endpoint (the dynamic one needs a definition):

```bash
curl -G 'http://localhost:9100/report/dynamic/export/pdf' -o tmp/d.pdf \
  --data-urlencode 'headers=host,ip,app,uptimeDays' \
  --data-urlencode 'data=[{"host":"a","ip":"10.0.0.1","app":"gw","uptimeDays":1234}]' \
  --data-urlencode 'country=th' --data-urlencode 'template=statement'
```

The fixed-layout listing needs no parameters at all:

```bash
curl -o tmp/approval.pdf 'http://localhost:9100/report/approval-status/export/pdf'
curl -o tmp/merged.pdf   'http://localhost:9100/report/approval-status/export/pdf/merged?copies=2'
curl -o tmp/mixed.pdf    'http://localhost:9100/report/approval-status/export/pdf/merged?langs=en,th'
```

**In the browser, with devtools**: swap `/dynamic/export/pdf` for **`/dynamic/preview/pdf`**
(or `/approval-status/export/pdf` for `/approval-status/preview/pdf`) and the
same URL answers `text/html` — the PDF template's own XHTML, the exact string
`PdfExportService.renderTemplateHtml` would have handed the renderer, `${logo}` data URIs and all.
The page adds only `static/css/report-pdf-preview.css`, which draws `<body>` as a real sheet in the
previewed chrome's own geometry — A4 landscape for the form set, portrait for the statement set,
each fed by that set's preview fragment so it cannot drift from the `@page` rule it mirrors (page
size, `@page` margins as padding, so the content box matches the PDF to the millimetre)
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

**Latest round** — two PDF layouts on `/dynamic/export/pdf` (`template=form|statement`) and a new
fixed-layout API, `/approval-status/export/pdf`, reproducing `docs/template.jpg` from a hard-coded
JSON payload. Both wear the new **statement chrome**, whose footer is a `position: running()`
element drawn in the `@bottom-center` margin box — the mechanism that pins a footer to the bottom of
every page, half-empty ones included, and carries its own centred page counter. 118 tests pass.

- **Commit diff viewer** (`DiffController`) — git commit diffs as side-by-side HTML, plus offline
  HTML export.
- **Report pages** (`ReportController`) — server info, with **HTML / PDF / Excel** export.
  - PDF via `openpdf-html` (Flying Saucer fork), using dedicated `*-pdf.html` templates in print
    CSS 2.1 with paged media. Bootstrap view templates cannot be reused (no flexbox/JS).
  - **Running header + footer with a logo image, on every page.** Both PDF templates wrap their
    body in a `.page-frame` table whose `<thead>`/`<tfoot>` are chrome fragments from
    `fragments/report-chrome-pdf.html`; `-fs-table-paginate` repeats them per page. That file now
    holds **three chrome sets**, and a template uses one whole: `styles/runningHeader/runningFooter/
    section` is the original dark bar (`report-export-pdf.html`), `formStyles/formHeader/
    formFooter/formSection` is the printed-bank-form look modelled on
    `resources/pdf-template/sample-form-uob-infinity-standard-registration.pdf`
    (`dynamic-report-export-pdf.html`): empty top margin, logo alone top-left with the title under
    it in bold uppercase `#005cb9`, a blue rule + numbered chip opening the section, and a two-line
    footnote in `#414042` opposite the page counter. The meta line (country · generated at · PDF
    snapshot) is header-right in the dark chrome and footnote line 2 in the form chrome, so the form
    report carries **one** logo per page, not two. The third set,
    `statementStyles/statementHeader/statementTitle/statementFooter`, is the transaction-statement
    look modelled on `docs/template.jpg` (`dynamic-report-statement-pdf.html`) — A4 **portrait**, a
    running header holding nothing but the logo, the title in the body under its own rule, and a
    two-storey footer **in the page margin**: `.statement-footer` is a `position: running()` element
    that `@bottom-center` draws with `content: element()`, carrying export date/time + report name
    opposite a seal (`${stamp}`) and, under them, its own centred `1 of 2` — `counter(page)`
    resolves inside a running element, which is what lets both storeys share one margin box. That is
    also what pins the footer to the bottom of a page the content does not fill; a frame `<tfoot>`
    reaches only the foot of the content. Needs the deep bottom margin (34mm; the box clips rather
    than grows) and `vertical-align: top` (or the floated seal hangs off the page edge). Two images
    per page there (logo + seal). The logos are `creed.report.pdf.logo` (dark, for the white footer) and
    `creed.report.pdf.logo-inverse` (knockout, for the dark header bar) — PNG/JPEG/GIF only, no
    SVG — read once and injected as `${logo}` / `${logoInverse}` data: URIs by `PdfExportService`
    on **every** render, so no controller can forget them and a missing file just drops the
    `<img>` (warned, never fatal; a missing inverse falls back to the plain logo).
    `creed.report.pdf.stamp` is the seal the statement chrome prints, injected as `${stamp}` by the
    same funnel — but with **no** fallback to the logo: a missing seal prints nothing rather than
    putting the brand mark where a seal belongs.
    `PdfRunningChromeTest` renders a 160-row report of each template and asserts, per page, the
    header text, the footer text and the image count that template's chrome should produce (2 for
    the dark bar, 1 for the form, 2 for the statement — whose header is image-only, so the count is
    the *only* proof it repeats, and whose `@bottom-center` counter is asserted as "N of M"). The two bundled PNGs under
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
  names are identical across countries and there is no shared block to edit. Styling is layered,
  shared first: `static/css/report.css` for the browser (the page links both) and
  `static/css/report-pdf.css` for the PDF, which `CountryStyles.pdf(country, locale)` concatenates
  in front of the country sheet and a **locale overlay**, so a template gets base + country + locale
  as one `${pdfCss}` string and cannot lose a layer by forgetting a variable.
  - **The locale overlay** is `static/css/locale/<tag>/report-pdf.css`, keyed on the **language**
    (`th-TH` → `th`, `zh-CN`/`zh-TW` → `zh`) rather than on the country, and appended **last**: it
    carries typography that follows the script rather than the edition, i.e. the same class going
    bold in one language and not in another (`locale/zh` bolds `.criteria-label` because Han at
    8.5pt reads lighter than the Latin beside it; `locale/th` un-bolds the small chrome because
    Noto Sans Thai fills its loops in when it goes bold). Unlike the country half a **missing file
    is the normal case**, not a degradation — English, Malay and Vietnamese ship none and render
    the base sheet unchanged. Pass the **same** `Locale` the template is rendered in, so the sheet
    and the message bundle cannot disagree about the language (`CountryStylesTest`).
  - **The face, as opposed to the weight, stays a message key**, because it follows the language
    and a bundle is what a language keys: `pdf.font.family` for `body` (the global default
    everything inherits) and `pdf.font.family.criteriaLabel` / `.criteriaValue`, which
    `approval-status-export-pdf.html` emits as a templated `<style>` **before** `${pdfCss}` so the
    cascade runs strictly least- to most-specific. Both default to `inherit` — a locale that needs
    no split says nothing. `report-pdf.css` sets no `font-family` on those two selectors, on
    purpose, and says so beside them.

  `CountryStyles` loads all of them eagerly and **fails startup** if one
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
  - **Multiple PDF layouts** (`DynamicReportTemplate`): `template=form` (default, the bank-form
    look) or `template=statement` (the `docs/template.jpg` look). A layout is *one enum constant +
    one `*-pdf.html` + one chrome set* — the controller only ever asks the enum for
    `pdfTemplate()`, and the page's picker is model-driven off `values()`, so a new layout appears
    in the dropdown by existing. The code rides in the definition (`DynamicTableRequest.template`),
    so the export forms re-post it; an unknown code is **400**, never a silent fallback. The
    offline HTML export deliberately ignores it: it is the live page's twin and has no pages to lay
    out. `DynamicReportTemplateSelectionTest` covers resolution, including that every layout is
    named in all six languages.
  - **`/dynamic/preview/pdf`** serves the PDF template to a browser instead of to bytes, for
    devtools. It is the *same string* by construction — `PdfExportService.renderTemplateHtml` is
    the export path stopped before Flying Saucer — plus one `${previewCss}` link;
    `PdfPreviewHtmlTest` strips the shim and asserts the remainder is byte-identical to the export's
    markup, and that a PDF render carries no preview markup at all. See the run section above.
- **Approval-status listing** (`ApprovalStatusReportController`, `/approval-status/export/pdf`,
  `/approval-status/preview/pdf`) — a PDF reproduction of the printed document in
  `docs/template.jpg`: logo above a rule, title in brand blue between two rules, a filter-criteria
  block four to a row, a four-column table whose account cell is several lines and whose status is
  printed in the accent colour, and the two-storey running footer (export date/time + document name
  opposite the seal, `1 of 2` centred below in `@bottom-center`). **It takes no input** — the sample
  payload is a JSON literal in the controller (`SAMPLE_JSON`, 13 rows so the document is two pages
  and the repeated chrome is observable), parsed into `model/ApprovalStatusReport`. It exists to pin
  the layout down; a report over real data reuses `approval-status-export-pdf.html` with a model
  built elsewhere. Wears the statement chrome whole, so it defines none of its own.
- **Merged export** (`/approval-status/export/pdf/merged?copies=2..10`, `service/PdfMergeService`) —
  the same document rendered N times and bound into one file, **with the footer's page counter
  corrected for the document it has become**. Two renders both say "1 of 2" and "2 of 2", so a naive
  concatenation counts to two twice, which is worse than carrying no counter at all: the reader can
  neither trust it nor tell which page is missing. The merge is `PdfSmartCopy` (it de-duplicates the
  identical embedded Noto faces — a plain `PdfCopy` carries one set per part); the correction finds
  each page's old counter by the string it must have printed (the merge knows how many pages each
  part had), paints it out and redraws it. The separator and the face come from the same bundle keys
  the template printed from (`pdf.page.middle`, `pdf.font.family`), so Thai reads "3 จาก 4" in the
  Thai face. `copies` outside the range is `InvalidMergeRequestException` → **400**, never a
  silently clamped document.
  **Mixed languages** (`?langs=en,th`) go through `mergeParts`, where every `Part` carries the
  separator *it* printed: the correction finds the old counter by that exact string, so a merge told
  only about " of " finds nothing on the Thai pages, warns, and leaves them counting for their part.
  The merged file counts in the **request's** language — a document made of an English half and a
  Thai half has no third one of its own, so that is a decision rather than a lookup.
  **Two things worth knowing before extending it.** (1) The old counter is *covered*, not deleted:
  it is gone from the page and still in the text layer, so an extractor or a copy-paste sees both
  strings — removing it means rewriting the page's content stream, and rendering the parts without
  a counter in the first place (or concatenating the XHTML and rendering once) would avoid it
  entirely. This route was taken because it merges any `byte[]`, not only our own renders.
  (2) The stamping font is **ours**, read from the TTF by `PdfExportService.stampingFont`, not the
  one embedded in the page: Flying Saucer embeds a *subset* carrying only the glyphs that were
  drawn, so a two-page part has no "3" to renumber with.
  (3) The cover's width cannot be measured by asking the stamping font for the width of the *old*
  string — in a mixed-language merge that string was drawn in another face, and Noto Sans answers
  for "1 จาก 2" with the Thai glyphs missing, which left a Thai "2" showing beside the new "4". It
  is the parsed extent plus **one digit's advance** instead: the parser stops at the last glyph's
  origin, and a page counter always ends in a digit. `PdfMergeServiceTest` and
  `ApprovalStatusMergedPdfTest` pin all of it, the text-layer leftover included.
  `ApprovalStatusPdfTest` renders the endpoint's own payload and asserts, per page, the footnote,
  the `N of M` counter and two images (logo + seal), plus the page-one body, the criteria block's
  two per-locale faces, and that every country × language still paginates. The document is
  rendered under **Creed's** logo and seal, not the sample's bank's.
  - **The criteria block is the module's one locale-typography showcase**: its caption and its
    datum take separate faces from `pdf.font.family.criteria*`, and whether the caption is bold
    comes from the locale CSS overlay — see the country/language section above for both halves.
    The Thai and Chinese bundles point `criteriaValue` at the **Latin** face, which is only safe
    because this document's values are references, amounts and dates: Flying Saucer picks one
    family for a whole run, so a value carrying Thai or Han under that stack would render **blank**,
    not substituted. A report over real data must flip the key back.
    `mvn -pl creed-report test -Dtest=ApprovalStatusPdfTest -Dpdf.sample.dir="$PWD/tmp/pdf"` dumps
    one PDF per country × language to eyeball the editions side by side.
  - **The same document has a second renderer**: `creed-jasper-report` (HTTP 9110) lays it out with
    JasperReports from a `.jrxml`. It reuses this module's `SAMPLE_JSON` byte-for-byte and packages
    **these** `fonts/` and `static/img/` files through `<resource>` entries in its pom, so the two
    PDFs differ only by layout engine. Two consequences: changing `SAMPLE_JSON` means changing
    `ApprovalStatusSamples` there in the same commit, and **moving or renaming `src/main/resources/fonts/`
    or the two brand PNGs breaks that module's build**, not just this one's.
- **Environment Inspector** — REST + Thymeleaf views over a standalone `ConfigurableEnvironment`
  replay. **Use the `env-inspector` skill for anything in this feature.**

## Landmines

- **What CSS the PDF renderer supports is written down** — `.claude/skills/creed-report/flying-saucer-css.md`
  catalogues the properties, selectors, units and at-rules openpdf-html 2.2.2 actually implements,
  each behavioural claim rendered and eyeballed rather than remembered. Check it before adding a rule
  to `report-pdf.css`, a `country/<code>/style-pdf.css`, a `locale/<tag>/report-pdf.css`, or a
  `<style>` in a `*-pdf.html`.
- **Never put `@media` in CSS that reaches the renderer.** Verified: the block never applies (`print`,
  `screen` and `all` all fail) **and every rule after it in that stylesheet is swallowed too** — a
  parser bug drops the whole media rule at EOF. Nothing silently degrades; a third of your sheet just
  stops existing. The PDF-side sheets are currently clean; `static/css/report-pdf-preview.css` uses
  `@media print` legitimately, because it is *linked by the browser preview and never inlined into a
  PDF*. Do not merge it into `report-pdf.css`.
- **`linear-gradient()` parses and draws nothing** in PDF output — `ITextOutputDevice.drawLinearGradient`
  is an empty method. The declaration is accepted, so there is no error to find; you just get no
  paint. Same class of trap: `calc()`, `var()`, `rem`, `box-shadow` and `transform` are dropped.
- **Getting an image onto every PDF page has two working mechanisms**, and the two obvious ones fail
  silently. An `@page` margin box **cannot** hold an image — `content: url(...)` draws
  nothing (so margin boxes stay text-only, which is also the only place `counter(page)` works).
  `position: fixed` **does** repeat per page, but it is positioned against the page's *content* box
  and clipped to it, so the negative offsets that would park a logo in the page margin render
  nothing at all. What works is the `.page-frame` wrapper table with `-fs-table-paginate` — and, as
  probing for the CSS reference established, `position: running(name)` + `content: element(name)` in
  an `@page` margin box, which does repeat an image and is the only way to reach the page *margin*.
  Both are now in production: the table carries every header (and the form/dark-bar footers), and the
  running element carries the statement footnote — the only way to reach the page *margin*, hence the
  only way to pin a footer to the bottom of a page the content does not fill. A new PDF template that
  forgets the wrapper silently loses its header.
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
  if the directory does not already exist. `mkdir -p tmp/pdf` first, or add one
  `Files.createDirectories`. `ApprovalStatusPdfTest.dumpSample` shares the property and the flaw.
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
  because it changes the *other* report too unless `.report-table` is split per chrome set. The
  statement chrome shows the shape of the fix: `.statement-table` is a second class **on top of**
  `.report-table`, declared after it in the same sheet, so it restyles one report's table (light
  rules, blue header band) without touching the other's. The form set can do the same.
- **The root `TODO.md` is stale.** Its unchecked boxes (导出PDF / 导出Excel / 根据策略导出相应格式的报表)
  all describe work that has since landed — see commits `290092a`, `7b2ca7a`, `6ab6df9`. Either tick
  them off or delete the file; as written it misrepresents the module's state.
- **The statement footnote is the only running element in the module**, and it is load-bearing:
  `.statement-footer` + `@bottom-center { content: element(statementfoot) }` is what pins a footer to
  the bottom of a page the content does not fill. Two numbers have to move together when that chrome
  changes — the `@page` bottom margin (34mm) and `statementPreview`'s `--preview-margin-bottom` —
  and the box **clips** rather than grows, so a third footnote line needs the margin deepened first.
  `PdfPreviewHtmlTest` pins the pair; nothing pins "the block still fits", which only a render shows.
- **`creed.report.pdf.stamp` ships a placeholder.** `static/img/creed-stamp.png` is a generated
  vermilion seal, committed for the same reason the logos are (a seal-less footnote would otherwise
  be the out-of-the-box look). It has **no fallback** to the logo — a missing seal prints nothing.
- **The approval-status listing is a layout, not a feature.** `/approval-status/export/pdf` serves a
  hard-coded payload on purpose; a report over real data reuses `approval-status-export-pdf.html`
  with a model built elsewhere. What it does not yet have: an entry point that accepts a real
  payload, and a link from any page — it is reachable only by URL.
- **Only two PDF layouts are reachable from `/dynamic`.** `DynamicReportTemplate` is the place to
  add a third (one constant + one template + one chrome set); the picker, the Excel/HTML buttons and
  the preview all follow the enum with no edit. The offline HTML export ignores `template=` by
  design — if a second HTML look is ever wanted, that decision has to be revisited, not patched.
- Three Excel report types exist (`server`, `environment`, `dynamic`). The strategy plumbing is
  built for more.
- `/dynamic` renders whatever JSON it is given; there is no schema and no persistence. If it ever
  needs to accept a real `application/json` body (rather than a form field), that is a new
  `@RequestBody` entry point, not a change to `DynamicTableService`.
- The country axis covers the **report** pages and their exports only. The commit diff viewer and
  the Environment Inspector are developer-facing and have no country dimension; the
  `EnvironmentExcelExporter` inherits the country's date format through `ExcelExportRequest` but
  nothing else.
