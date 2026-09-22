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
B=http://127.0.0.1:9110/jasper-report/approval-status/export
curl -sS -o tmp/approval.pdf   "$B"                       # or $B/pdf, the alias
curl -sS -o tmp/approval.xlsx  "$B?format=xlsx"
curl -sS -o tmp/approval.csv   "$B?format=csv"
curl -sS -o tmp/approval.html  "$B?format=html"
curl -sS -o tmp/approval-2col.pdf "$B?columns=status,type"      # a custom view, PDF only
curl -sS -H 'Accept-Language: th' -o tmp/approval-th.pdf "$B"
```

`GET|POST /approval-status/export` is the whole API (`/export/pdf` is kept as an alias so the
side-by-side with creed-report keeps working). It takes **no data input** — the payload is a JSON
literal in `ApprovalStatusSamples`, byte-for-byte creed-report's — so two calls a week apart differ
only in the export timestamp. What the query string chooses is the *rendering*: `format`,
`columns`, and `Accept-Language`. Bad values are **400**, never 500.

The iteration loop is the test, not the server:

```bash
mvn -pl creed-jasper-report test -Dtest=ApprovalStatusJasperPdfTest -Dpdf.sample.dir="$PWD/tmp/jasper"
open tmp/jasper            # one PDF per language: en, zh-CN, zh-TW, th, ms, vi
# no viewer? sips -s format png -Z 1400 tmp/jasper/approval-status-jasper-th.pdf --out tmp/th.png
```

## Shape

| | |
|---|---|
| `jasper/approval-status.jrxml` | the document: page box, chrome bands, styles, conditional styles. **No table** |
| `jasper/approval-status-criteria.jrxml` | the criteria block — a **4-column** subreport |
| `dynamic/TableColumn` · `TableDesign` · `TableDesigner` | the table, generated into the design at compile time |
| `dynamic/ReportShape` | template + columns + chrome + JSON query — what a compiled report *is*, and the cache key |
| `dynamic/TableRows` | the data source for a generated table, built from the same column list |
| `export/ExportFormat` · `ExportRequest` | the formats, and what a caller asks for |
| `render/JasperTableRenderer` | the pipeline: columns → shape → compile → fill → export |
| `render/TableData` | rows from a `JRDataSource`, or straight from JSON |
| `render/ApprovalStatusRenderer` | this document: its four columns, its payload, its parameters |
| `JasperReportService` | load design → write table → compile (cached) → fill → export, per format |
| `FieldDataSource` | `JRDataSource` over records, field names mapped to accessors explicitly |
| `ReportLanguage` / `ExportTimestamp` | the one presentation axis, and the footer's two timestamps |
| `fonts/creed-fonts.xml` + `jasperreports_extension.properties` | the per-locale embedded faces |
| `i18n/jasper-messages*.properties` | the three strings that are not payload |

**Band → chrome**, and it is a 1:1 map onto the HTML twin's:
`title` = logo + title + criteria + count (once) · `pageHeader` = logo (pages 2+) ·
`columnHeader` = the light-blue table header (every page) · `detail` = a row ·
`pageFooter` = export stamp + seal + centred `N of M` (every page).
The last two are **generated**; the rest are in the jrxml.

## The generated table (what replaces DynamicJasper)

The jrxml declares **no `columnHeader`, no `detail` and no fields**. They are written into the
`JasperDesign` at compile time by `TableDesigner`, from a `List<TableColumn>`:

```java
TableColumn.of("account", "Account", 34).multiline().styled("AccountCell")
```

DynamicJasper is the library that normally does this — and it is the architecture the reference
photos in `docs/` show: one jrxml for the page and the chrome, the table built in Java. It has had
no release in years, layers its own layout managers and style model over JasperReports' own, and
pulls a second API surface into the build for what is underneath a few dozen calls to the
**JasperReports design API**. `TableDesigner` is those calls: no dependency, JasperReports' own
error messages, nothing in between to go stale.

**The division of labour is the point:**

| the jrxml | the column list |
|---|---|
| page box, chrome bands, and every **style** — fonts, palette, padding, the rule under each row, the locale-conditional weights | which fields exist, their captions, their share of the width, which style a cell takes |
| read the look here, as before | supplied at runtime, by a caller |

A generated cell therefore carries no colour and no font: it names a style
(`setStyleNameReference`) and the template answers. The single exception is the table's outer
left/right rule — that belongs to *being the first or last column*, which a style cannot know, so
it is `TableDesign.edgeColor`.

Three more things it does that are easy to get wrong by hand:

- **Weights, not widths.** `26/22/34/18` are normalised over the design's `columnWidth` and the
  rounding remainder goes to the last column, so the row ends exactly on the right margin whatever
  the paper.
- **Stretch.** A `multiline()` column gets `textAdjust="StretchHeight"`; every *other* cell then
  gets `stretchType="RelativeToTallestObject"`, or its bottom rule stops at the designed height and
  the row looks torn. That is the design-API spelling of what a CSS table cell does for free.
- **Fields are replaced, not merged.** See the landmine below.

`TableRows.of(rows, columns)` builds the data source from the **same** list, so the fields the
design declares, the cells that reference them and the source that answers for them cannot drift
apart. A key a row does not carry is an empty cell (normal for a caller-shaped table); an unmapped
*field* still throws (a template and a data source that have drifted).

## The migrated renderer (`docs/1.jpeg`–`4.jpeg`)

`JasperTableRenderer` is the DynamicJasper renderer in those photos, ported onto this stack — same
responsibilities, same template-method shape, none of the library. Method for method:

| `DynamicJasperRendererAbstract` | here |
|---|---|
| `render(ExportRequest, json, params)` | `render(ExportRequest)` |
| `generateMainReport(...)` / `setTemplateFile` only for PDF | `ReportShape.document` vs `.tableOnly`, off `ExportFormat.carriesChrome()` |
| `setResourceBundle` / `setDefaultEncoding` / `setLeft+RightMargin` | declared once in the jrxml |
| `setReportLocale(locale)` | the `REPORT_LOCALE` parameter |
| `generateBaseTable()` — `setMargins(0,0,0,0)`, `setUseFullPageWidth`, `setAllowDetailSplit(false)` | `TableDesign` + `TableDesigner.stripChrome` + normalised weights + `splitType="Prevent"` |
| `generateTable(locale, request)` + `getCustomViewColumns(...)` for PDF | `columnsFor(request)` — same rule |
| `generateBaseColumn(.., firstCol, lastCol)` — four `Style` variants per position | one named style per column + two edge pens |
| `IS_IGNORE_PAGINATION` for non-PDF | `ExportFormat.paginated()` |
| `exportReport(builder, params, fileType)` + the CSV BOM property | `JasperReportService.export(..., format)` + `setWriteBOM` |
| `JsonQueryExecuterFactory.JSON_INPUT_STREAM` | `TableData.json(...)` |

**A renderer is a value over one report, not a bean.** `new ApprovalStatusRenderer(jasper, report,
now).render(request)` — the payload and the export instant are constructor arguments and final
fields, so the hooks just read them, the object is immutable and thread-safe by having no state,
and one per request costs nothing: the only expensive thing in the pipeline is the compiled report,
cached in the shared `JasperReportService` it is handed. The reference carries its payload on the
parameter map instead, which is the same information one indirection further from the code that
reads it.

**Two things the migration changes on purpose.** The reference builds the table's *look* in Java —
four `Style` objects per column position, because DynamicJasper has no other way to draw a table's
outer border; here the look stays in the jrxml as named styles and the generator adds two pens, so
a column says which style it takes and never what colour it is. And the non-PDF path starts from
the **same** template with its chrome stripped rather than from a second report built from nothing,
so there is one file to keep in step instead of two.

**The formats and what hangs off them** (`ExportFormat`):

| | `paginated()` | `carriesChrome()` |
|---|---|---|
| `pdf` | yes | yes — logo, title, criteria block, footnote, `N of M` |
| `xlsx` · `csv` · `html` | no → `IS_IGNORE_PAGINATION` | no → the table alone |

Both flags are silent when wrong: pagination on in a spreadsheet carries a page break, a repeated
caption row and a page footer into the middle of the data; chrome on in a CSV drops a logo and a
footnote in as stray cells. `ExportFormatsTest` pins both.

**Custom views are PDF-only**, the rule carried over verbatim: a spreadsheet or a CSV is a data
extract and carries every column whatever a screen happens to be showing. The parameter is accepted
and ignored for those formats rather than refused, so `format` can be flipped without rewriting the
URL. An unknown column name **is** refused — a silently dropped column is a report that is wrong in
a way nobody notices.

**JSON straight into the fill** (`TableData.json`): the payload goes on
`JsonQueryExecuterFactory.JSON_INPUT_STREAM`, the design gets a `json` query naming the array, and
`$F{host}` binds to the JSON property. With a generated table *and* a JSON source there is no Java
type for the row anywhere — which is the case that makes caller-defined columns useful end to end.
Its limit: a field binds to a *property*, so a value that is an array or an object has no useful
rendering. The approval-status listing's `account` block is four lines that must arrive joined, a
decision only Java can make, so that document uses a `JRDataSource`; a flat table can skip the
model (`JsonTableDataTest`).

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
- **JasperReports asks the data source for *every* declared field**, not only the ones an
  expression references. So a field left over from a previous table shape is still requested at
  fill time and answered by a data source built from the current columns — a fill that fails on a
  column nobody can see. `TableDesigner` therefore makes the dataset's fields *exactly* the column
  list, which is also the contract in the other direction: **a template handed to the designer
  declares no fields of its own.**
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
- **The criteria block is still hand-written.** It is a multi-column subreport of labelled pairs,
  not a table of columns — "four to a row" is a page property and the pairs are not a rectangle —
  so it stays in its own jrxml. Only the listing's table is generated.
- **Fonts and brand images are shared with creed-report at build time** (`<resource>` entries in
  the pom pointing at `../creed-report/src/main/resources/`), not copied. ~36MB of TTF stays in one
  place in git, and a visual difference between the two PDFs cannot be a differently-instanced
  font. `JasperFontsTest` fails loudly if a fresh clone has not provisioned them — see the
  `creed-report` skill for the recipe.

## Open items

- **No fully caller-defined report yet.** Columns can be *subset and reordered* by a caller
  (`?columns=`) and rows can come from JSON, but nothing lets a caller define columns that the
  report does not already declare. That wants its own request contract and caps; creed-report's
  `/dynamic` (`headers` as comma-separated keys, `data` as a JSON array, `max-columns`/`max-rows`,
  400 not 500) is the precedent to copy.
- `TableColumn.valueClass` exists but every column is a `String` today; a numeric column also wants
  a `pattern` on the generated text field before it is useful, and that is what makes an `xlsx`
  column summable.
- The table's four column headers are English literals in `ApprovalStatusRenderer`, exactly as
  they are in the HTML twin. Localising them means four keys on both sides at once, or they drift.
- `creed.jasper.cache-templates` ships `false` (dev ergonomics). Anything load-bearing wants `true`.
