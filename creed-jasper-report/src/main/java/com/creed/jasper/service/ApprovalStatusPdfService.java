package com.creed.jasper.service;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.i18n.ExportTimestamp;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.report.FieldDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The approval-status listing as JasperReports lays it out — the model half, opposite
 * {@link JasperReportService}'s engine half.
 *
 * <p>Everything visual is in {@code jasper/approval-status.jrxml} and its criteria subreport.
 * What this class decides is what the fill is given:
 *
 * <pre>
 *   main data source   the listing's rows                      -> the detail band
 *   criteriaData       the filter pairs                        -> the subreport, four to a row
 *   criteriaReport     the compiled subreport                  -> passed in, not resolved by path
 *   reportTitle/note   payload strings the bands print
 *   exportDate/Time    formatted here, not in the template
 *   logo/stamp         classpath names; the engine loads and caches the images
 *   REPORT_LOCALE      the language everything else follows
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
 */
@Service
public class ApprovalStatusPdfService {

    /** Both templates, compiled through {@link JasperReportService} and cached the same way. */
    private static final String MAIN_TEMPLATE = "jasper/approval-status.jrxml";
    private static final String CRITERIA_TEMPLATE = "jasper/approval-status-criteria.jrxml";

    /** Shared with creed-report at build time; see this module's pom. */
    private static final String LOGO = "img/creed-logo.png";
    private static final String STAMP = "img/creed-stamp.png";

    private final JasperReportService jasper;

    public ApprovalStatusPdfService(JasperReportService jasper) {
        this.jasper = jasper;
    }

    /** The document, as PDF bytes. */
    public byte[] exportPdf(ApprovalStatusReport report, ReportLanguage language, LocalDateTime now) {
        JasperReport main = jasper.compile(MAIN_TEMPLATE);
        return jasper.exportPdf(main, parameters(report, language, now), rows(report));
    }

    /**
     * The filled report, before export — what a test asserts pages and text on, and the input to
     * any other {@code JRExporter}. Same fill as {@link #exportPdf}, by construction.
     */
    public JasperPrint fill(ApprovalStatusReport report, ReportLanguage language, LocalDateTime now) {
        JasperReport main = jasper.compile(MAIN_TEMPLATE);
        try {
            return JasperFillManager.fillReport(main, parameters(report, language, now), rows(report));
        }
        catch (JRException ex) {
            throw new IllegalStateException("Filling the approval-status listing failed", ex);
        }
    }

    private Map<String, Object> parameters(ApprovalStatusReport report, ReportLanguage language,
                                           LocalDateTime now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JRParameter.REPORT_LOCALE, language.locale());
        parameters.put("reportTitle", report.title());
        parameters.put("note", report.note());
        parameters.put("exportDate", ExportTimestamp.date(now, language));
        parameters.put("exportTime", ExportTimestamp.time(now, language));
        parameters.put("criteriaReport", jasper.compile(CRITERIA_TEMPLATE));
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
