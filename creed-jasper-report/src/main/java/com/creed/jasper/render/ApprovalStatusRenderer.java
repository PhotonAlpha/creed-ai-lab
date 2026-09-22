package com.creed.jasper.render;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.dynamic.TableColumn;
import com.creed.jasper.dynamic.TableDesign;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ExportTimestamp;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.report.FieldDataSource;
import com.creed.jasper.service.JasperReportService;

import java.awt.Color;
import java.time.LocalDateTime;
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
     * The listing's four columns — the <b>shape</b> of the table, supplied to the generator instead
     * of being written into the jrxml.
     *
     * <p>Weights, not widths: 26/22/34/18 are the percentages the sample's columns occupy, and the
     * generator normalises them over whatever content width the template's page box leaves, so this
     * list survives a change of paper — and survives the chrome being stripped for a spreadsheet,
     * which widens the content area by both margins.
     *
     * <p>The captions are English literals rather than message keys, exactly as they are in
     * creed-report's HTML twin — localising them means four keys on both sides at once, or the two
     * documents drift.
     */
    private static final List<TableColumn> COLUMNS = List.of(
            TableColumn.of("type", "Transaction / Deposit Type", 26),
            TableColumn.of("bankReference", "Bank Reference", 22),
            TableColumn.of("account", "Account", 34).multiline().styled("AccountCell"),
            TableColumn.of("status", "Status", 18).styled("StatusCell"));

    /**
     * The table's geometry. Both heights are sized for the <b>Thai</b> line, not the Latin one —
     * a text element shorter than its face's line height prints nothing at all — and 46pt is the
     * four-line account block plus its padding.
     *
     * <p>{@code #C9CCD1} is the sample's grid, and the only colour on this side of the split: it
     * draws the table's outer left and right rules, which belong to being the first or last column
     * rather than to any one style.
     */
    private static final TableDesign TABLE = new TableDesign(22, 46, Color.decode("#C9CCD1"),
            "TableHeader", "TableCell");

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
        Map<String, Function<ApprovalStatusReport.Row, Object>> fields = new LinkedHashMap<>();
        fields.put("type", ApprovalStatusReport.Row::type);
        fields.put("bankReference", ApprovalStatusReport.Row::bankReference);
        fields.put("account", ApprovalStatusReport.Row::accountText);
        fields.put("status", ApprovalStatusReport.Row::status);
        return FieldDataSource.of(report.rows(), fields);
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
