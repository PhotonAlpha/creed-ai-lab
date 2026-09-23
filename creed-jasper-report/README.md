# creed-jasper-report — HTTP API

Renders the **approval-status listing** with JasperReports and answers it as PDF, XLSX, CSV or
HTML. Standalone: plain HTTP, no OAuth2, no mTLS, no database.

```
http://127.0.0.1:9110/jasper-report
```

Port is `CREED_JASPER_PORT` (default `9110`); the context path `/jasper-report` is fixed. Every path
below is relative to it, and every example uses it as `$B`:

```bash
B=http://127.0.0.1:9110/jasper-report
```

**The endpoint takes no data.** The payload is a fixed sample built into the app, so two calls a
week apart differ only in the export timestamp. What the query string chooses is how the document is
**rendered** — its format, its columns, how those columns are sized, and its language — never what
it says. That is what makes it usable as a reference render and as a regression test.

| | |
|---|---|
| `GET·POST /approval-status/export` | the document, in the format asked for |
| `GET·POST /approval-status/export/pdf` | alias for `?format=pdf`, kept so the side-by-side with creed-report keeps working |
| `GET·POST /approval-status/layout` | **how the columns were sized, as JSON** — no document |

GET and POST behave identically everywhere; there is no request body.

---

## `GET·POST /approval-status/export`

Answers the document as a file attachment.

### Parameters

