---
name: creed-jasper-report
description: The creed-jasper-report module — a standalone Spring MVC app on HTTP 9110 context-path /jasper-report that renders the approval-status listing with JasperReports from .jrxml templates as PDF, XLSX, CSV or HTML. The table's columnHeader/detail bands are GENERATED at runtime from a column list via the JasperReports design API, and the whole renderer is a migration of the DynamicJasper one in docs/*.jpeg (custom view columns, format-driven pagination and chrome, CSV BOM, JSON-fed fills) with no DynamicJasper dependency. Also: a multi-column criteria subreport, per-locale embedded font families, conditional styles. The engine-twin of creed-report's approval-status-export-pdf.html. Use for jrxml layout, generated/dynamic tables, export formats, JasperReports fills/exporters/fonts, or when comparing the two engines.
---

# creed-jasper-report

Standalone **Spring MVC** app, plain HTTP `9110`, context-path `/jasper-report`. No OAuth2, no mTLS, no config server, no database, no view layer — it answers PDF bytes and nothing else.

**It is a second rendering of one document, not a second document.** `creed-report`'s `approval-status-export-pdf.html` and this module's `jasper/approval-status.jrxml` both reproduce the printed listing in `creed-report/docs/template.jpg`. Everything they *can* share is shared — the JSON payload byte-for-byte, the Noto TTFs, the logo and the seal (build-time `<resource>` entries pointing into `../creed-report/src/main/resources/`) — so a visual difference between the two PDFs is the **layout engine** and never the inputs. Change one side's payload and you must change the other's. See [[creed-report]] for the twin.

## API

```
GET|POST /jasper-report/approval-status/export        ?format=pdf|xlsx|csv|html   (default pdf)
                                                      &columns=type,status        (PDF only)
                                                      &widths=auto|fixed          (default auto)
GET|POST /jasper-report/approval-status/export/pdf    alias, so the side-by-side with
                                                      creed-report keeps working unchanged
GET|POST /jasper-report/approval-status/layout        ?columns=…&widths=…  the column plan as JSON:
                                                      min, max, width, %, wraps, breaksTokens
```

The module's own `README.md` documents this surface in full — parameters, media types, the layout
JSON field by field, and the error model; `ApprovalStatusJasperEndpointTest` is what keeps it true,
so change the two together.

**Input-less by design**, like its twin: the payload is a JSON literal in `ApprovalStatusSamples` and the endpoint pins a *layout* down, so two calls a week apart differ only in the export timestamp. What the query string chooses is the **rendering** — format, columns, `Accept-Language` — never the data. Bad values are **400**, never 500. Thirteen rows on purpose — the document has to be two pages for the repeated chrome and the page counter to be observable at all.

## Layout (`com.creed.jasper`)

| | |
|---|---|
| `api/ApprovalStatusJasperController` | the one endpoint; normalises the locale before the fill |
| `dynamic/TableColumn` · `TableDesign` · `TableDesigner` | the table, generated into the design at compile time |
| `dynamic/CellStyle` | what a generated cell looks like — in Java, not a jrxml `<style>` name |
| `dynamic/ColumnFit` · `TextMetrics` | widths measured from the content — `table-layout: auto`, weighted |
| `export/ColumnWidths` | `auto` (measured, the default) or `fixed` (the declared weights) |
| `dynamic/ReportShape` | template + columns + chrome + JSON query: what a compiled report *is*, and the cache key |
| `dynamic/TableRows` | the data source for a generated table, built from the same column list |
| `export/ExportFormat` · `ExportRequest` | the four formats, and what a caller asks for |
| `render/JasperTableRenderer` | the migrated pipeline: columns → shape → compile → fill → export |
| `render/TableData` | rows from a `JRDataSource`, or straight from JSON |
| `render/ApprovalStatusRenderer` | this document: its columns, payload and parameters |
| `service/JasperReportService` | load design → write table → compile (cached) → fill → export, per format |
| `service/ApprovalStatusSamples` | the JSON literal, creed-report's byte for byte |
| `service/JasperFonts` | logs the face each locale resolved to, at startup |
| `report/FieldDataSource` | `JRDataSource` over records, field names mapped to accessors explicitly |
| `domain/ApprovalStatusReport` | the same record shape as creed-report's model |
| `i18n/ReportLanguage`, `i18n/ExportTimestamp` | the one presentation axis; the footer's two stamps |

