package com.creed.jasper.render;

import com.creed.jasper.dynamic.ColumnFit;
import com.creed.jasper.dynamic.ReportShape;
import com.creed.jasper.dynamic.TableColumn;
import com.creed.jasper.dynamic.TableDesign;
import com.creed.jasper.export.ColumnWidths;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.service.JasperReportService;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A report whose table is generated: one template, a column list, and an export in any
 * {@link ExportFormat}.
 *
 * <p>This is the <b>migration of the DynamicJasper renderer</b> in {@code docs/1.jpeg}–{@code 4.jpeg}
 * — same responsibilities, same template-method shape, none of the library. The mapping, method
 * for method:
 *
 * <pre>
 *   DynamicJasperRendererAbstract          here
 *   ────────────────────────────────────── ──────────────────────────────────────────────────
 *   render(ExportRequest, json, params)    render(ExportRequest)
 *   generateMainReport(...)                shape(...) + JasperReportService.compile(ReportShape)
 *     setTemplateFile(...) only for PDF      ReportShape.document vs .tableOnly, off
 *                                            ExportFormat.carriesChrome()
 *     setResourceBundle / setDefaultEncoding  declared once in the jrxml
 *     setReportLocale(locale)                 REPORT_LOCALE parameter
 *     setLeft/RightMargin(...)                the template's page box
 *   generateBaseTable()                    TableDesign
 *     setAllowDetailSplit(false)             splitType="Prevent" on the generated band
 *     setMargins(0,0,0,0)                    TableDesigner.stripChrome
 *     setUseFullPageWidth(true)              weights normalised over columnWidth
 *   generateTable(locale, request)         columnsFor(request)
 *     getCustomViewColumns(...) for PDF      the same rule, in customViewColumns(...)
 *   generateBaseColumn(.., first, last)    TableDesigner's first/last edge pens
 *     four Style variants per position       one style per column + two pens
 *   the table, concatenated as a subreport a &lt;jr:table&gt; in the template's own detail band,
 *                                            given its columns at compile time
 *   IS_IGNORE_PAGINATION for non-PDF       ExportFormat.paginated()
 *   exportReport(builder, params, type)    JasperReportService.export(..., format)
 *     CSV BOM property                       SimpleCsvExporterConfiguration.setWriteBOM
 *   JsonQueryExecuterFactory.JSON_INPUT_STREAM  TableData.json(...)
 * </pre>
 *
 * <p><b>What the migration changes on purpose.</b> The reference builds a table's look out of four
 * {@code Style} objects <i>per column position</i>, because DynamicJasper has no other way to draw
 * a table's outer border; here a column carries one {@link com.creed.jasper.dynamic.CellStyle}
 * whatever position it lands in and the generator adds the two edge pens. The non-PDF path starts
 * from the <b>same</b> template with its chrome stripped rather than from a second report built
 * from nothing, so there is one file to keep in step instead of two. And the column widths can be
 * <b>measured from the content</b> ({@link com.creed.jasper.dynamic.ColumnFit}) rather than taken
 * from hand-tuned weights — the reference has no equivalent, and neither does JasperReports.
 *
 * <p>Subclasses supply the document; this class supplies the pipeline. {@link #render} is final.
 *
 * <p><b>A renderer is a value over one report, not a service.</b> The hooks take only the
 * {@link ExportRequest} and read the payload from the subclass's own final fields, so a renderer
 * is built where the payload is, used once and dropped. It holds no mutable state and needs no
 * synchronisation, and nothing is lost by building one per request: the only expensive thing in
 * the pipeline is the compiled report, and that is cached in the shared
 * {@link JasperReportService} this class is handed. The reference implementation carries its
 * payload on the parameter map instead — the same information, one indirection further from the
 * code that reads it.
 */
public abstract class JasperTableRenderer {

    /** The template parameter the table's {@code datasetRun} reads its rows from. */
    public static final String ROWS_PARAMETER = "rows";

    private final JasperReportService jasper;

    protected JasperTableRenderer(JasperReportService jasper) {
        this.jasper = jasper;
    }

    // ---- what a subclass supplies ---------------------------------------------------------

    /** The classpath {@code .jrxml} this report's chrome and styles come from. */
    protected abstract String templateLocation();

    /** The table's geometry — heights and the outer rule. */
    protected abstract TableDesign tableDesign();

    /**
     * Every column this report can print, in its natural order — the reference implementation's
     * {@code generateAllColumns(locale)}. The locale is passed because a caption may be localized;
     * a report whose captions are literals simply ignores it.
     */
    protected abstract List<TableColumn> allColumns(ReportLanguage language);

    /** Where the rows come from, for the columns that survived the request. */
    protected abstract TableData data(List<TableColumn> columns, ExportRequest request);

    /**
     * The rows as <b>text</b>, row-major in the column list's order — what a content fit measures.
     *
     * <p>Separate from {@link #data} on purpose: a {@link net.sf.jasperreports.engine.JRDataSource}
     * is single-pass and a fill consumes it, so measuring through it would leave the fill with an
     * exhausted source. A subclass that has its payload in hand (all of them do — the payload is
     * why a renderer is a value) answers this from the payload directly.
     *
     * <p>The default is empty, which is not a failure: a fit with no rows measures the captions
     * alone, so a report that does not implement this still lays its headings out sensibly.
     */
    protected List<List<String>> rowText(List<TableColumn> columns, ExportRequest request) {
        return List.of();
    }

    /**
     * The content width the fit is made against, in points — the template's page box minus its
     * margins.
     *
     * <p>Only the <i>relative</i> result survives: {@link ColumnFit} hands back the fitted widths
     * as weights and {@link com.creed.jasper.dynamic.TableDesigner} re-normalises them over the
     * real content area, which differs between a PDF (515pt here) and a chrome-stripped extract
     * (the full page). What this number decides is which branch of the fit applies — whether the
     * content fits, has to be squeezed, or overflows — so it has to be the width of the format
     * people actually read, i.e. the PDF's.
     */
    protected abstract int contentWidth();

    /**
     * Anything else the template's own bands need — the title it prints, the export stamp, the
     * logo. Called for every format; a stripped design simply ignores the ones its bands no longer
     * reference.
     */
    protected Map<String, Object> reportParameters(ExportRequest request) {
        return Map.of();
    }

    // ---- the pipeline ---------------------------------------------------------------------

    /** The report, in the requested format. */
    public final byte[] render(ExportRequest request) {
        List<TableColumn> columns = layoutFor(request).columns();
        JasperReport report = jasper.compile(shape(request, columns));

        // ONE empty record for the MAIN dataset: the detail band holds the table and has to run
        // exactly once. The rows belong to the table's own datasetRun, which reads them from the
        // `rows` parameter -- the report around the table has no rows of its own.
        return jasper.export(report, parameters(request, data(columns, request)),
                new JREmptyDataSource(1), request.format());
    }

    /**
     * The columns this request prints, measured — the fit, not just the widths, so a caller can be
     * shown <i>why</i> a column came out the width it did.
     *
     * <p>{@link ColumnWidths#FIXED} does not skip the measurement, it skips the <b>result</b>: the
     * declared weights are kept, and the mins and maxes are still measured so the same report can
     * be inspected either way. Measuring is cheap next to a fill; being unable to compare the two
     * modes is not.
     */
    public final ColumnFit layoutFor(ExportRequest request) {
        List<TableColumn> columns = columnsFor(request);
        ColumnFit fit = ColumnFit.measure(columns, rowText(columns, request), tableDesign(),
                contentWidth(), request.language().locale());
        return request.fitsColumns() ? fit : ColumnFit.declared(fit, columns, contentWidth());
    }

    /**
     * Which columns this request prints.
     *
     * <p>The reference implementation's rule, kept: a <b>custom view</b> — the caller's own subset,
     * in the caller's order — applies only to the format that carries chrome. A spreadsheet or a
     * CSV is a data extract and is expected to carry every column whatever a screen is showing,
     * which is a decision worth keeping rather than rediscovering.
     */
    protected final List<TableColumn> columnsFor(ExportRequest request) {
        List<TableColumn> all = allColumns(request.language());
        if (!request.hasCustomView()) {
            return all;
        }
        return customViewColumns(all, request.customViewColumns());
    }

    /**
     * Picks and reorders {@code all} by field name.
     *
     * @throws IllegalArgumentException on a name no column has — a silently dropped column is a
     *                                  report that is wrong in a way nobody notices, so this is
     *                                  the one place the request is refused rather than trimmed
     */
    protected List<TableColumn> customViewColumns(List<TableColumn> all, List<String> requested) {
        Map<String, TableColumn> byProperty = new LinkedHashMap<>();
        all.forEach(column -> byProperty.put(column.property(), column));

        List<TableColumn> chosen = new ArrayList<>(requested.size());
        for (String property : requested) {
            TableColumn column = byProperty.get(property);
            if (column == null) {
                throw new IllegalArgumentException("Unknown column '" + property + "'; this report has "
                        + byProperty.keySet());
            }
            chosen.add(column);
        }
        return List.copyOf(chosen);
    }

    /** The template plus the table, with or without the document around it. */
    protected ReportShape shape(ExportRequest request, List<TableColumn> columns) {
        return request.format().carriesChrome()
                ? ReportShape.document(templateLocation(), tableDesign(), columns)
                : ReportShape.tableOnly(templateLocation(), tableDesign(), columns);
    }

    private Map<String, Object> parameters(ExportRequest request, TableData data) {
        Map<String, Object> parameters = new HashMap<>(reportParameters(request));
        parameters.put(JRParameter.REPORT_LOCALE, request.language().locale());
        parameters.put(ROWS_PARAMETER, data.rows());
        if (!request.format().paginated()) {
            // Without this a spreadsheet carries a page break, a repeated caption row and a page
            // footer into the middle of the data -- the paper's furniture, in a file that has no
            // pages.
            parameters.put(JRParameter.IS_IGNORE_PAGINATION, Boolean.TRUE);
        }
        return parameters;
    }

    /** For a subclass that needs the engine directly — compiling a subreport, say. */
    protected final JasperReportService jasper() {
        return jasper;
    }
}
