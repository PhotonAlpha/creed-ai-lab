# creed-jasper-report — handoff

The **approval-status listing** again, this time laid out by **JasperReports** from a `.jrxml`
instead of by Flying Saucer from Thymeleaf + CSS 2.1. Same printed document as
`creed-report/docs/template.jpg`, same sample payload, same fonts, same logo and seal — the module
exists so the two can be put side by side and the only difference is the layout engine.

Standalone: plain HTTP **9110**, context path **`/jasper-report`**. No OAuth2, no mTLS, no config
server, no database.

## Run it

```bash
mvn -q -pl creed-jasper-report -am -DskipTests install
mvn -pl creed-jasper-report spring-boot:run          # no workingDirectory needed: no file: SSL bundle
curl -sS -o tmp/approval.pdf http://127.0.0.1:9110/jasper-report/approval-status/export/pdf
curl -sS -H 'Accept-Language: th' -o tmp/approval-th.pdf \
     http://127.0.0.1:9110/jasper-report/approval-status/export/pdf
```

`GET|POST /approval-status/export/pdf` is the whole API. It takes **no input** — the payload is a
JSON literal in `ApprovalStatusSamples`, byte-for-byte creed-report's — so two calls a week apart
differ only in the export timestamp. Only `Accept-Language` changes anything.

The iteration loop is the test, not the server:

```bash
mvn -pl creed-jasper-report test -Dtest=ApprovalStatusJasperPdfTest -Dpdf.sample.dir="$PWD/tmp/jasper"
open tmp/jasper            # one PDF per language: en, zh-CN, zh-TW, th, ms, vi
# no viewer? sips -s format png -Z 1400 tmp/jasper/approval-status-jasper-th.pdf --out tmp/th.png
```

## Shape

| | |
|---|---|
| `jasper/approval-status.jrxml` | the document: page box, bands, styles, conditional styles |
| `jasper/approval-status-criteria.jrxml` | the criteria block — a **4-column** subreport |
| `JasperReportService` | compile (cached) → fill → `JRPdfExporter`; the only class touching the engine |
| `ApprovalStatusPdfService` | what the fill is given: data sources, parameters, `REPORT_LOCALE` |
| `FieldDataSource` | `JRDataSource` over records, field names mapped to accessors explicitly |
| `ReportLanguage` / `ExportTimestamp` | the one presentation axis, and the footer's two timestamps |
| `fonts/creed-fonts.xml` + `jasperreports_extension.properties` | the per-locale embedded faces |
| `i18n/jasper-messages*.properties` | the three strings that are not payload |

**Band → chrome**, and it is a 1:1 map onto the HTML twin's:
`title` = logo + title + criteria + count (once) · `pageHeader` = logo (pages 2+) ·
`columnHeader` = the light-blue table header (every page) · `detail` = a row ·
`pageFooter` = export stamp + seal + centred `N of M` (every page).

## Landmines

- **A text element shorter than its FACE's line height prints NOTHING.** No warning, no clipped
  glyph, no ellipsis — the line does not fit vertically and is dropped. The bundled faces are not
  the same height (8.5pt: Latin 11.58, SC/TC 12.31, Thai 12.84; 14pt: 19.07 / 20.27 / 21.15), so a
  12pt-tall caption is *correct in English and blank in Chinese and Thai*. This shipped once: the
  document title and every criteria caption were missing in two languages while the English proof
  read perfectly. Every single-line element is now sized at ~**1.6 × font size**, and
  `ApprovalStatusJasperPdfTest.everyCaptionSurvivesTheTallerScripts` renders th/zh-CN/zh-TW to keep
  it that way. creed-report cannot hit this — a CSS line box grows to fit.
- **The `title` band prints BEFORE the `pageHeader`.** `JRVerticalFiller` fills title, then page
  header, so a logo that lives only in the page header lands *below* the title — here, between the
  record count and the table, which is exactly where it first turned up. Page one's logo is
  therefore part of the title band and the page header carries
  `printWhenExpression: $V{PAGE_NUMBER} > 1`.
- **Bands are declared in the schema's order** (background, title, pageHeader, columnHeader, detail,
  columnFooter, pageFooter, …), which is not the order they print in. Out of sequence, the compile
  fails naming only the element it tripped over.
