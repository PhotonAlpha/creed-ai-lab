package com.creed.jasper.api;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.service.ApprovalStatusPdfService;
import com.creed.jasper.service.ApprovalStatusSamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The <b>approval-status listing</b>, rendered by JasperReports.
 *
 * <pre>
 * GET|POST /jasper-report/approval-status/export/pdf   the PDF
 * </pre>
 *
 * <p>Same endpoint shape, same payload and same document as {@code creed-report}'s
 * {@code /approval-status/export/pdf} — the two exist to be compared, and a difference in what
 * they are given would make the comparison meaningless.
 *
 * <p><b>No {@code /preview} twin.</b> creed-report has one because its PDF template is the one
 * thing there you cannot open in devtools: Flying Saucer takes a string of XHTML and hands back
 * bytes, so the preview serves that very string to a browser. A jrxml has no such intermediate —
 * it is not markup a browser could show — and JasperReports' design-time tooling (Jaspersoft
 * Studio, opening the file directly) is the equivalent. {@code ApprovalStatusJasperPdfTest} dumps
 * one PDF per language under {@code -Dpdf.sample.dir} for eyeballing.
 *
 * <p><b>Input-less by design</b>, like its twin: the payload is a JSON literal
 * ({@link ApprovalStatusSamples}), so two calls a week apart differ only in the export timestamp.
 * Only the {@code Accept-Language} header changes anything.
 */
@RestController
public class ApprovalStatusJasperController {

    /** ASCII and sortable in the filename, never the locale's own date format. */
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;
    private final ApprovalStatusPdfService pdfService;

    public ApprovalStatusJasperController(ObjectMapper objectMapper, ApprovalStatusPdfService pdfService) {
        this.objectMapper = objectMapper;
        this.pdfService = pdfService;
    }

    /** The document, as PDF. */
    @RequestMapping(value = "/approval-status/export/pdf",
            method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        // Normalised before it reaches the fill: an unsupported language must land on English
        // rather than on whatever the JVM's default locale happens to be. See ReportLanguage.
        ReportLanguage language = ReportLanguage.of(locale);
        ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

        byte[] body = pdfService.exportPdf(report, language, now);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                "creed-approval-status-jasper-" + now.format(FILE_TS) + ".pdf");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
