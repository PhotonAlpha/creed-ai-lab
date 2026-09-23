package com.creed.jasper.api;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.dynamic.ColumnFit;
import com.creed.jasper.export.ColumnWidths;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.render.ApprovalStatusRenderer;
import com.creed.jasper.service.ApprovalStatusSamples;
import com.creed.jasper.service.JasperReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>approval-status listing</b>, rendered by JasperReports.
 *
 * <pre>
 * GET|POST /jasper-report/approval-status/export            the report
 *          ?format=pdf|xlsx|csv|html   default pdf
 *          &amp;columns=type,status        PDF only: a custom view, in this order
 * </pre>
 *
 * <p>Same document and same payload as {@code creed-report}'s
 * {@code /approval-status/export/pdf} — the two exist to be compared, and a difference in what
 * they are given would make the comparison meaningless. {@code /approval-status/export/pdf} is
 * kept as an alias so that comparison keeps working unchanged.
 *
 * <p><b>Input-less by design</b>, still: the payload is a JSON literal
 * ({@link ApprovalStatusSamples}), so two calls a week apart differ only in the export timestamp.
 * What the query string chooses is the <i>rendering</i> — which format, which columns — never the
 * data.
 *
 * <p><b>Why {@code columns} is PDF-only.</b> It is the rule the reference implementation this
 * renderer was migrated from applies, and it is the right one: a spreadsheet or a CSV is a data
 * extract and is expected to carry every column whatever a screen happens to be showing. The
 * parameter is accepted and ignored for those formats rather than refused, so a caller can flip
 * {@code format} without rewriting the rest of the URL.
 *
 * <p><b>No {@code /preview} twin.</b> creed-report has one because its PDF template is the one
 * thing there you cannot open in devtools: Flying Saucer takes a string of XHTML and hands back
 * bytes. A jrxml has no such intermediate — and {@code ?format=html} is now the cheap look.
 */
@RestController
public class ApprovalStatusJasperController {

    /** ASCII and sortable in the filename, never the locale's own date format. */
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;
    private final JasperReportService jasper;

    public ApprovalStatusJasperController(ObjectMapper objectMapper, JasperReportService jasper) {
        this.objectMapper = objectMapper;
        this.jasper = jasper;
    }

    /** The document, in the requested format. */
    @RequestMapping(value = { "/approval-status/export", "/approval-status/export/pdf" },
            method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "pdf") String format,
                                         @RequestParam(required = false) List<String> columns,
                                         @RequestParam(required = false) String widths,
                                         Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        ExportFormat exportFormat = ExportFormat.of(format)
                .orElseThrow(() -> new IllegalArgumentException("Unknown format '" + format + "'"));
        ColumnWidths widthMode = widthMode(widths);
        // Normalised before it reaches the fill: an unsupported language must land on English
        // rather than on whatever the JVM's default locale happens to be. See ReportLanguage.
        ReportLanguage language = ReportLanguage.of(locale);
        ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

        // A renderer PER REPORT, not a bean: it is an immutable value over the payload and the
        // export instant, and the only thing worth reusing between renders -- the compile cache --
        // lives in the injected JasperReportService it is handed.
        byte[] body = new ApprovalStatusRenderer(jasper, report, now)
                .render(new ExportRequest(exportFormat, language, widthMode, columns));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(exportFormat.mediaType()));
        headers.setContentDispositionFormData("attachment", "creed-approval-status-jasper-"
                + now.format(FILE_TS) + "." + exportFormat.extension());
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Bad input is a <b>400</b>, never a 500 — an unknown {@code format} or a {@code columns} name
     * this report does not have. The repo's rule next door
     * ({@code InvalidTableDefinitionException}, {@code UnknownReportTypeException}), applied to the
     * two things a caller can get wrong here.
     */
    /**
     * The column layout this request would print, as JSON — the same measurement the export makes,
     * without the PDF.
     *
     * <p>Two renders of the same document differ in a hundred points of leading and there is no way
     * to read a column's width off a page; this answers the question the fit exists to answer. Call
     * it twice, once per {@code widths} mode, or with different {@code columns} lists, and the
     * difference between three, four and eight columns is arithmetic rather than eyesight.
     *
     * <pre>
     * GET /approval-status/layout                                      all 8, fitted
     * GET /approval-status/layout?widths=fixed                         all 8, declared weights
     * GET /approval-status/layout?columns=type,bankReference,status    3 columns, fitted
     * GET /approval-status/layout?columns=...&amp;widths=fixed&amp;...  the same 3, declared
     * </pre>
     *
     * <p>Every number is a point at the PDF's 515pt content width. {@code min} is the width below
     * which that column breaks inside a token, {@code max} the width at which nothing in it wraps,
     * {@code width} what the mode gave it — so {@code width < min} is a column that will break a
     * reference across two lines, and it is visible here before anyone opens a document.
     */
    // produces=JSON explicitly: with jackson-dataformat-xml anywhere on the classpath, content
    // negotiation happily answers this as XML to a caller that sent no Accept header, and an API
    // whose media type depends on what else is in the build is not one anybody can script against.
    @RequestMapping(value = "/approval-status/layout", method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> layout(@RequestParam(required = false) List<String> columns,
                                                      @RequestParam(required = false) String widths,
                                                      Locale locale) {
        ColumnWidths widthMode = widthMode(widths);
        ReportLanguage language = ReportLanguage.of(locale);
        ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

        // PDF: the fit is measured against the format that carries chrome, and a custom view is
        // only honoured for that format anyway -- asking for the layout of a CSV would silently
        // answer for all eight columns whatever was asked for.
        ExportRequest request = new ExportRequest(ExportFormat.PDF, language, widthMode, columns);
        ColumnFit fit = new ApprovalStatusRenderer(jasper, report, LocalDateTime.now()).layoutFor(request);

        List<Map<String, Object>> laidOut = new ArrayList<>(fit.measurements().size());
        for (ColumnFit.Measured measured : fit.measurements()) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("property", measured.column().property());
            column.put("header", measured.column().header());
            column.put("weight", measured.column().weight());
            column.put("min", measured.min());
            column.put("max", measured.max());
            column.put("width", measured.width());
            column.put("percent", Math.round(1000.0 * measured.width() / fit.available()) / 10.0);
            column.put("wraps", measured.width() < measured.max());
            // The one that matters: a column below its own minimum does not wrap, it breaks a
            // token in half.
            column.put("breaksTokens", measured.width() < measured.min());
            laidOut.add(column);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("widths", widthMode.code());
        body.put("language", language.locale().toLanguageTag());
        body.put("available", fit.available());
        body.put("columnCount", laidOut.size());
        body.put("total", fit.measurements().stream().mapToInt(ColumnFit.Measured::width).sum());
        body.put("everythingFits", fit.everythingFits());
        body.put("columns", laidOut);
        return ResponseEntity.ok(body);
    }

    /** A width mode, or a 400 — never a 500, and never a silent fallback to the default. */
    private static ColumnWidths widthMode(String widths) {
        return ColumnWidths.of(widths).orElseThrow(() -> new IllegalArgumentException(
                "Unknown widths '" + widths + "'; use auto or fixed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<byte[]> badRequest(IllegalArgumentException ex) {
        // WITH the charset, not a bare text/plain: the body is written as UTF-8 and the message
        // quotes back whatever the caller sent -- which can be a column name in any script. A
        // Content-Type that does not say so leaves the client decoding it as its platform default.
        return ResponseEntity.badRequest()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
