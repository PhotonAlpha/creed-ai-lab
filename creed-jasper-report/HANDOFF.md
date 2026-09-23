# creed-jasper-report — handoff

The **approval-status listing** again, this time laid out by **JasperReports** from a `.jrxml`
instead of by Flying Saucer from Thymeleaf + CSS 2.1. Same printed document as
`creed-report/docs/template.jpg`, same fonts, same logo and seal — the module exists so the two can
be put side by side and the only difference is the layout engine.

> **The table is now EIGHT columns and creed-report's is still four.** The payload here grew
> `customerReference`, `currency`, `amount` and `valueDate`; the twin's has not. Until it does, the
> two PDFs are comparable on their **chrome** (title band, criteria block, footnote, page counter)
> but not row for row. Everything else about the side-by-side still holds.

Standalone: plain HTTP **9110**, context path **`/jasper-report`**. No OAuth2, no mTLS, no config
server, no database.

**The HTTP API is documented in [`README.md`](README.md)** — endpoints, parameters, media types,
the layout JSON and the error model, with `ApprovalStatusJasperEndpointTest` asserting every line of
it. What follows here is the inside.

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
curl -sS -o tmp/approval-fixed.pdf "$B?widths=fixed"           # declared weights, not a content fit
curl -sS "${B%/export}/layout" | jq                            # the column plan, as JSON
curl -sS "${B%/export}/layout?columns=type,bankReference,status&widths=fixed" | jq
curl -sS -o tmp/approval-4col.pdf \
  "$B?columns=type,bankReference,account,status"                # the pre-eight-column listing
curl -sS -H 'Accept-Language: th' -o tmp/approval-th.pdf "$B"
```

`GET|POST /approval-status/export` renders it and `GET|POST /approval-status/layout` explains how
the columns came out that way (`/export/pdf` is kept as an alias so the
side-by-side with creed-report keeps working). It takes **no data input** — the payload is a JSON
literal in `ApprovalStatusSamples` — so two calls a week apart differ
only in the export timestamp. What the query string chooses is the *rendering*: `format`,
`columns`, `widths`, and `Accept-Language`. Bad values are **400**, never 500.

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
| `dynamic/CellStyle` | what a generated cell looks like — face, size, weight, colours, padding, rules |
| `dynamic/ColumnFit` · `TextMetrics` | column widths measured from the content — `table-layout: auto` |
| `export/ColumnWidths` | `auto` (measured) or `fixed` (the declared weights) |
| `dynamic/TableRows` | the data source for a generated table, built from the same column list |
| `export/ExportFormat` · `ExportRequest` | the formats, and what a caller asks for |
| `render/JasperTableRenderer` | the pipeline: columns → shape → compile → fill → export |
| `render/TableData` | rows from a `JRDataSource`, or straight from JSON |
| `render/ApprovalStatusRenderer` | this document: its eight columns, its payload, its parameters |
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
TableColumn.of("account", "Account", 20).multiline().styled(ACCOUNT_CELL)
```

DynamicJasper is the library that normally does this — and it is the architecture the reference
photos in `docs/` show: one jrxml for the page and the chrome, the table built in Java. It has had
no release in years, layers its own layout managers and style model over JasperReports' own, and
pulls a second API surface into the build for what is underneath a few dozen calls to the
**JasperReports design API**. `TableDesigner` is those calls: no dependency, JasperReports' own
error messages, nothing in between to go stale.

**The division of labour:**

| the jrxml | Java |
|---|---|
| page box, chrome bands, and the **chrome's** styles — the title, the count line, the footnote, the counter, the locale-conditional weights | the table: which fields exist, their captions, their share of the width, **and what each cell looks like** |
| edited and reloaded, no build (`cache-templates=false`) | compiled |

