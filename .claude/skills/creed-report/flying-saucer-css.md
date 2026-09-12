# CSS support in openpdf-html (Flying Saucer) — what the PDF renderer actually does

Reference for anyone writing a `*-pdf.html` template or a stylesheet that `CountryStyles` inlines
into one. The renderer is **openpdf-html 2.2.2** (LibrePDF's Flying Saucer fork, `org.openpdf.*`),
driven by `PdfExportService` through `ITextRenderer.setDocumentFromString`.

**It is not a browser.** It implements roughly **CSS 2.1 plus the CSS 3 Paged Media module**, plus a
handful of proprietary `-fs-*` properties. Everything after CSS 2.1 — flexbox, grid, custom
properties, `calc()`, `rem`, transforms, shadows, media queries — is either ignored or, in one case
below, actively destructive.

**How this file was produced** (2026-09-11, so it can be redone when the version changes): the
property/selector/at-rule lists are read out of the library's own sources
(`org/openpdf/css/constants/CSSName.java`, `IdentValue.java`, `MarginBoxName.java`,
`css/parser/CSSParser.java`), and every behavioural claim marked **✅** was rendered through the real
pipeline and eyeballed as a PDF. Claims marked **◦** are declared in the source but were not probed.
Recipe for re-probing at the bottom.

---

## 1. Verdict table — the things people reach for

| You want | Works? | Use instead |
|---|---|---|
| `display: flex` / `grid` | ❌ not a value the engine knows | floats + tables |
| `calc()` | ❌ **✅ probed: declaration dropped** | fixed `mm`/`pt`/`%` |
| CSS custom properties (`--x`, `var()`) | ❌ dropped | Thymeleaf `th:inline="css"` if the value must vary |
| `rem` (also `vw`, `vh`, `ch`) | ❌ **✅ probed: dropped** | `pt`, `mm`, `em`, `%` |
| `box-shadow`, `text-shadow`, `transform`, `transition` | ❌ **✅ probed: no-op** | borders / background colours |
| `linear-gradient()` | ⚠️ **parses, draws nothing** — see §7 | a solid `background-color` |
| `@media` (any type) | 💀 **breaks the stylesheet** — see §7 | never use it in PDF CSS |
| `border-radius` | ✅ probed | — |
| `opacity` | ✅ probed | — |
| `box-sizing: border-box` | ✅ probed | — |
| `background-image: url(data:…)` | ✅ probed | (only `data:` — no base URL, see §7) |
| `float`, `position: relative/absolute/fixed` | ✅ probed | — |
| `:nth-child()`, `[attr^="x"]`, `:first-child` | ✅ probed | — |
| repeated header/footer with an image | ✅ two ways | `.page-frame` + `-fs-table-paginate`, or `position: running()` (§6) |
| footer pinned to the page bottom on a short page | ✅ only via `position: running()` (§6) | a frame `<tfoot>` reaches the content's foot, not the page's |
| page counter | ✅ `counter(page)` in an `@page` margin box — **and inside a running element drawn in one** (§6) | — |
| mixed page orientation in one PDF | ✅ probed | named `@page` + `page:` (§5) |

---

## 2. Supported properties

The complete set the engine has a builder for — anything **not** in this list is logged and the
declaration is dropped (the rest of the rule survives, §7).

**Box** `display` · `position` · `top` `right` `bottom` `left` · `float` · `clear` · `width`
`height` · `min-width` `max-width` `min-height` `max-height` · `margin` (+ `-top/-right/-bottom/-left`)
· `padding` (+ 4) · `overflow` · `clip` · `visibility` · `z-index` · `box-sizing`

**Border** `border` · `border-width/-style/-color` · `border-top/-right/-bottom/-left` (+ their
`-width/-style/-color`) · `border-radius` + the four corners (`border-top-left-radius`, …)

**Background** `background` · `background-color` · `background-image` · `background-position` ·
`background-repeat` · `background-attachment` · `background-size`

**Text & font** `color` · `font` · `font-family` · `font-size` · `font-style` · `font-variant` ·
`font-weight` · `line-height` · `letter-spacing` · `word-spacing` · `text-align` · `text-decoration`
· `text-indent` · `text-transform` · `vertical-align` · `white-space` · `word-wrap` · `word-break` ·
`hyphens` · `tab-size` · `direction` · `unicode-bidi` · `opacity`

**Lists & generated content** `list-style` · `list-style-type` · `list-style-position` ·
`list-style-image` · `content` · `counter-reset` · `counter-increment` · `quotes`

**Tables** `border-collapse` · `border-spacing` · `caption-side` · `empty-cells` · `table-layout`

**Paged media** `page` · `page-break-before` · `page-break-after` · `page-break-inside` · `orphans` ◦
· `widows` ◦ · `size` (inside `@page`)

**Other** `outline` (+ `-width/-style/-color`) · `cursor` (meaningless in PDF) · `src` (inside
`@font-face`)

### Proprietary `-fs-*`

`-fs-table-paginate` (repeat a table's `thead`/`tfoot` per page — **the mechanism `.page-frame`
uses**) · `-fs-page-sequence` ◦ · `-fs-page-width` `-fs-page-height` `-fs-page-orientation` ◦ ·
`-fs-pdf-font-embed` `-fs-pdf-font-encoding` ◦ · `-fs-font-metric-src` ◦ · `-fs-named-destination` ◦
· `-fs-fit-images-to-width` ◦ · `-fs-dynamic-auto-width` ◦ · `-fs-keep-with-inline` ◦ ·
`-fs-border-spacing-horizontal` / `-vertical` ◦ · `-fs-table-cell-colspan` / `-rowspan` ◦ ·
`-fs-text-decoration-extent` ◦ · plus the `@-fs-pdf-xmp-metadata` margin box ◦

### Notable `display` values

`block` · `inline` · `inline-block` · `list-item` · `run-in` · `compact` · `none` · `table` ·
`inline-table` · `table-row` · `table-cell` · `table-row-group` · `table-header-group` ·
`table-footer-group` · `table-column` · `table-column-group` · `table-caption`.
**No `flex`, no `grid`, no `inline-flex`.**

`position` takes `static` · `relative` · `absolute` · `fixed` · `running(name)` — **no `sticky`**.

---

## 3. Selectors

**Supported** ✅ — type, `*`, `.class`, `#id`, descendant, `>` (child), `+` (adjacent sibling),
grouping with `,`.

**Attribute selectors** — the full CSS 3 set: `[attr]`, `[attr=v]`, `[attr~=v]`, `[attr|=v]`,
`[attr^=v]`, `[attr$=v]`, `[attr*=v]`. ✅ probed (`td[data-k^="a"]`).

**Pseudo-classes** — `:link` · `:visited` · `:hover` · `:focus` · `:active` (the last four are inert
in a PDF) · `:first-child` ✅ · `:last-child` · `:nth-child(n)` ✅ · `:even` · `:odd` · `:lang(x)`.

**Pseudo-elements** — `::before` · `::after` · `::first-line` · `::first-letter` (one- or two-colon
syntax both parse).

**NOT supported, and they are a parse *error*, not a silent skip** — `:not()`, `:is()`, `:where()`,
`:has()`, `:root`, `:nth-of-type()`, `:first-of-type`, `:empty`, `:checked`, `~` (general sibling).
An unknown pseudo-class throws `CSSParseException`, which **drops the whole ruleset** (§7). `:even`
and `:odd` are Flying Saucer inventions — the repo's PDF templates use a `.odd` class on the `<tr>`
instead, which is why zebra striping survives.

---

## 4. Units, colours, functions

**Length units** `px` · `pt` · `pc` · `mm` · `cm` · `in` · `em` · `ex` · `%` · `deg` (angles).
Nothing else — `rem`, `vw`, `vh`, `vmin`, `ch` are dropped ✅. Prefer `pt`/`mm`: the PDF has a real
physical page, and `px` is resolution-dependent (`dotsPerPixel`).

**Colours** `#rgb` · `#rrggbb` · named CSS colours · `rgb(r,g,b)` · `rgba(r,g,b,a)`.
**No** `hsl()`, `hsla()`, `#rrggbbaa`, `oklch()`, `color-mix()`.

**Functions in `content:`** `attr()` · `counter()` · `counters()` · `target-counter()` ✅ ·
`leader()` ✅ · `element()` ✅ · any `-fs-*`. Anything else is a parse error on that declaration.

`target-counter(attr(href), page)` + `leader(dotted)` is a working table-of-contents recipe — probed:
it rendered a dotted leader and the resolved page number of the `href` target.

---

## 5. At-rules

| At-rule | Status |
|---|---|
| `@page` | ✅ full — `size`, margins, and margin boxes |
| `@page :first` / `:left` / `:right` | ✅ probed (`:first` printed only on page 1) |
| `@page <name>` + `page: <name>` | ✅ probed — **mixed orientation works**: `@page land { size: A4 landscape }` on a `page-break-before: always` block produced a real 297×210mm page 2 in a 210×297mm document |
| `@font-face` | ✅ used by `-fs-font-metric-src`; note this project registers fonts through `ITextFontResolver.addFont` instead, which is what `creed.report.pdf.font-paths` does |
| `@import` | ◦ parsed, top of sheet only — irrelevant here (everything is inlined) |
| `@charset`, `@namespace` | ◦ parsed, top of sheet only |
| `@media` | 💀 **never works and destroys the rest of the stylesheet — see §7** |
| `@supports`, `@keyframes`, `@layer`, `@container` | ❌ "Invalid at-rule", skipped |

**`@page` margin boxes** — all 16 CSS 3 boxes exist:
`@top-left-corner` `@top-left` `@top-center` `@top-right` `@top-right-corner` ·
`@bottom-left-corner` `@bottom-left` `@bottom-center` `@bottom-right` `@bottom-right-corner` ·
`@left-top` `@left-middle` `@left-bottom` · `@right-top` `@right-middle` `@right-bottom`.
They are the **only** place `counter(page)` / `counter(pages)` resolve — which is why the page
counter in `report-chrome-pdf :: formStyles` has to live in one.

---

## 6. Repeating something on every page

Four mechanisms, ranked by what this project learned:

1. **`.page-frame` table + `-fs-table-paginate: paginate`** — the `<thead>`/`<tfoot>` repeat on every
   page and may contain arbitrary markup, **images included**. This is what both PDF templates use.
   Trap: putting a `<tfoot>` on the *data* table instead once turned a 60-row table into 122 pages.
2. **`position: running(name)` + `content: element(name)` in a margin box** — ✅ works, image and
   all, and is now **in production**: the statement chrome's footnote
   (`report-chrome-pdf :: statementFooter`, `.statement-footer`) is a running element drawn by
   `@page { @bottom-center { content: element(statementfoot) } }`, seal included.

   It is the only mechanism that reaches the page *margin* rather than the content box, which makes
   it the only way to **pin a footer to the bottom of a page the content does not fill** — a
   `.page-frame` `<tfoot>` only ever reaches the foot of the *content*, and both ways of stretching
   that frame fail: `height: 100%` is ignored (no resolvable containing block in paged media) and an
   explicit `height: 247mm` turned a three-row report into three pages with the first one blank.

   Three things it needs, all found by rendering:
   - **`counter(page)` resolves INSIDE the running element.** The element is laid out in a margin
     box, and that is where those counters live — so a `:after { content: counter(page) … }` on a
     child works, which is how that one footnote carries both its text and its own centred page
     counter. Two margin boxes cannot stack vertically, so without this the "footnote above the page
     number" layout would not be expressible at all.
   - **A bottom `@page` margin deep enough for the whole block** (34mm there: two text lines beside
     a 12mm seal, then the counter). The margin box does not grow the margin — it clips.
   - **`vertical-align: top` on the margin box.** Bottom-aligned, a float taller than the text
     beside it (the seal) hung below the block's last line and was cut off by the page edge.

   A browser understands none of this: `position: running()` is an invalid value, so the block
   renders wherever it sits in the markup. `report-pdf-preview.css` parks `.statement-footer` in the
   sheet's bottom margin band to compensate.
3. **`position: fixed`** — ✅ repeats on every page, but it is positioned against the page's
   **content** box and clipped to it, so a negative offset meant to reach the margin renders nothing.
4. **`@page { @top-x { content: url(…) } }`** — ❌ silently draws nothing. Margin boxes are text-only.

---

## 7. Landmines (all verified)

**💀 `@media` discards its own block *and* everything after it in that stylesheet.**
Probed: with `@media print { .a { color: green } } .b { color: green }`, neither `.a` nor `.b` is
green, while an identical `.b` placed *before* the `@media` is. Cause is a parser bug —
`CSSParser.media()`'s inner loop breaks the `switch`, not the `while`, so after the block's `}` it
keeps trying to parse rulesets, throws at EOF, and the `catch` discards the whole media rule; the
statement that would have registered it never runs. It is not media-type-dependent: `print`,
`screen` and `all` all failed. **Rule: no `@media` in any CSS that reaches the renderer.** In this
repo that means `report-pdf.css`, `country/<code>/style-pdf.css`, and the `<style>` blocks in
`*-pdf.html`. `static/css/report-pdf-preview.css` *does* use `@media print`, legitimately — it is
linked by the browser preview and never inlined into a PDF. Keep it that way.

**⚠️ `linear-gradient()` parses and draws nothing.** The engine has a whole `FSLinearGradient` value
class, so the declaration is accepted — but `ITextOutputDevice.drawLinearGradient(...)` is an empty
method body. Probed: no paint. (This is why `report-pdf.css` documents the header bar as
"`linear-gradient(...)` → solid + accent border".)

**Error handling is asymmetric — know which mistake costs you what:**
- unknown **property** → logged, that declaration dropped, rest of the rule kept;
- malformed **value** → same, declaration dropped;
- unknown **pseudo-class** or bad **selector** → exception → **the whole ruleset is dropped**;
- bad **at-rule** → the whole at-rule is dropped (and for `@media`, the rest of the sheet with it).

None of this reaches your logs by default in this app, so a rule that "does nothing" is usually a
rule the parser threw away.

**Images must be `data:` URIs.** `setDocumentFromString` is called with no base URL, so relative and
absolute-path `src`/`url()` resolve to nothing. `PdfExportService` injects `${logo}` /
`${logoInverse}` as base64 `data:` URIs for exactly this reason. PNG/JPEG/GIF only — **no SVG**.

**Fonts have no per-glyph fallback across families.** Each locale's `pdf.font.family` stack must
*lead* with a face that covers its script and also carries the Latin the tables are full of; and
because the engine does not synthesize bold, a family without a Bold face silently loses every bold
CJK/Thai glyph. See `SKILL.md` for the bundled-Noto details.

**No JavaScript, ever.** `<script>` is inert; anything computed must be computed in Java or
Thymeleaf.

---

## 8. Re-probing when the version changes

Property/selector lists come out of the sources:

```bash
cd /tmp && rm -rf fs-src && mkdir fs-src && cd fs-src
unzip -q ~/Desktop/workspace/repos/com/github/librepdf/openpdf-html/<ver>/openpdf-html-<ver>-sources.jar
grep -oE '"[-a-zA-Z]+"' org/openpdf/css/constants/CSSName.java | sort -u   # every known property
grep -n "addPseudoClassOrElement" -A 30 org/openpdf/css/parser/CSSParser.java  # every known selector
sed -n '/MarginBoxName TOP_LEFT_CORNER/,/FS_PDF_XMP/p' org/openpdf/css/constants/MarginBoxName.java
```

Behaviour needs a render. Drop a throwaway JUnit test in `creed-report/src/test/.../service/`, feed
the probe markup to the real service, and look at the PDF — this is how every ✅ above was settled:

```java
PdfExportService service = new PdfExportService(new SpringTemplateEngine(),
        new PathMatchingResourcePatternResolver(), "classpath:/fonts/*.ttf",
        "classpath:/static/img/creed-logo.png", "classpath:/static/img/creed-logo-inverse.png");
Files.write(Path.of("/tmp/probe.pdf"), service.renderHtml("<html>…</html>"));
```

```bash
mvn -q -pl creed-report test -Dtest=YourProbeTest
sips -s format png -Z 1400 /tmp/probe.pdf --out /tmp/probe.png   # page 1, no viewer needed
```

Colour-code the probe (green = "this rule applied") and always include a **control** rule outside
whatever you are testing — that is what turned "`@media print` doesn't work" into "`@media` eats the
rest of the sheet".
