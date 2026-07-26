package com.creed.report.controller;

import com.creed.report.export.ExcelExportRequest;
import com.creed.report.export.ExcelExportService;
import com.creed.report.export.ReportType;
import com.creed.report.model.ServerInfo;
import com.creed.report.service.AssetService;
import com.creed.report.service.PdfExportService;
import com.creed.report.service.ServerInfoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class ReportController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInfoService serverInfoService;
    private final AssetService assetService;
    private final TemplateEngine templateEngine;
    private final PdfExportService pdfExportService;
    private final ExcelExportService excelExportService;

    public ReportController(ServerInfoService serverInfoService,
                            AssetService assetService,
                            TemplateEngine templateEngine,
                            PdfExportService pdfExportService,
                            ExcelExportService excelExportService) {
        this.serverInfoService = serverInfoService;
        this.assetService = assetService;
        this.templateEngine = templateEngine;
        this.pdfExportService = pdfExportService;
        this.excelExportService = excelExportService;
    }

    @GetMapping({ "/report"})
    public String report(Model model) {
        List<ServerInfo> servers = serverInfoService.listServers();
        model.addAttribute("servers", servers);
        model.addAttribute("total", servers.size());
        model.addAttribute("generatedAt", LocalDateTime.now().format(TS));
        // Drives the Excel dropdown: whatever export strategies are registered, nothing hard-coded
        // in the template.
        model.addAttribute("reportTypes", excelExportService.supportedTypes());
        return "report";
    }

    @GetMapping(value = "/export", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> export(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        List<ServerInfo> servers = serverInfoService.listServers();

        Context ctx = new Context(locale);
        ctx.setVariable("servers", servers);
        ctx.setVariable("total", servers.size());
        ctx.setVariable("generatedAt", now.format(TS));
        ctx.setVariable("bootstrapCss", assetService.bootstrapCss());
        ctx.setVariable("bootstrapJs", assetService.bootstrapJs());

        String html = templateEngine.process("report-export", ctx);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String filename = "creed-server-report-" + now.format(FILE_TS) + ".html";
//        byte[] body = pdfExportService.renderHtml(html);
//        String filename = "creed-server-report-" + now.format(FILE_TS) + ".pdf";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
//        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * PDF twin of {@link #export(Locale)}: renders {@code report-export-pdf.html} — the PDF adaptation of
     * {@code report-export.html}, same visual language rebuilt in paged-media CSS 2.1 (openpdf-html
     * cannot render the Bootstrap template). See {@link PdfExportService} for the template
     * constraints and CJK font configuration.
     *
     * <p>Localized: the locale comes from {@link com.creed.report.config.LocaleConfig} —
     * {@code ?lang=} (kept in a cookie) or {@code Accept-Language}; strings and the Noto font
     * stack follow the locale via the {@code messages*.properties} bundles.
     */
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        List<ServerInfo> servers = serverInfoService.listServers();

        byte[] body = pdfExportService.renderTemplate("report-export-pdf", Map.of(
                "servers", servers,
                "total", servers.size(),
                "generatedAt", now.format(TS)), locale);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                "creed-server-report-" + now.format(FILE_TS) + ".pdf");
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Excel (.xlsx) export, dispatched by report type: {@code type} selects the
     * {@link com.creed.report.export.ExcelReportExporter} strategy
     * ({@code server} → the table of this page, {@code environment} → the Environment Inspector
     * snapshot), and every other query parameter is handed to that strategy — which is how the
     * environment report picks up {@code spring.profiles.active} &amp; friends from the URL.
     *
     * <p>Localized like the other exports (headers and sheet names come from the same bundles);
     * an unsupported {@code type} answers 400 via
     * {@link com.creed.report.export.UnknownReportTypeException}.
     */
    @GetMapping(value = "/export/excel", produces = ExcelExportService.CONTENT_TYPE)
    public ResponseEntity<byte[]> exportExcel(@RequestParam(name = "type", defaultValue = "server") String type,
                                              @RequestParam Map<String, String> parameters,
                                              Locale locale) {
        ExcelExportRequest request =
                new ExcelExportRequest(ReportType.of(type), locale, parameters, LocalDateTime.now());
        byte[] body = excelExportService.export(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(ExcelExportService.CONTENT_TYPE));
        headers.setContentDispositionFormData("attachment", excelExportService.filename(request));
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }
}