**The table's styles are `CellStyle` values in Java, not `<style>` names in the jrxml.** A generated
cell used to carry `setStyleNameReference("TableHeader")` and let the engine resolve it at fill
time; `TableDesigner.apply` now writes the face, size, weight, colours, padding and rules straight
onto the element. The reason is that a style reference is **a string nothing checks** — JasperReports
resolves an unknown one to *nothing* and prints the cell in the default face, so a renamed or
deleted style is not a compile error, not a fill error, just a table that is quietly 9pt in cells
measured for 8pt. The four styles (`TableHeader`, `TableCell`, `AccountCell`, `StatusCell`) are gone
from `approval-status.jrxml`; the palette lives in `ApprovalStatusRenderer`, next to the column
widths it has to agree with.

What that costs: an edited jrxml still takes effect on the next request, but that no longer covers
the table's look — only its chrome. `TableDesignerTest.theCellsCarryTheirStyleRatherThanAStyleName`
asserts what a generated cell now carries, which was not assertable when it carried a string.

Two things stay out of `CellStyle`: the table's outer left/right rule (`TableDesign.edgeColor`) —
that belongs to *being the first or last column*, which a per-element style cannot know — and
Identity-H + embedding, which the designer writes onto every cell because without them the CJK and
Thai glyphs never reach the PDF at all.

The **font family** is still resolved by the engine: every cell asks for `"Creed Sans"`, and
`fonts/creed-fonts.xml` declares that name four times, three of them locale-restricted. What moved
into Java is which family, size and weight a cell asks for — not the per-locale resolution.

Three more things it does that are easy to get wrong by hand:

- **Weights, not widths.** `12/13/13/20/7/12/12/11` are normalised over the design's `columnWidth`
  and the rounding remainder goes to the last column, so the row ends exactly on the right margin
  whatever the paper.
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

## Where a column's left/right spacing comes from

**Three layers, in three different places** — cell padding (`CellStyle.padding`, written by
`TableDesigner.apply`), the table's outer rules (`TableDesigner.place`, `TableDesign.edgeColor`) and
the jrxml page box (`leftMargin`/`rightMargin`). A `<jr:table>` column has **no margin**: the widths
sum to the table's width and each cell's element is `x=0` at full width, so those three are the only
sources there are.