Resources: `jasper/approval-status.jrxml` + `jasper/approval-status-criteria.jrxml`, `fonts/creed-fonts.xml` + `jasperreports_extension.properties`, `i18n/jasper-messages*.properties`.

## The band map, and what each band buys

A 1:1 map onto the HTML twin's chrome — worth reading next to it, because the *reasons* invert:

| band | prints | the twin needs |
|---|---|---|
| `title` | logo, title, criteria block, record count — **once** | the `.statement-title-band` in the body |
| `pageHeader` | the logo alone, **pages 2+** | a `.page-frame` `<thead>` + `-fs-table-paginate` |
| `columnHeader` **(generated)** | the light-blue table header, every page | the same wrapper table |
| `detail` **(generated)** | one row, `splitType="Prevent"` | `page-break-inside: avoid` |
| `pageFooter` | export stamp, seal, centred `N of M`, every page | `position: running()` + an `@page` margin box |

- **Repeating chrome is free here.** `pageHeader`/`pageFooter` repeat by themselves and the footer sits at the foot of the *page*, including a last page the content does not fill. Next door that took probing four mechanisms, two of which fail silently.
- **`counter(page)` is not special.** `$V{PAGE_NUMBER}` at `evaluationTime="Report"` is the total, so the counter is two ordinary text fields and can share a band with the seal. In CSS that counter exists only inside an `@page` margin box, a margin box cannot hold an image, and two cannot stack — which is the whole reason that footer is a running element.
- **Four-to-a-row is a *report* property.** The criteria block is a **multi-column subreport** (`columnCount="4" printOrder="Horizontal"`); the main report has one column and cannot also have four. The twin uses a `<table>` of percentage cells because Flying Saucer has no grid.
- **A row that grows**: `stretchType="RelativeToTallestObject"` on the three short cells, `textAdjust="StretchHeight"` on the account cell. CSS table cells do that by themselves.

## The generated table — what replaces DynamicJasper

`approval-status.jrxml` declares **no `columnHeader`, no `detail` and no fields**. `TableDesigner`
writes them into the `JasperDesign` before it is compiled, from a `List<TableColumn>`:

```java
TableColumn.of("account", "Account", 20).multiline().styled(ACCOUNT_CELL)   // a CellStyle, not a name
```

DynamicJasper is the library that normally does this, and it is the architecture the reference
photos in `docs/` show (one jrxml for page + chrome, the table built in Java). No release in years,
its own layout-manager and style model layered over JasperReports', a second API surface in the
build — for what is underneath a few dozen calls to the **JasperReports design API**
(`JRDesignBand`, `JRDesignTextField`, `JRDesignField`). `TableDesigner` is those calls: no
dependency, JasperReports' own compile-time errors, nothing in between to go stale.

**The division of labour.** The jrxml keeps the page box, the chrome bands and the **chrome's**
styles — the title, the count line, the footnote, the counter, the locale-conditional weights. The
table is Java on both axes: the column list carries fields, captions and widths, and
`dynamic/CellStyle` carries what a cell looks like — face, size, weight, colours, padding, rules —
which `TableDesigner.apply` writes onto the generated element.

**The styles used to be names.** A cell carried `setStyleNameReference("TableHeader")` and the
template answered at fill time. That was reversed deliberately: **a style reference is a string
nothing checks**, and JasperReports resolves an unknown one to *nothing* — it prints the cell in
the default face, so a renamed or deleted style is not a compile error, not a fill error, just a
table quietly rendered at the wrong size in cells measured for another. `TableHeader`, `TableCell`,
`AccountCell` and `StatusCell` no longer exist in `approval-status.jrxml`; the palette is in
`ApprovalStatusRenderer` beside the column widths it has to agree with. The price: an edited jrxml
still takes effect on the next request (`cache-templates=false`) but that no longer covers the
table's look, only its chrome.

