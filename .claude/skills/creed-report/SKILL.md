---
name: creed-report
description: The creed-report module — a standalone Spring MVC + Thymeleaf reporting app on HTTP 9100 context-path /report, with a git commit-diff viewer, server-info/report pages, and the Environment Inspector feature, exportable as offline HTML, PDF (openpdf-html) and Excel (POI, strategy-per-report-type). Use when working on the diff/report/environment pages, Thymeleaf templates, offline/PDF/Excel export, or server-info rendering.
---

# creed-report

Standalone **Spring MVC + Thymeleaf** reporting/visualization app. Plain HTTP `9100`, context-path `/report` (no HTTPS listener, no mTLS — it's a viewer, not part of the OAuth2 mesh). Thymeleaf templates in `classpath:/templates/`, cache off. Actuator fully exposed (`env`/`configprops` with `show-values: always`) — it's an introspection tool.

## Features / layout (`com.creed.report`)
- **Commit diff viewer** — `controller/DiffController` (`@Controller`, `GET /` & `/commit`, `GET /export/commit` offline HTML). Models `Commit`, `DiffFile`, `DiffRow`, `Cell`. Renders git commit diffs as side-by-side HTML.
- **Report pages** — `controller/ReportController` (`GET /report`, `GET /export` offline HTML, `GET /export/pdf` PDF), backed by `service/ServerInfoService` (`model/ServerInfo`) and `service/AssetService` (inlines CSS/JS for self-contained export). **i18n on two axes** — see the *Country + language* section below. Bundles live in `classpath:/i18n/<domain>-messages[_locale].properties` (`report-`, `payment-`; `_zh` duplicates `_zh_CN` as bare-`zh` fallback). `config/MessageSourceConfig` defines **one `MessageSource` bean per domain**, composed with `setParentMessageSource` — head bean **must** be named `messageSource` (container/Thymeleaf lookup name, and what `MessageSourceAutoConfiguration` backs off on, which is why `spring.messages.*` is absent from application.yml and would be inert if added). Each domain owns a key prefix so they can't shadow each other; `fallbackToSystemLocale=false` so unknown locales get English (it also picks the font stack). Tests build the chain via `new MessageSourceConfig().messageSource()` rather than re-declaring basenames. The bundles also carry per-locale CSS font stacks: `pdf.font.family` (PDF, picks the one Noto face FS can use) and `html.font.family` (browser stack, locale's Noto face first). Templates use `#{...}` throughout — incl. inside `<style th:inline="css">` for the `@page` margin-box page-counter fragments (`pdf.page.*`, edge spaces kept via ` ` escapes).
- **PDF export** — `service/PdfExportService`: Thymeleaf → XHTML → PDF via `com.github.librepdf:openpdf-html` (Flying Saucer fork on OpenPDF, classes under `org.openpdf.*`, `ITextRenderer`). PDF templates are dedicated `*-pdf.html` variants (`report-export-pdf.html`, the CSS-2.1 rebuild of `report-export.html`'s look) with print CSS 2.1 + paged-media (`@page` margin boxes, `counter(page)/counter(pages)`, `-fs-table-paginate`) — Bootstrap view templates can't be reused (no flexbox/JS). Parsing is lenient (bundled neko-htmlunit repairs sloppy HTML) but keep templates well-formed. Fonts: `creed.report.pdf.font-paths` (comma-separated Spring resource patterns, default `classpath:/fonts/*.ttf,*.otf`; registered IDENTITY_H + embedded; `addFont` accepts file paths, `file:`/`jar:` URLs and classpath-resource paths natively, and `BaseFont`'s static cache makes per-render re-registration a cache hit). Bundled Noto faces in `src/main/resources/fonts/`: Noto Sans + Noto Sans SC/TC/Thai, Regular+Bold each. **Font gotchas (cost a debugging session):** CFF-flavored OTFs (official noto-cjk builds) embed but silently drop all CJK glyphs — use static glyf TTFs (SC/TC are cut from Google Fonts variable TTFs via `fonttools varLib.instancer wght=N --update-name-table`; plain variable TTFs fail to register); Flying Saucer doesn't synthesize bold, so without a Bold face bold CJK text (headings/th) silently disappears; no per-glyph fallback across families, so each locale's `pdf.font.family` stack must lead with the face covering its script — **and that face must also carry the Latin** the table is full of (host names, IPs). The bundled `NotoSansThai-*.ttf` are the **Google Fonts** build (Thai *plus* Latin-1), instanced at wght 400/700; the `notofonts.github.io` release is Thai-only (101 glyphs) and would blank every host name in a Thai PDF. Malay and Vietnamese add no script — Noto Sans covers Vietnamese diacritics — so only `_th` overrides `pdf.font.family`.
- **Excel export (strategy pattern)** — `GET /export/excel?type=<code>` on `ReportController`, package `com.creed.report.export`, POI (`org.apache.poi:poi-ooxml`, version pinned in the module pom — Boot's BOM doesn't manage it). `ReportType` enum (wire codes `server` / `environment`) is the strategy key; `ExcelReportExporter` is the strategy (`reportType()` + `write(Workbook, ExcelExportRequest)`); `ExcelExportService` is the context — Spring injects every exporter bean, it builds the `EnumMap` (duplicate type ⇒ startup failure), owns the `XSSFWorkbook` lifecycle (exporters must not write/close it) and the download filename. **A new report type = one enum constant + one `@Component` exporter**; the `/report` page's Excel dropdown is driven by the `reportTypes` model attribute (`ExcelExportService.supportedTypes()`), so it lists new types automatically. Implementations: `ServerInventoryExcelExporter` (one sheet, same columns as the page/PDF), `EnvironmentExcelExporter` (Summary / Effective Properties / Property Sources / All Properties, the last flagging which occurrence of a shadowed key won). All request query params are passed through in `ExcelExportRequest.parameters` — that is how the environment report picks up `spring.profiles.active` / `spring.config.location` / `spring.config.additional-location` from the URL (defaults from `EnvironmentInspectionService`). Shared helpers: `ExcelStyles` (workbook-scoped cell styles — POI caps a workbook at 64k styles, so never per cell) and `ExcelSheetBuilder` (fluent title/caption/header/row + `finish()` doing merge, freeze pane, autofilter and capped autosize; sheet names go through `WorkbookUtil.createSafeSheetName`, 31-char limit). Localized like the rest: `report.type.*` and `excel.*` keys in the report bundle. Unknown `type` ⇒ `UnknownReportTypeException` (`@ResponseStatus(BAD_REQUEST)`), not a 500.
- **Dynamic table report** — `controller/DynamicReportController` (`/dynamic`, `/dynamic/export`, `/dynamic/export/pdf`), package `com.creed.report.dynamic`. The table's *shape* comes from the request: `headers` is split on commas into column **keys**, `data` is a JSON array of rows. Details in the *Caller-defined tables* section below.
- **Environment Inspector** — `controller/EnvironmentInspectionController` (REST: `GET /api/environment`, `/api/environment/rendered`) + `controller/EnvironmentViewController` (Thymeleaf: `GET /environment`, `/environment/rendered`), backed by `service/EnvironmentInspectionService`. Models `EnvironmentSnapshot`, `PropertySourceView`, `PropertyEntry`, `RenderedEnvironment`. **For anything in this feature, use the [[env-inspector]] skill** — it has the full requirements/design (replays Spring Boot's config-loading pipeline standalone, renders effective properties to YAML/.properties).

### Previewing a PDF template

`PdfSampleDumpTest` is the iteration loop — it renders `report-export-pdf` **and**
`dynamic-report-export-pdf` for every country × language through the real engine, bundles and fonts,
without starting Tomcat:

```bash
mvn -pl creed-report test -Dtest=PdfSampleDumpTest -Dpdf.sample.dir=/tmp/pdf && open /tmp/pdf
```

Skipped unless `-Dpdf.sample.dir` is set. Its `generatedAt`/`total` go through `CountryFormatter`
like the endpoints', so a sample is a faithful preview rather than a lookalike. To eyeball one
without a viewer: `sips -s format png -Z 1600 /tmp/pdf/x.pdf --out /tmp/x.png`.

### Provisioning the PDF fonts

`src/main/resources/fonts/` is **gitignored** (~36MB), so a fresh clone has none and the locale font tests fail until it is populated. Recipe (needs `pip install fonttools`):

```bash
cd creed-report/src/main/resources/fonts
# Latin (covers Vietnamese and Malay as-is) — static release, no instancing needed
curl -LO https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf
curl -LO https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSans/hinted/ttf/NotoSans-Bold.ttf
# Thai / SC / TC — Google Fonts variable builds, cut to static instances.
# Use the google/fonts copy, NOT notofonts.github.io: only the former bundles Latin alongside
# the script, and Flying Saucer has no cross-family fallback.
curl -L -o /tmp/NotoSansThai-VF.ttf \
  'https://github.com/google/fonts/raw/main/ofl/notosansthai/NotoSansThai%5Bwdth,wght%5D.ttf'
python3 -m fontTools.varLib.instancer /tmp/NotoSansThai-VF.ttf wght=400 wdth=100 \
  --update-name-table -o NotoSansThai-Regular.ttf
python3 -m fontTools.varLib.instancer /tmp/NotoSansThai-VF.ttf wght=700 wdth=100 \
  --update-name-table -o NotoSansThai-Bold.ttf   # same two calls for notosanssc / notosanstc
```

Verify a face before trusting it: `TTFont(f)` must have `glyf` and **no** `fvar` (variable fonts fail to register), its `name` table must say the family the CSS asks for, and `getBestCmap()` must contain both the script (`0x0E01` for Thai) and Basic Latin.

## Country + language (the two presentation axes)

The report is rendered for one **country edition** in one **language**, and the two are independent inputs that are folded into a single `Locale` at the edge. Package `com.creed.report.i18n`.

- **Why one `Locale`** — the country contributes the **region subtag**, so `ResourceBundleMessageSource` gives the three-level fallback `report-messages_en_MY` → `report-messages_en` → `report-messages` for free. A country bundle then carries only the keys that differ (`report.country.name` / `.notice` / `.timezone`), and every controller, exporter and template keeps taking a plain `Locale`. `CountryCatalog.profileFor(Locale)` is the inverse (region → country), which is why nothing had to grow a second parameter.
- **`ReportCountry`** (`GLOBAL`/`TH`/`MY`/`VN`) is both the key and the built-in defaults — languages, date pattern, calendar, number locale — i.e. the `:fallback` half of the config convention. **`CountryProperties`** (`creed.report.country.profiles.<code>.*`) overrides individual fields; **`CountryCatalog`** merges them once at startup and fails loudly, naming the country, on a bad pattern or locale. **`CountryProfile`** is the merged, per-request result handed to the views as `${profile}`; **`CountryFormatter`** is static (no state to inject, and `ExcelExportRequest` — a record — calls it).
- **`GLOBAL`'s region is empty on purpose.** Overwriting the region for the region-less default would collapse `zh-CN` and `zh-TW` onto bare `zh`, i.e. Traditional Chinese would silently vanish. `CountryProfile.effectiveLocale` passes the language locale through untouched when the region is empty.
- **A country only renders in its own languages** (`TH: th,en` · `MY: ms,en` · `VN: vi,en` · `GLOBAL: en,zh-CN,zh-TW`); an unsupported one falls back to that country's default, first in the list.
- **A *selected* country implies its language** unless the visitor actually chose one (`?lang=` or the locale cookie). Key this off the selection — parameter **or cookie** — never off `?country=` alone: the exports carry no query string, so the parameter-only version renders the page in Thai and its downloads in English.
- **`CountryLocaleResolver` composes, it does not extend** `CookieLocaleResolver`: the delegate keeps owning the locale cookie so `LocaleChangeInterceptor` works unchanged. It reads `?country=` itself because `DispatcherServlet` builds the request's `LocaleContext` **before** interceptors run — `CountryChangeInterceptor` only persists the cookie.
- **What differs per country**: row **scope** (`ServerInfoService.listServers(country)`), **format** (Thailand in the Buddhist era — a property of the country, so `?country=th&lang=en` still shows 2569; Malaysia 12-hour PG/PTG, and note `en-MY` writes lower-case `pm`; Vietnam `.` grouping), and **markup + CSS**, which a country owns outright — see below.

### One directory per country, loaded by path

A country edition owns four files, all under its own code:

```
templates/country/<code>/report.html        browser fragments   (th:fragment="notice", …)
templates/country/<code>/report-pdf.html    PDF fragments       (same names, CSS-2.1 markup)
static/css/country/<code>/style.css         browser stylesheet
static/css/country/<code>/style-pdf.css     PDF stylesheet (inlined, not linked)
```

`ReportCountry.contentTemplate()` / `pdfContentTemplate()` / `styleSheet()` / `pdfStyleSheet()` are the **single definition** of that layout; templates and `CountryStyles` both go through them.

- **Include by template path, not by fragment name** — `~{${profile.contentTemplate} :: notice}`. Thymeleaf allows an expression as the template half of a fragment expression, so the earlier `~{fragments/country-web :: notice-__${profile.code}__}` preprocessing is gone: the fragment name is now the *same* in every country, so a country's file can grow a second and third fragment without any name mangling, and nothing has to be kept unique across countries.
- **Only one country's CSS is ever loaded**, so the country stylesheets need **no `.country-<code>` prefix** — plain `.total-badge { … }`. The live page `<link>`s `report.css` then `@{${profile.styleSheet}}`; the offline export and the PDF inline the same files via `CountryStyles` (openpdf-html renders from a string, so it has no base URL for a `<link>`). The `country-<code>` body class survives only as a **marker**, not a selector hook.
- **Split rule for CSS**: CSS that needs a message key stays a Thymeleaf fragment (`report-chrome :: styles`, which is now just the locale font stack); everything else is a plain `.css` file. The shared, country-neutral rules are `static/css/report.css` and deliberately set **no** accent colours — every country, `global` included, defines its own, so a missing stylesheet renders visibly wrong.
- **`CountryStyles` loads all of them eagerly in its constructor** (not `@PostConstruct`, so tests can `new CountryStyles()`) and throws naming the country and path — a missing country stylesheet is a **startup** failure, not a quietly unstyled page.
- **The page header and footer are identical in every country.** That is structural, not a convention: they are fragments in `fragments/report-chrome.html` that read no country field and pull in no country template or stylesheet, and `OfflineHtmlExportTemplateTest.theHeaderAndFooterAreIdenticalInEveryCountry` diffs them across all four editions.
- **`th:replace` outranks `th:if`** — both on one tag includes the fragment regardless of the condition. The switcher's `th:if` therefore sits on an outer `<th:block>`; without it the switcher's `@{...}` links reach the offline export, which renders on a plain non-web `Context`, and 500 it.
- **Adding a country** = one `ReportCountry` constant + its four files above + its region bundles (`_<lang>_<REGION>.properties` per language it offers). No shared block to edit anywhere.

## Caller-defined tables (`/dynamic`)

A report whose columns and rows the caller supplies, sharing every piece of the country/language machinery with the built-in one.

- **Columns are keys, not labels.** Each `headers` token is a column key whose label resolves as `report.col.<key>` — the server report's own keys — so `headers=host,ip,app` comes out translated in any of the six languages for free. A key with no message shows verbatim (ad-hoc columns just work); `key:Label` spells one out explicitly. This is why the header can be dynamic *and* localized at once: making the tokens display text would have made them untranslatable.
- **Rows take either JSON shape**: objects (`[{"host":"a"}]`, read by column key — order free, missing field = empty cell) or arrays (`[["a","b"]]`, positional, padded/truncated to the columns).
- **`DynamicTable.Cell` carries `value` *and* `text`** — the raw JSON value and its country-formatted rendering. Templates print `text`; Excel writes `excelValue()` so numeric columns stay numeric and a spreadsheet can still sum them.
- **Country formatting, not language formatting**: numbers take the country's grouping (`1.234` in Vietnam), booleans the localized yes/no. Strings pass through untouched **on purpose** — guessing at date-like strings would corrupt identifiers that merely look like dates.
- **Every endpoint answers GET and POST**, and `/export/excel` was widened to POST for the same reason: `data` is caller-sized and outgrows a query string. The page's three export buttons are therefore forms re-posting the definition (`fragments/dynamic-table :: definitionFields`), not links — cookies still carry the country and language, but the table exists only in that form.
- **`ReportType.linkable()`** is false for `DYNAMIC`. The report page's Excel dropdown is model-driven off `ExcelExportService.linkableTypes()`; without the flag it would offer a `?type=dynamic` link that can only answer 400.
- **Bad input is 400, never 500** — `InvalidTableDefinitionException` (`@ResponseStatus(BAD_REQUEST)`), like `UnknownReportTypeException`. `creed.report.dynamic.max-columns` / `max-rows` cap the payload, because POI and openpdf-html lay the whole table out in memory.
- The Excel side needed **no new plumbing**: `headers`/`data`/`title` ride in on `ExcelExportRequest.parameters`, which is exactly what that pass-through map is for, and `DynamicTableRequest.from(...)` makes the controller and the exporter read the same names.

## Conventions
- **Shared chrome, per-report body.** Both reports' header/footer come from `fragments/report-chrome.html` (browser) and `fragments/report-chrome-pdf.html` (PDF — `styles` with the `@page` boxes and base CSS 2.1, plus `header`/`section`/`footer`). Adding a report means a body, not chrome. The table CSS hook is `.report-table` (was `.servers-table`), so a country's stylesheet styles *any* report's table.
- **Offline HTML export** is a recurring pattern: controllers produce `MediaType.TEXT_HTML_VALUE` with assets inlined (via `AssetService`) so the output renders with no server. Templates ending `-export.html` (`commit-export.html`, `report-export.html`) are the self-contained variants.
- Templates: `commit.html`/`commit-export.html`, `report.html`/`report-export.html`/`report-export-pdf.html`, `dynamic-report.html`/`dynamic-report-export.html`/`dynamic-report-export-pdf.html`, `environment.html`/`environment-rendered.html`, plus `fragments/` (chrome, chrome-pdf, dynamic-table) and `country/<code>/`.

## Notes
- No config-server / SSL dependency; runs fully standalone. See [[creed-platform]] only for build/run basics (local Maven repo, JDK).