The full version, with what each layer costs to change, is in
[`README.md`](README.md#where-a-columns-leftright-spacing-comes-from) — it is the question that gets
asked most, so it is documented where people look first rather than here.

## Eight columns on A4, and what they cost

The listing printed four columns until it printed eight, and the interesting part is that only the
**column list** is a list: everything else that had to move lives in the jrxml.

| moved | from | to | because |
|---|---|---|---|
| table font size | 9pt (inherited) | **8pt** on all four cell styles | at ~64pt a column, `BK2600000001` needs ~71pt at 9pt and breaks *inside the token* |
| cell padding | 8 / 4 | **6 / 3** | 4pt of width per column, for free |
| caption band | 22pt | **48pt** (`TableDesign`) | captions wrap to three lines, and a band short of the last line **drops** it |
| the styles themselves | four `<style>`s in the jrxml | four `CellStyle`s in `ApprovalStatusRenderer` | a style name is a string nothing checks; see above |
| account line spacing | 1.35 | **1.25** | the block wraps to five lines instead of four, and leading multiplies |

Still two pages, 6 rows on page one in English and 5 in Thai. The column weights are measured
against the longest string each column actually carries — the table in `ApprovalStatusRenderer`'s
javadoc — and taking weight off any of the five short ones puts an unreadable mid-token break back.

**48pt, not 42, and that is the landmine again.** 42pt fits three 8pt lines of Latin (3 x 10.90 + 6
of padding = 38.7) and is 0.3pt short of three lines of Thai (3 x 12.09 + 6 = 42.3), so the header
printed `Value / Placement Date` in English and `Value /` + `Placement` in Thai — the third line
dropped, no warning, in a document whose English proof was perfect. This was caught by rendering
`th`, not by reading the file. `ApprovalStatusJasperPdfTest.everyCaptionSurvivesTheTallerScripts`
now asserts the whole caption row in th/zh-CN/zh-TW.

**A column list can be subset without touching the jrxml; it cannot be lengthened without it.**
That is the real boundary of the generated-table split, and `?columns=` still works on all eight —
`?columns=type,bankReference,account,status` gives the old listing back, at the new type size.

Tests that had to change, and why they are better for it: PDF assertions now compare **flattened**
text (`\s+` collapsed), because a wrapped caption is still a printed caption and asserting on raw
extraction pins line breaks rather than content. And the "chrome is stripped" witness in
`ExportFormatsTest` moved from `Customer Reference` to **`Payer / Payee`** — the one criterion the
eight-column table has no column for, so it can still only come from the criteria block.

## Column widths: `auto` (default) or `fixed`

A `TableColumn`'s `weight` was a hand-tuned share of the page, measured once against the longest
string the column happened to carry. That does not survive a change of shape: the weights tuned for
eight columns make three of them a third of the page each, and a payload whose references grew two
characters breaks inside a token with nobody the wiser. `ColumnFit` measures instead.

```
GET /approval-status/layout                                   the plan, fitted
GET /approval-status/layout?widths=fixed                      the plan, declared weights
GET /approval-status/layout?columns=type,bankReference,status three columns, fitted
GET /approval-status/export?widths=fixed                      the PDF, declared weights
```

**How it measures.** `TextMetrics` resolves the same AWT face the fill will use, out of the same
font extension (`FontUtil.getAwtFontFromBundles`), so a measurement is what the PDF will draw — the
Noto faces are not metrically compatible and a per-character average is wrong exactly where it
matters. Per column: **min** = the widest unbreakable token (below this a cell does not wrap, it
breaks *inside* a word), **max** = the widest full line, caption measured in the header style and
cells in theirs, padding included.

**How it distributes** — CSS's algorithm, with one departure:

| | |
|---|---|
| Σmax ≤ available | everyone gets their content; the slack is shared **by weight** |
| Σmin ≤ available < Σmax | everyone gets their min; the rest is shared by `(max − min) × weight` |
| available < Σmin | mins scaled down — the table is wider than the paper, and something will break |

The `× weight` is the departure. A browser has no notion of one column mattering more, so plain CSS
gives the account block and the value date the same claim per point wanted — and this document's
account block then loses 11pt, wraps to a sixth line and takes the listing onto another page.
**The weights did not go away; they stopped being widths and became priorities.** A caller that
leaves them equal gets the browser's answer exactly.

Eight columns, en, 515pt — the measurement is shared, only the answer differs:

| column | min | max | auto | fixed |
|---|---|---|---|---|
| type | 57 | 116 | **76** | 62 |
| bankReference | 65 | 72 | 67 | 67 |
| customerReference | 66 | 91 | **74** | 67 |
| account | 55 | 139 | 104 | 103 |
| currency | 26 | 26 | **26** | 36 |
| amount | 50 | 50 | **50** | 62 |
| valueDate | 52 | 103 | **68** | 62 |
| status | 50 | 50 | **50** | 56 |

Auto takes the slack out of the three columns that cannot use it (`CCY`, `Amount`, `Status` have no
break opportunity at all, so a point more is a point wasted) and gives it to the wrapping captions.
Still two pages, six rows on page one — the fit costs nothing here and removes the hand-tuning.

**What it costs.** The fit needs the rows as text before the report is compiled, which is why
`JasperTableRenderer.rowText` exists next to `data` — a `JRDataSource` is single-pass and measuring
through it would leave the fill an exhausted source. And fitted widths are part of the compile-cache
key, so a report served over changing data compiles per distinct layout. That is the reason `fixed`
is not deprecated: it is the mode that does not need the data.

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
- The table's eight column headers are English literals in `ApprovalStatusRenderer`, exactly as
  they are in the HTML twin. Localising them means eight keys on both sides at once, or they drift.
  `CCY` is deliberately an abbreviation and not "Currency": the column is 36pt because its value is
  three characters, and the full word needs 47pt and breaks as `Curre` / `ncy`.
- **creed-report's payload has not grown the four new fields.** Doing it is mechanical — the same
  four keys in its `ApprovalStatusReportController` JSON, four components on its model record, four
  `<td>`s in `approval-status-export-pdf.html` and its `<colgroup>` widths — and until it happens
  the two engines are rendering two different tables.
- `creed.jasper.cache-templates` ships `false` (dev ergonomics). Anything load-bearing wants `true`.