Two things stay out of `CellStyle`: the table's outer left/right rule (`TableDesign.edgeColor`),
which belongs to *being the first or last column* — something a per-element style cannot know — and
Identity-H + embedding, written onto every cell because without them the CJK and Thai glyphs never
reach the PDF. The **family name** is still engine-resolved: a cell asks for `"Creed Sans"` and the
font extension picks the face by locale.

Three things it does that are easy to get wrong by hand:

- **Weights, not widths** — normalised over the design's `columnWidth` with the rounding remainder
  to the last column, so the row ends exactly on the right margin whatever the paper. Since the fit
  arrived (below) a weight is no longer a width at all: `ColumnFit` measures the content and hands
  the designer the fitted widths *as* weights, so nothing under it changed.
- **Stretch** — a `multiline()` column gets `textAdjust="StretchHeight"`, and every *other* cell
  gets `stretchType="RelativeToTallestObject"` or its bottom rule stops at the designed height and
  the row looks torn. The design-API spelling of what a CSS table cell does for free.
- **Fields are replaced, not merged** — see the landmine below.

`TableRows.of(rows, columns)` builds the data source from the **same** list, so the declared
fields, the cells referencing them and the source answering them cannot drift. A key a row lacks is
an empty cell (normal for a caller-shaped table); an unmapped *field* throws.

The compile cache is keyed on the template **and** the columns — two shapes of one template are two
compiled reports. `TableDesignerTest` pins the weights, the replacement semantics, the refusals
(duplicate field, empty list) and that two different column lists really produce two documents.

**Still hand-written:** the criteria subreport. It is labelled pairs four to a row, not a rectangle
of columns, so a column model would not express it.

## The migrated renderer — `docs/1.jpeg`–`4.jpeg`, without the library

`JasperTableRenderer` is the `DynamicJasperRendererAbstract` in those photos, ported onto this stack. Same responsibilities, same template-method shape:

| `DynamicJasperRendererAbstract` | here |
|---|---|
| `render(ExportRequest, json, params)` | `render(ExportRequest)` |
| `generateMainReport(...)`, `setTemplateFile` only for PDF | `ReportShape.document` vs `.tableOnly`, off `ExportFormat.carriesChrome()` |
| `setResourceBundle` / `setDefaultEncoding` / margins | declared once in the jrxml |
| `setReportLocale(locale)` | the `REPORT_LOCALE` parameter |
| `generateBaseTable()` — margins 0, full page width, no detail split | `TableDesign` + `TableDesigner.stripChrome` + normalised weights + `splitType="Prevent"` |
| `generateTable(locale, req)` + `getCustomViewColumns(...)` for PDF | `columnsFor(request)`, same rule |
| `generateBaseColumn(.., firstCol, lastCol)` — four `Style` variants per position | one `CellStyle` per column + two edge pens |
| `IS_IGNORE_PAGINATION` for non-PDF | `ExportFormat.paginated()` |
| `exportReport(...)` + CSV BOM property | `JasperReportService.export(..., format)` + `setWriteBOM` |
| `JsonQueryExecuterFactory.JSON_INPUT_STREAM` | `TableData.json(...)` |

**A renderer is a value over one report, not a bean** — `new ApprovalStatusRenderer(jasper, report, now).render(request)`. Payload and export instant are final fields, so the hooks just read them; the object is immutable, needs no synchronisation, and one per request costs nothing because the only expensive thing in the pipeline is the compiled report, cached in the shared `JasperReportService`. (The reference puts the payload on the parameter map instead.)

**Changed on purpose.** The reference builds a table's look out of four `Style` objects *per column position*, because DynamicJasper has no other way to draw a table's outer border. Here a column carries one `CellStyle` whatever position it lands in and the generator adds the two edge pens — the same end result from a model a caller can reason about. (The look lived in the jrxml as named styles for a while; see "The division of labour" above for why it moved into Java.) And the non-PDF path starts from the **same** template with its chrome stripped, not from a second report built from nothing — one file to keep in step instead of two.

**Two flags hang off the format, and both are silent when wrong:**