| name | values | default | notes |
|---|---|---|---|
| `format` | `pdf` · `xlsx` · `csv` · `html` | `pdf` | case-insensitive |
| `columns` | field names, comma-separated | every column | **PDF only** — see below |
| `widths` | `auto` · `fixed` | `auto` | how column widths are decided |
| `Accept-Language` | header, not a parameter | `en` | see [Languages](#languages) |

### Response

| format | `Content-Type` | contains |
|---|---|---|
| `pdf` | `application/pdf` | the whole document: logo, title, criteria block, table, footnote, page counter |
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | the table only |
| `csv` | `text/csv;charset=UTF-8` | the table only, with a UTF-8 BOM so Excel opens it as UTF-8 |
| `html` | `text/html;charset=UTF-8` | the table only |

```
Content-Disposition: form-data; name="attachment";
                     filename="creed-approval-status-jasper-20260924-101530.pdf"
```

The timestamp in the filename is ASCII and sortable, never the locale's own date format.

**Only the PDF carries the document around the table.** A spreadsheet, a CSV and an HTML fragment
are *extracts*: no title band, no criteria block, no footnote, no page furniture — and no
pagination, so a page break, a repeated caption row and a page footer never land in the middle of
the data.

### Columns

Eight, in this order. `columns` picks a subset **and reorders it**, in the caller's order:

| field name | caption |
|---|---|
| `type` | Transaction / Deposit Type |
| `bankReference` | Bank Reference |
| `customerReference` | Customer Reference |
| `account` | Account |
| `currency` | CCY |
| `amount` | Amount |
| `valueDate` | Value / Placement Date |
| `status` | Status |

```bash
curl -o listing.pdf "$B/approval-status/export?columns=type,bankReference,status"
```

**`columns` is honoured for the PDF and ignored for every extract.** An extract is expected to carry
every column whatever a screen happens to be showing; the parameter is accepted rather than refused
there, so `format` can be flipped without rewriting the URL.

**An unknown field name is refused, not dropped** — a silently missing column is a report that is
wrong in a way nobody notices.

### Widths

| | |
|---|---|
| `auto` (default) | widths **measured from the content**, like a browser's `table-layout: auto`. Every column keeps its longest unbreakable token whole, and the slack goes where it is wanted. Works at three columns and at eight without anyone tuning a number |
| `fixed` | the weights the report declares, normalised over the page. Reproducible to the point, and the only mode that needs no measurement |

`widths` changes the compiled report for **every** format, so an XLSX gets fitted column widths too.
A CSV has no column widths at all, so its bytes are the same either way.

Ask `/approval-status/layout` what a mode will do before rendering anything.

---

## `GET·POST /approval-status/layout`

The column plan, as JSON. Same measurement the export makes, without the document — call it twice to
compare the two modes, or with different `columns` lists to see how the table adapts.

### Parameters

| name | values | default |
|---|---|---|
| `columns` | field names, comma-separated | every column |
| `widths` | `auto` · `fixed` | `auto` |
| `Accept-Language` | header | `en` |

Always answers `application/json`. The numbers are points at the **PDF's** 515pt content area (A4
less the template's 40pt margins), because that is the format whose widths anyone reads.

### Response

```json
{
  "widths": "auto",
  "language": "en",
  "available": 515,
  "columnCount": 8,
  "total": 515,
  "everythingFits": false,
  "columns": [
    {
      "property": "bankReference",
      "header": "Bank Reference",
      "weight": 13,
      "min": 65,
      "max": 72,
      "width": 67,
      "percent": 13.0,
      "wraps": true,
      "breaksTokens": false
    }
  ]
}
```

| field | meaning |
|---|---|
| `available` | the content width being fitted, in points |
| `total` | the fitted widths added up — always equals `available` |
| `everythingFits` | whether every column got its full content width, i.e. nothing in the table wraps |
| `weight` | the column's **declared** weight: a width under `fixed`, a priority under `auto` |
| `min` | below this the cell does not wrap, it breaks **inside a word** (`BK260000000` / `1`) |
| `max` | at this width nothing in the column wraps at all |
| `width` | what this mode gave it |
| `percent` | `width` as a share of `available`, one decimal |
| `wraps` | `width < max` — the column wraps, which is normal |
| `breaksTokens` | `width < min` — the column splits a token across two lines. **This one is a bug in the layout, not a style choice** |

```bash
curl -s "$B/approval-status/layout"                                         # 8 columns, fitted
curl -s "$B/approval-status/layout?widths=fixed"                            # 8 columns, declared
curl -s "$B/approval-status/layout?columns=type,bankReference,status"       # 3 columns, fitted
curl -s -H 'Accept-Language: th' "$B/approval-status/layout"                # measured in Thai
```

Eight columns in English, the two modes side by side — the measurement is shared, only the answer
differs:

| column | min | max | `auto` | `fixed` |
|---|---|---|---|---|
| `type` | 57 | 116 | 76 | 62 |
| `bankReference` | 65 | 72 | 67 | 67 |
| `customerReference` | 66 | 91 | 74 | 67 |
| `account` | 55 | 139 | 104 | 103 |
| `currency` | 26 | 26 | 26 | 36 |
| `amount` | 50 | 50 | 50 | 62 |
| `valueDate` | 52 | 103 | 68 | 62 |
| `status` | 50 | 50 | 50 | 56 |

`auto` takes the slack out of the three columns that cannot use it — `CCY`, `Amount` and `Status`
have no break opportunity, so a point more is a point wasted — and gives it to the captions that
wrap.

---

## Languages

`Accept-Language` is the only header that changes the document. Six languages ship:

`en` · `zh-CN` · `zh-TW` · `th` · `ms` · `vi`

Anything else renders **English**, deliberately: an unsupported language must not fall through to
whatever locale the server's JVM happens to run in.

The language changes the footnote's wording, the export date's format and calendar (Thai prints the
Buddhist era: `11 ก.ย. 2569`), the font the document is set in, and the weight of some captions. It
does **not** translate the payload or the column captions — those are the same strings the HTML twin
prints, and localising them would mean keeping two sets of keys in step.

```bash
curl -H 'Accept-Language: th' -o listing-th.pdf "$B/approval-status/export"
```

---

## Errors

Every bad value is **400** with a plain-text body naming what was wrong. Nothing here answers 500 for
caller input.

```
$ curl -i "$B/approval-status/export?format=doc"
HTTP/1.1 400
Content-Type: text/plain;charset=UTF-8

Unknown format 'doc'
```

| request | status | body |
|---|---|---|
| `?format=doc` | 400 | `Unknown format 'doc'` |
| `?columns=type,nope` | 400 | `Unknown column 'nope'; this report has [type, bankReference, …]` |
| `?widths=elastic` | 400 | `Unknown widths 'elastic'; use auto or fixed` |

---

## Recipes

```bash
E=$B/approval-status

curl -o listing.pdf   "$E/export"                      # the document
curl -o listing.xlsx  "$E/export?format=xlsx"
curl -o listing.csv   "$E/export?format=csv"
curl -o listing.html  "$E/export?format=html"

curl -o narrow.pdf    "$E/export?columns=type,bankReference,status"
curl -o declared.pdf  "$E/export?widths=fixed"
curl -H 'Accept-Language: zh-CN' -o listing-zh.pdf "$E/export"

curl -s "$E/layout" | jq '.columns[] | {property, min, max, width, breaksTokens}'
```

Run it with:

```bash
mvn -q -pl creed-jasper-report -am -DskipTests install
mvn -pl creed-jasper-report spring-boot:run
```

---

## Where a column's left/right spacing comes from

**Three layers, in three different places, and the one people go looking for first is usually not
the one they mean.**

Start from what is *not* there: a `<jr:table>` column has **no margin**. The column widths add up to
exactly the table's width (`TableDesigner.column` → `StandardColumn.setWidth`), and the text element
inside each cell sits at `x=0` filling the whole cell (`TableDesigner.place`). There is no gutter
between columns anywhere, and no property that would add one. Everything visible between two
columns is one of these three:

| layer | what it is | who writes it | where the value lives |
|---|---|---|---|
| **1. cell padding** | text to cell edge — this is the one you usually want | `TableDesigner.apply` → `box.setLeftPadding` / `setRightPadding` | `CellStyle.padding(left, right, top, bottom)` in `ApprovalStatusRenderer.HEADER` and `.CELL`: **6 left, 3 right** |
| **2. the table's outer rules** | the first column's left line and the last column's right line | `TableDesigner.place`, the `index == 0` and `index == count - 1` branches | `CellStyle.RULE_WIDTH` (0.5pt) and `TableDesign.edgeColor` — `GRID`, `#C9CCD1` |
| **3. the page box** | how far the whole table sits from the paper's edge | the template | `jasper/approval-status.jrxml`: `leftMargin="40" rightMargin="40"`, `columnWidth="515"` |

Three things about that table are worth knowing before changing anything in it:

- **Padding is not free width.** `ColumnFit` adds `padding.left() + padding.right()` to every
  column's measured `min` and `max`, so changing the padding changes the fitted widths and what
  `/approval-status/layout` reports. The 6/3 above is where four points a column came from when the
  listing grew to eight.
- **`AccountCell` and `StatusCell` inherit the padding.** They are withers off `CELL`
  (`CELL.vAlign(TOP).lineSpacing(1.25f)` and `CELL.forecolor(ACCENT)`) — a wither narrows one
  property and carries the rest, so there is one place to change padding for all four cell styles.
- **The `width="515"` on the jrxml's `<componentElement key="listing">` is a skeleton value that
  never survives.** `TableDesigner.write` overwrites it with `design.getColumnWidth()` on every
  compile, because a chrome-stripped export has already had its margins zeroed and its column width
  widened to the whole page. Editing that number in the template changes nothing.

The outer rules are deliberately *not* part of `CellStyle`: the same style lands in every column,
and "am I the first or the last one" is not something a per-element style can know — which is why
that one colour lives on `TableDesign` instead.

---

Internals — how the table is generated, why the widths are measured the way they are, and the
landmines — are in [`HANDOFF.md`](HANDOFF.md).
