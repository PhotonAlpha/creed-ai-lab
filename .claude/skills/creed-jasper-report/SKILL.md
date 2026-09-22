---
name: creed-jasper-report
description: The creed-jasper-report module — a standalone Spring MVC app on HTTP 9110 context-path /jasper-report that renders the approval-status listing as PDF with JasperReports from .jrxml templates (multi-column criteria subreport, per-locale embedded font families, conditional styles). The engine-twin of creed-report's approval-status-export-pdf.html. Use for jrxml layout, JasperReports fills/exporters/fonts, or when comparing the two engines.
---

# creed-jasper-report

Standalone **Spring MVC** app, plain HTTP `9110`, context-path `/jasper-report`. No OAuth2, no mTLS, no config server, no database, no view layer — it answers PDF bytes and nothing else.

**It is a second rendering of one document, not a second document.** `creed-report`'s `approval-status-export-pdf.html` and this module's `jasper/approval-status.jrxml` both reproduce the printed listing in `creed-report/docs/template.jpg`. Everything they *can* share is shared — the JSON payload byte-for-byte, the Noto TTFs, the logo and the seal (build-time `<resource>` entries pointing into `../creed-report/src/main/resources/`) — so a visual difference between the two PDFs is the **layout engine** and never the inputs. Change one side's payload and you must change the other's. See [[creed-report]] for the twin.

## API

```
GET|POST /jasper-report/approval-status/export/pdf     the PDF
```

**Input-less by design**, like its twin: the payload is a JSON literal in `ApprovalStatusSamples` and the endpoint pins a *layout* down, so two calls a week apart differ only in the export timestamp. `Accept-Language` is the only thing that changes the document. Thirteen rows on purpose — the document has to be two pages for the repeated chrome and the page counter to be observable at all.

## Layout (`com.creed.jasper`)

| | |
|---|---|
| `api/ApprovalStatusJasperController` | the one endpoint; normalises the locale before the fill |
| `service/JasperReportService` | compile (cached) → fill → `JRPdfExporter`; the **only** class that touches the engine |
| `service/ApprovalStatusPdfService` | what a fill is given: data sources, parameters, `REPORT_LOCALE` |
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
| `columnHeader` | the light-blue table header, every page | the same wrapper table |
| `detail` | one row, `splitType="Prevent"` | `page-break-inside: avoid` |
| `pageFooter` | export stamp, seal, centred `N of M`, every page | `position: running()` + an `@page` margin box |

- **Repeating chrome is free here.** `pageHeader`/`pageFooter` repeat by themselves and the footer sits at the foot of the *page*, including a last page the content does not fill. Next door that took probing four mechanisms, two of which fail silently.
- **`counter(page)` is not special.** `$V{PAGE_NUMBER}` at `evaluationTime="Report"` is the total, so the counter is two ordinary text fields and can share a band with the seal. In CSS that counter exists only inside an `@page` margin box, a margin box cannot hold an image, and two cannot stack — which is the whole reason that footer is a running element.
- **Four-to-a-row is a *report* property.** The criteria block is a **multi-column subreport** (`columnCount="4" printOrder="Horizontal"`); the main report has one column and cannot also have four. The twin uses a `<table>` of percentage cells because Flying Saucer has no grid.
- **A row that grows**: `stretchType="RelativeToTallestObject"` on the three short cells, `textAdjust="StretchHeight"` on the account cell. CSS table cells do that by themselves.

## Per-locale typography — the Jasper-native answer

Both halves of what creed-report splits between a message key and a CSS overlay:

- **The face follows `REPORT_LOCALE`, in the extension, not the template.** `fonts/creed-fonts.xml` declares the family **`Creed Sans` four times** — Thai, SC, TC, then unrestricted — and `FontUtil` takes the first whose `supportsLocale()` says yes. Every element just says `fontName="Creed Sans"`; the `Base` style carries it as `isDefault="true"`, which is the exact job `body { font-family }` does in the twin. `Creed Sans Data` is a second family that is **always Latin** — the criteria *values*, i.e. creed-report's `pdf.font.family.criteriaValue`.
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

`docs/1.jpeg`–`4.jpeg` are reference photos of a **DynamicJasper** renderer that builds columns in Java and uses a jrxml only as the outer template. That earns its keep when the *caller* defines the columns (creed-report's `/dynamic` solves the same problem with a `DynamicTable`). This document's four columns and its criteria pairs are fixed — "labelled pairs, four to a row" and "this cell is four lines that belong together" are the whole reason it is not a dynamic table — so its layout lives in the jrxml where it can be read.

See [[creed-platform]] only for build/run basics (local Maven repo, JDK).