| | `paginated()` | `carriesChrome()` |
|---|---|---|
| `pdf` | yes | yes — logo, title, criteria block, footnote, `N of M` |
| `xlsx` · `csv` · `html` | no → `IS_IGNORE_PAGINATION` | no → the table alone |

Pagination on in a spreadsheet carries a page break, a repeated caption row and a page footer into the middle of the data; chrome on in a CSV drops a logo and a footnote in as stray cells. `ExportFormatsTest` pins both. `JRXlsxExporter` writes the OOXML itself, so **no POI** is needed — unlike the legacy `.xls` exporter.

**Custom views are PDF-only**, carried over verbatim: an extract carries every column whatever a screen is showing. The parameter is accepted and ignored for extract formats (so `format` can be flipped without rewriting the URL); an unknown column name is *refused*, because a silently dropped column is a report that is wrong in a way nobody notices.

**JSON straight into the fill** (`TableData.json`): payload on `JSON_INPUT_STREAM`, a `json` query on the design naming the array, `$F{host}` bound to the JSON property. With a generated table *and* a JSON source there is no Java type for the row anywhere. Limit: a field binds to a *property*, so an array or object value has no useful rendering — the `account` block is four lines that must arrive joined, a decision only Java can make, so that document keeps a `JRDataSource`.

## A column's left/right spacing — three layers, and no margin among them

**A `<jr:table>` column has no margin.** Column widths sum to exactly the table width
(`StandardColumn.setWidth`) and each cell's text element is `x=0` filling the cell
(`TableDesigner.place`), so there is no gutter. Everything visible between two columns is one of:

| layer | set by | value lives in |
|---|---|---|
| cell inner padding | `TableDesigner.apply` → `setLeftPadding`/`setRightPadding` | `CellStyle.padding(l, r, t, b)` — `ApprovalStatusRenderer.HEADER`/`.CELL`, **6 left / 3 right** |
| table's outer rules (first column's left, last column's right) | `TableDesigner.place`, `index == 0` / `index == count - 1` | `CellStyle.RULE_WIDTH` 0.5pt + `TableDesign.edgeColor` |
| table's distance from the paper | the jrxml page box | `leftMargin="40" rightMargin="40"`, `columnWidth="515"` |

- Padding is **not** free width: `ColumnFit` adds `left + right` to every measured min and max, so
  changing it moves the fitted widths.
- `AccountCell`/`StatusCell` inherit the padding — they are withers off `CELL`.
- The `width="515"` on the jrxml's `<componentElement key="listing">` is a skeleton value
  `TableDesigner.write` overwrites with `design.getColumnWidth()` on every compile (a stripped
  design has zeroed margins and a full-page column width). Editing it changes nothing.
- The outer rules stay out of `CellStyle` on purpose: one style lands in every column, and "am I
  first or last" is not something a per-element style can know.

## Column widths — `table-layout: auto`, which JasperReports does not have

A column's `weight` was a share of the page, hand-measured once against the longest string it
happened to carry — and wrong the moment the shape changed: weights tuned for eight columns make
three of them a third of the page each, and a payload whose references grew two characters breaks
inside a token with nobody the wiser. `ColumnFit` measures instead, and `?widths=auto|fixed` picks
which answer is used (auto is the default; `/approval-status/layout` returns the plan as JSON so the
two can be compared without reading a PDF).

- **The measurement is the real face.** `TextMetrics` pulls the AWT font out of the same extension
  the fill resolves `Creed Sans` through (`FontUtil.getAwtFontFromBundles`), so the numbers are what
  the PDF will draw. A per-character average is wrong exactly where the Noto faces differ, which is
  where this module's bugs live. A clone without the TTFs falls back to `SansSerif` and still
  renders — the same bargain `JasperFonts` makes.
- **min is the number that matters.** Below its widest unbreakable token a cell does not wrap, it
  breaks *inside* a word: `BK260000000` / `1`. Whitespace is the only assumed break opportunity,
  which is conservative for CJK and Thai (Jasper wraps those between characters) and therefore safe.
