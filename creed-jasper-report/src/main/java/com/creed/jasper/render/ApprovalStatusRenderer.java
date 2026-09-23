package com.creed.jasper.render;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.dynamic.CellStyle;
import com.creed.jasper.dynamic.TableColumn;
import com.creed.jasper.dynamic.TableDesign;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ExportTimestamp;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.report.FieldDataSource;
import com.creed.jasper.service.JasperReportService;

import java.awt.Color;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The approval-status listing — the concrete {@link JasperTableRenderer}, and the only place this
 * document's own decisions live.
 *
 * <p><b>One renderer per report.</b> The payload and the export instant are constructor arguments
 * and final fields, so the hooks below simply read them and the object is immutable, thread-safe
 * by having no mutable state, and cheap: it holds two references and the shared
 * {@link JasperReportService}, which is where the compile cache lives. That is why this is
 * <i>not</i> a Spring bean — it is a value, built where the payload is, and the controller does
 * exactly that.
 *
 * <p>Everything visual is in {@code jasper/approval-status.jrxml} and its criteria subreport, with
 * one deliberate exception: the table's <b>shape</b>. That template declares no
 * {@code columnHeader}, no {@code detail} and no fields — {@link #COLUMNS} does, and the generator
 * writes the bands into the design before it is compiled. The template still owns every style
 * those generated cells name, so the look has not moved into Java; only the column list has.
 *
 * <p><b>The split is not clean when the column COUNT changes.</b> Going from four columns to eight
 * is a change to {@link #COLUMNS}, but the type size the cells print at and the depth of the
 * caption band are the template's, and both had to move with it. A column list can be reordered or
 * subset — that is what {@code ?columns=} does — without touching the jrxml; making it longer
 * cannot be.
 *
 * <p>What the base class does with that: picks the columns (honouring a custom view for PDF),
 * compiles the template with or without its chrome depending on the format, and exports. What this
 * class adds is the payload and the parameters the template's own bands print:
 *
 * <pre>
 *   COLUMNS + TABLE    which columns the table has            -&gt; the GENERATED bands
 *   data(...)          the listing's rows                     -&gt; that detail band
 *   criteriaData       the filter pairs                       -&gt; the subreport, four to a row
 *   criteriaReport     the compiled subreport                 -&gt; passed in, not resolved by path
 *   reportTitle/note   payload strings the bands print
 *   exportDate/Time    formatted here, not in the template
 *   logo/stamp         classpath names; the engine loads and caches the images
 * </pre>
 *
 * <p><b>Why the subreport is a parameter.</b> A {@code subreportExpression} naming a path would be
 * resolved by the engine's repository during the fill — i.e. a missing or unparseable criteria
 * template would surface on a user's download. Compiled up front and handed in, it fails at the
 * same moment the main template does.
 *
 * <p><b>Why the timestamps are formatted here.</b> The footer prints date and time either side of
 * a divider, so they arrive as two finished strings; a jrxml pattern would have to guess where one
 * ends, and the Thai era is not a pattern at all (see {@link ExportTimestamp}).
 *
 * <p><b>Why the rows are a data source rather than JSON.</b> The payload <i>is</i> JSON and
 * {@link TableData#json} would fill straight from it — but the account cell is four lines that
 * have to arrive joined, and a JSON field binds to a property, not to a rendering decision. A flat
 * table can skip the model; this one cannot.
 */
public final class ApprovalStatusRenderer extends JasperTableRenderer {

    /** Both templates, compiled through {@link JasperReportService} and cached the same way. */
    private static final String MAIN_TEMPLATE = "jasper/approval-status.jrxml";
    private static final String CRITERIA_TEMPLATE = "jasper/approval-status-criteria.jrxml";

    /** Shared with creed-report at build time; see this module's pom. */
    private static final String LOGO = "img/creed-logo.png";
    private static final String STAMP = "img/creed-stamp.png";

    /**
     * The one family name every element in this document asks for. It resolves to Noto Sans, Noto
     * Sans Thai, Noto Sans SC or Noto Sans TC by {@code REPORT_LOCALE} — four declarations of the
     * same name in {@code fonts/creed-fonts.xml}, of which the engine takes the first whose
     * {@code <locales>} accept the fill's locale. That indirection stays in the font extension,
     * which is where a locale-resolved family belongs; what has moved into Java is only which
     * family, size and weight each table cell asks for.
     */
    private static final String FONT = "Creed Sans";

    /** The table's palette, sampled off {@code creed-report/docs/template.jpg}. */
    private static final Color HEADER_TEXT = Color.decode("#1F3864");
    private static final Color HEADER_FILL = Color.decode("#DBE5F1");
    private static final Color TEXT = Color.decode("#212529");
    private static final Color ACCENT = Color.decode("#005CB9");
    private static final Color GRID = Color.decode("#C9CCD1");
    private static final Color ROW_RULE = Color.decode("#E3E6EA");

    /**
     * The table's four cell styles — in Java, and no longer four {@code <style>} elements in the
     * jrxml reached by name.
     *
     * <p><b>8pt, not the document's 9.</b> 515pt of content over eight columns is ~64pt each, and
     * at 9pt a bank reference needs about 71pt with its padding — so it breaks <i>inside</i> the
     * token, {@code BK260000000} then {@code 1}, which is unreadable in a way a wrapped caption is
     * not. 8pt buys about 11% and the narrower padding buys 4pt more; together they are the
     * difference between eight columns fitting and not.
     *
     * <p>The chrome's styles — the title, the count line, the footnote, the page counter, the
     * conditional per-script weights — are still declared in {@code approval-status.jrxml}: those
     * are the template's own elements, and nothing generates them.
     */
    private static final CellStyle HEADER = CellStyle.of(FONT, 8f).bold(true)
            .forecolor(HEADER_TEXT).backcolor(HEADER_FILL)
            .padding(6, 3, 3, 3).rules(GRID, GRID);

    private static final CellStyle CELL = CellStyle.of(FONT, 8f)
            .forecolor(TEXT).padding(6, 3, 4, 4).rules(null, ROW_RULE);

    /**
     * The account block: several lines that belong together, top-aligned, and the only cell whose
     * height is data. 1.25 rather than the 1.35 the four-column table used — it wraps to five lines
     * at 103pt instead of four, and proportional leading multiplies by every one of them.
     */
    private static final CellStyle ACCOUNT_CELL = CELL.vAlign(CellStyle.VAlign.TOP).lineSpacing(1.25f);

    /** The status column, in the accent colour like the sample's. */
    private static final CellStyle STATUS_CELL = CELL.forecolor(ACCENT);

    /**
     * The listing's eight columns — the <b>shape</b> of the table, supplied to the generator instead
     * of being written into the jrxml.
     *
     * <p>Weights, not widths: these are the percentages of the content area each column takes, and
     * the generator normalises them over whatever width the template's page box leaves, so this
     * list survives a change of paper — and survives the chrome being stripped for a spreadsheet,
     * which widens the content area by both margins.
     *
     * <p><b>Eight columns is what A4 portrait holds, and only just.</b> 515pt of content over eight
     * columns is ~64pt each, so the weights below are not decoration — they were measured against
     * the longest string each column actually carries, at the 8pt the table styles now set:
     *
     * <pre>
     *   bankReference     13 -> 67pt   "BK2600000001"   ~62pt with padding
     *   customerReference 13 -> 67pt   "CUST26000013"   ~66pt with padding
     *   amount            12 -> 62pt   "310,750.25"     ~53pt, right-aligned
     *   valueDate         12 -> 62pt   "02/09/2026"     ~53pt
     *   status            11 -> 57pt   "Processing"     ~53pt
     *   type              12 -> 62pt   wraps: "Telegraphic" / "Transfer"
     *   currency           7 -> 36pt   "SGD", and the caption is "CCY" for the same reason:
     *                                    "Currency" needs 47pt and would break as "Curre" / "ncy"
     *   account           20 -> 103pt  wraps to five lines, and sets the row's height
     * </pre>
     *
     * <p>Take weight off any of the first five and its cell breaks <b>inside the token</b> —
     * {@code BK260000000} / {@code 1} — which is the failure mode of a too-narrow column here: a
     * reference split across two lines is unreadable in a way a wrapped caption is not. The table
     * font size and the header band height are the other half of that bargain; both live in the
     * jrxml and both are sized for eight columns.
     *
     * <p>The captions are English literals rather than message keys, exactly as they are in
     * creed-report's HTML twin — localising them means eight keys on both sides at once, or the two
     * documents drift.
     */
    private static final List<TableColumn> COLUMNS = List.of(
            TableColumn.of("type", "Transaction / Deposit Type", 12),
            TableColumn.of("bankReference", "Bank Reference", 13),
            TableColumn.of("customerReference", "Customer Reference", 13),
            TableColumn.of("account", "Account", 20).multiline().styled(ACCOUNT_CELL),
            TableColumn.of("currency", "CCY", 7),
            TableColumn.of("amount", "Amount", 12).aligned(TableColumn.Align.RIGHT),
            TableColumn.of("valueDate", "Value / Placement Date", 12),
            TableColumn.of("status", "Status", 11).styled(STATUS_CELL));

    /**
     * The table's geometry. Both heights are sized for the <b>Thai</b> line, not the Latin one —
     * a text element shorter than its face's line height prints nothing at all.
     *
     * <p><b>48pt of header, not 22.</b> At eight columns every caption but three wraps, and a
     * caption cell too short for its last line does not clip it, it <i>drops</i> it: the 22pt band
     * that was right for four columns silently printed "Transaction /", "Bank", "Customer" and
     * "Value /" and nothing told anyone.
     *
     * <p>The number is the <b>Thai</b> arithmetic, not the Latin: "Value / Placement Date" is the
     * deepest caption these widths can produce at three lines, and three 8pt Noto Sans Thai lines
     * are 3 x 12.09 = 36.3pt against the style's 3 + 3 of padding — 42.3pt, which is how 42 came
     * to print "Value /" and "Placement" in Thai and all three lines in English, on the same page
     * of the same document. 48 is that plus headroom. {@code ApprovalStatusJasperPdfTest} asserts
     * the whole caption row in th/zh-CN/zh-TW for exactly this reason.
     *
     * <p>46pt of row is the minimum, not the row: the account cell stretches and the rest of the
     * row follows it, which at 103pt of width is five lines and about 85pt.
     *
     * <p>{@code GRID} draws the table's outer left and right rules, which belong to being the first
     * or last column rather than to any one cell — the one thing a {@link CellStyle} cannot say.
     */
    private static final TableDesign TABLE = new TableDesign(48, 46, GRID, HEADER, CELL);

    private final ApprovalStatusReport report;
    private final LocalDateTime exportedAt;

    /**
     * @param jasper     the shared engine, and the compile cache — the one thing worth reusing
     *                   between renders
     * @param report     the payload this renderer prints
     * @param exportedAt the instant the footer dates the document by
     */
    public ApprovalStatusRenderer(JasperReportService jasper, ApprovalStatusReport report,
                                  LocalDateTime exportedAt) {
        super(jasper);
        this.report = Objects.requireNonNull(report, "report");
        this.exportedAt = Objects.requireNonNull(exportedAt, "exportedAt");
    }

    @Override
    protected String templateLocation() {
        return MAIN_TEMPLATE;
    }

    @Override
    protected TableDesign tableDesign() {
        return TABLE;
    }

    @Override
    protected List<TableColumn> allColumns(ReportLanguage language) {
        return COLUMNS;
    }

    @Override
    protected TableData data(List<TableColumn> columns, ExportRequest request) {
        return TableData.source(rows(report));
    }

    /**
     * The same rows the fill gets, as text, for the content fit — read straight off the payload
     * rather than through the data source, which a fill consumes.
     *
     * <p>Column order, not field order: the caller may have subset and reordered the columns
     * ({@code ?columns=}), and the fit measures what this request will actually print.
     */
    @Override
    protected List<List<String>> rowText(List<TableColumn> columns, ExportRequest request) {
        Map<String, Function<ApprovalStatusReport.Row, Object>> fields = fields();
        List<List<String>> text = new ArrayList<>(report.rows().size());
        for (ApprovalStatusReport.Row row : report.rows()) {
            List<String> cells = new ArrayList<>(columns.size());
            for (TableColumn column : columns) {
                Function<ApprovalStatusReport.Row, Object> accessor = fields.get(column.property());
                Object value = accessor == null ? null : accessor.apply(row);
                cells.add(value == null ? "" : String.valueOf(value));
            }
            text.add(cells);
        }
        return text;
    }

    /**
     * The template's content area: A4 at 595pt wide, less the 40pt margins the page box declares.
     *
     * <p>Duplicated from the jrxml on purpose rather than read back out of the loaded design: the
     * fit happens before the template is compiled, and loading a design to ask it one number would
     * put an I/O round trip in front of every export. It only decides which branch of the fit
     * applies — the widths themselves are re-normalised over the real content width, whichever
     * format is being written.
     */
    @Override
    protected int contentWidth() {
        return 515;
    }

    @Override
    protected Map<String, Object> reportParameters(ExportRequest request) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("reportTitle", report.title());
        parameters.put("note", report.note());
        parameters.put("exportDate", ExportTimestamp.date(exportedAt, request.language()));
        parameters.put("exportTime", ExportTimestamp.time(exportedAt, request.language()));
        parameters.put("criteriaReport", jasper().compile(CRITERIA_TEMPLATE));
        parameters.put("criteriaData", criteria(report));
        parameters.put("logo", LOGO);
        parameters.put("stamp", STAMP);
        return parameters;
    }

    /**
     * The listing's rows. The account block arrives already newline-joined
     * ({@link ApprovalStatusReport.Row#accountText()}) because the cell is one stretching text
     * field; the breaks are the payload's, not the engine's guess about where they belong.
     */
    private static FieldDataSource<ApprovalStatusReport.Row> rows(ApprovalStatusReport report) {
        return FieldDataSource.of(report.rows(), fields());
    }

    /**
     * The one field-name-to-accessor map, used by both the fill's data source and the fit's
     * measurement — so a column can never be measured off one value and printed from another.
     */
    private static Map<String, Function<ApprovalStatusReport.Row, Object>> fields() {
        Map<String, Function<ApprovalStatusReport.Row, Object>> fields = new LinkedHashMap<>();
        fields.put("type", ApprovalStatusReport.Row::type);
        fields.put("bankReference", ApprovalStatusReport.Row::bankReference);
        fields.put("customerReference", ApprovalStatusReport.Row::customerReference);
        fields.put("account", ApprovalStatusReport.Row::accountText);
        fields.put("currency", ApprovalStatusReport.Row::currency);
        fields.put("amount", ApprovalStatusReport.Row::amount);
        fields.put("valueDate", ApprovalStatusReport.Row::valueDate);
        fields.put("status", ApprovalStatusReport.Row::status);
        return fields;
    }

    /**
     * The criteria, in payload order — the order <b>is</b> the layout, which is why the model keeps
     * them as a list and the subreport walks it rather than looking anything up by name.
     */
    private static FieldDataSource<ApprovalStatusReport.Criterion> criteria(ApprovalStatusReport report) {
        Map<String, Function<ApprovalStatusReport.Criterion, Object>> fields = new LinkedHashMap<>();
        fields.put("label", ApprovalStatusReport.Criterion::label);
        fields.put("value", ApprovalStatusReport.Criterion::value);
        return FieldDataSource.of(report.criteria(), fields);
    }
}