- **XML comments cannot contain `--`.** The repo's habit of writing `--` for an em dash makes a
  jrxml unparseable, and the message (`The string "--" is not permitted within comments`) does not
  say which comment. Both templates use `—` and `─`.
- **ecj must be overridden.** JasperReports 6.21.3 pins `org.eclipse.jdt:ecj` 3.21.0 (2019), which
  cannot read a modern JDK's class files; the runtime expression compile then dies on the first
  *fill* with `Unsupported class file major version`, naming neither JasperReports nor the report.
  The module pom pins a current ecj.
- **The extension registry id is not free-form.** `net.sf.jasperreports.extension.registry.factory.`
  **`simple.font.families`** — the factory reads the properties under *its own id*, so a different
  id registers a factory that finds nothing, and the only symptom is
  `JRFontNotFoundException: Font "Creed Sans" is not available to the JVM` at the first fill.
- **A family with no `<locales>` supports every locale**, so it must be declared **last** in
  `creed-fonts.xml`. Declared first it wins every lookup and Thai and CJK silently become Noto Sans.
- **JasperReports does not synthesize bold.** A bold element in a family with only a normal face
  renders nothing — which is why every family declares `<bold>`.
- **No per-glyph fallback**, exactly as in creed-report: a run is set in one face and a glyph that
  face lacks comes out blank, not substituted. Hence `Creed Sans Data` (always Latin) is only used
  for the criteria *values*, which in this document are references, amounts and dates.
- **Records are not beans.** `JRBeanCollectionDataSource` reads objects through commons-beanutils,
  i.e. `getXxx()`, which a record does not have. `FieldDataSource` maps jrxml field names to
  component accessors instead, and throws by name on an unmapped field rather than printing a blank
  cell.
- **Jasper strips leading whitespace at the start of a line**, so the two-field page counter
  (`N` right | ` of M` left) renders `1of 2`. The separator gets a **centred box of its own** —
  which also stops caring whether it is ` of `, ` 页，共 ` or ` จาก `.
- **`ResourceBundle.getBundle` falls back to the JVM default locale** before the base bundle, so an
  unnormalised `Accept-Language: fr` would render Chinese on a `zh_CN` machine. `ReportLanguage`
  normalises first; creed-report rules the same trap out with `fallbackToSystemLocale=false`, which
  Jasper has no equivalent of.

## Deliberate differences from creed-report

- **One presentation axis, not two.** creed-report varies on country × language; this document is
  fixed brand chrome there (the statement palette is not delegated to a country sheet), so the only
  axis it really varies on is the language. Consequence worth knowing before comparing the two Thai
  PDFs: the Buddhist era is a property of the **country** next door (`?country=th&lang=en` still
  shows 2569) and of the **language** here.
- **No `/preview` twin.** creed-report has one because Flying Saucer takes a string of XHTML and
  hands back bytes, so its PDF template is the one thing there you cannot open in devtools. A jrxml
  has no such intermediate; Jaspersoft Studio and `-Dpdf.sample.dir` are the equivalents.
- **Static jrxml, not DynamicJasper.** `docs/1.jpeg`–`4.jpeg` are reference photos of a
  DynamicJasper renderer that builds its columns in Java and uses a jrxml only as the outer
  template. That earns its keep when the *caller* defines the columns (creed-report's `/dynamic`
  does the same job with a `DynamicTable`). This document's four columns and its criteria pairs are
  fixed — "labelled pairs, four to a row" and "this cell is four lines that belong together" are
  the whole reason it is not a dynamic table — so the layout lives in the jrxml where it can be
  read.
- **Fonts and brand images are shared with creed-report at build time** (`<resource>` entries in
  the pom pointing at `../creed-report/src/main/resources/`), not copied. ~36MB of TTF stays in one
  place in git, and a visual difference between the two PDFs cannot be a differently-instanced
  font. `JasperFontsTest` fails loudly if a fresh clone has not provisioned them — see the
  `creed-report` skill for the recipe.

## Open items

- Only PDF. `JRPdfExporter` has siblings (`JRXlsxExporter`, `HtmlExporter`); `JasperReportService`
  already separates fill from export, so a second format is an exporter and a content type.
- The table's four column headers are hard-coded English in the jrxml, exactly as they are in the
  HTML twin. Localising them means four `$R{}` keys on both sides at once, or they drift.
- `creed.jasper.cache-templates` ships `false` (dev ergonomics). Anything load-bearing wants `true`.
