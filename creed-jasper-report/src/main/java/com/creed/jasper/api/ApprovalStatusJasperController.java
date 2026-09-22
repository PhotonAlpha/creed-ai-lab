package com.creed.jasper.api;

import com.creed.jasper.domain.ApprovalStatusReport;
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
import java.util.List;
import java.util.Locale;

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
                                         Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        ExportFormat exportFormat = ExportFormat.of(format)
                .orElseThrow(() -> new IllegalArgumentException("Unknown format '" + format + "'"));
        // Normalised before it reaches the fill: an unsupported language must land on English
        // rather than on whatever the JVM's default locale happens to be. See ReportLanguage.
        ReportLanguage language = ReportLanguage.of(locale);
        ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

        // A renderer PER REPORT, not a bean: it is an immutable value over the payload and the
        // export instant, and the only thing worth reusing between renders -- the compile cache --
        // lives in the injected JasperReportService it is handed.
        byte[] body = new ApprovalStatusRenderer(jasper, report, now)
                .render(new ExportRequest(exportFormat, language, columns));

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
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<byte[]> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