- **Distribution is CSS's, times the weight.** Everything fits → each gets its max, slack shared by
  weight. Needs squeezing → each gets its min, the rest shared by `(max − min) × weight`. Nothing
  fits → mins scaled down. The `× weight` is the one departure: a browser has no notion of a column
  mattering more, and plain CSS gives this document's account block 11pt less, a sixth line and an
  extra page. **Weights stopped being widths and became priorities**; equal weights give the
  browser's answer exactly.
- **Fitting is a pre-pass.** `ColumnFit.columns()` returns the same columns with the fitted widths
  in the `weight` slot, so `TableDesigner`, `ReportShape` and the cache are untouched — and a
  chrome-stripped spreadsheet gets the same proportions re-normalised over its wider content area
  rather than a second fit.
- **It needs the rows before the compile**, which is why `JasperTableRenderer.rowText` sits beside
  `data`: a `JRDataSource` is single-pass and measuring through it would hand the fill an exhausted
  source. The fitted widths are part of the compile-cache key, so changing data means compiling per
  distinct layout — the reason `fixed` is kept rather than deprecated.

## Per-locale typography — the Jasper-native answer

Both halves of what creed-report splits between a message key and a CSS overlay:

- **The face follows `REPORT_LOCALE`, in the extension, not the template.** `fonts/creed-fonts.xml` declares the family **`Creed Sans` four times** — Thai, SC, TC, then unrestricted — and `FontUtil` takes the first whose `supportsLocale()` says yes. Every element just asks for `Creed Sans` — the chrome by inheriting the `Base` style's `isDefault="true"`, which is the exact job `body { font-family }` does in the twin, and a generated table cell by carrying the name in its `CellStyle`. `Creed Sans Data` is a second family that is **always Latin** — the criteria *values*, i.e. creed-report's `pdf.font.family.criteriaValue`.
- **The weight follows the script, in `<conditionalStyle>`.** `"zh".equals($P{REPORT_LOCALE}.getLanguage())` bolds `.criteria-label`; `"th"` un-bolds the footer title. That is creed-report's `static/css/locale/<tag>/report-pdf.css` said in jrxml, and the same reason applies: a weight per selector has nowhere to live in a `.properties` file.
- **One axis, not two.** creed-report varies on country × language; the approval-status listing is fixed brand chrome there, so the only axis it really varies on is the language. Consequence: the Buddhist era is keyed on the **country** next door (`?country=th&lang=en` still shows 2569) and on the **language** here.

## Landmines

- **A text element shorter than its FACE's line height prints NOTHING** — no warning, no clipped glyph. The faces differ (8.5pt: Latin 11.58 / SC·TC 12.31 / Thai 12.84; 14pt: 19.07 / 20.27 / 21.15), so a 12pt caption is *right in English and blank in Chinese and Thai*. This shipped once: the title and every criteria caption vanished in two languages while the English proof read perfectly. Size single-line elements at **~1.6 × font size**; `ApprovalStatusJasperPdfTest.everyCaptionSurvivesTheTallerScripts` renders th/zh-CN/zh-TW to hold the line. The twin cannot hit this — a CSS line box grows.
- **The `title` band prints BEFORE the `pageHeader`.** `JRVerticalFiller` fills title then page header, so a logo living only in the page header lands *under* the title. Page one's logo is part of the title band; the page header carries `printWhenExpression: $V{PAGE_NUMBER} > 1`.
- **Bands are declared in the schema's order** (background, title, pageHeader, columnHeader, detail, columnFooter, pageFooter, …), which is not the print order. Out of sequence the compile fails naming only the element it tripped over.
- **XML comments cannot contain `--`** — the repo's em-dash-in-ASCII habit makes a jrxml unparseable, and the error names no file position you can act on. Both templates use `—` / `─`.
- **ecj must be overridden.** JR 6.21.3 pins `org.eclipse.jdt:ecj` 3.21.0 (2019), which cannot read a modern JDK's class files; the runtime expression compile dies on the first **fill** with `Unsupported class file major version`, naming neither JasperReports nor the report. The module pom pins a current ecj.
- **The extension registry id is not free-form**: `net.sf.jasperreports.extension.registry.factory.`**`simple.font.families`**. A different id registers a factory that finds nothing, and the only symptom is `JRFontNotFoundException: Font "Creed Sans" is not available to the JVM` at the first fill.
- **A family with no `<locales>` supports every locale**, so it goes **last**. Declared first it wins every lookup and Thai/CJK silently become Noto Sans — which `JasperFontsTest` is there to catch.
- **JasperReports does not synthesize bold**: a bold element in a family with only a normal face renders nothing. Every family declares `<bold>`.
- **No per-glyph fallback**, same as the twin: a run is one face, and a glyph it lacks comes out blank rather than substituted.
- **Records are not beans.** `JRBeanCollectionDataSource` reads `getXxx()` through commons-beanutils; a record has `label()`. `FieldDataSource` maps jrxml field names to accessors and throws *by name* on an unmapped field instead of printing a blank cell.
- **JasperReports asks the data source for *every* declared field**, not only the ones an expression references. A field left over from a previous table shape is therefore still requested at fill time and answered by a data source built from the current columns — a fill that fails on a column nobody can see. `TableDesigner` makes the dataset's fields *exactly* the column list, which is the contract in the other direction too: **a template handed to it declares no fields of its own.**
- **Jasper strips leading whitespace at line start**, so the classic two-field counter renders `1of 2`. The separator gets a **centred box of its own**, which also stops caring whether it is ` of `, ` 页，共 ` or ` จาก `.
- **`ResourceBundle.getBundle` falls back to the JVM default locale** before the base bundle. `ReportLanguage` normalises every request to one of six languages so the lookup is always exact; creed-report rules the same trap out with `fallbackToSystemLocale=false`, which Jasper has no equivalent of.

## Build, run, iterate

```bash
mvn -q -pl creed-jasper-report -am -DskipTests install
mvn -pl creed-jasper-report spring-boot:run        # no -Dspring-boot.run.workingDirectory: no file: SSL bundle
mvn -pl creed-jasper-report test -Dtest=ApprovalStatusJasperPdfTest -Dpdf.sample.dir="$PWD/tmp/jasper"
```

The sample dump is the iteration loop — one PDF per language (`en`, `zh-CN`, `zh-TW`, `th`, `ms`, `vi`) through the real engine, bundles and fonts, without Tomcat. To eyeball one without a viewer: `sips -s format png -Z 1400 tmp/jasper/approval-status-jasper-th.pdf --out tmp/th.png`.

- **JasperReports 6.21.3**, not 7: one jar, PDF exporter included, and it renders onto the same **OpenPDF 1.3.32** that creed-report's Flying Saucer fork uses — so a difference between the two PDFs is the layout engine, never the PDF writer. JR 7 splits the exporter into `jasperreports-pdf` under `net.sf.jasperreports.pdf.*`.
- **Templates compile at runtime and are cached** by classpath location. `creed.jasper.cache-templates=false` (the shipped default) turns the cache off so an edited jrxml takes effect on the next request — the bargain `spring.thymeleaf.cache: false` makes next door. Precompiling to `.jasper` at build time is deliberately *not* done: it puts a build step between the file you edit and the PDF you look at.
- **Fonts come from creed-report.** A fresh clone that has not provisioned them builds fine and renders everything in Helvetica; `JasperFontsTest` is what fails instead of a user's download. The provisioning recipe is in the [[creed-report]] skill.

## Notes

`docs/1.jpeg`–`4.jpeg` are the reference photos the renderer was migrated from; the mapping table above is that migration. **DynamicJasper is not a dependency and adding it back would be a regression** — last release Aug 2023, it pins jasperreports to the range `[6.20.1, 6.20.4]`, and it drags in barbecue, batik-bridge 1.8 (2015), jxl and xmlgraphics-commons for a table this module draws with two dozen design-API calls.

**Not yet:** a *fully* caller-defined report. Columns can be subset and reordered (`?columns=`) and rows can come from JSON, but a caller cannot declare a column the report does not already have. That wants its own request contract and caps; creed-report's `/dynamic` (`headers` as comma-separated keys, `data` as a JSON array, `max-columns`/`max-rows`, 400 not 500) is the precedent to copy.

See [[creed-platform]] only for build/run basics (local Maven repo, JDK).
