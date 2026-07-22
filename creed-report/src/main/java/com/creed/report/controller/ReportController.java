package com.creed.report.controller;

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
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Controller
public class ReportController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInfoService serverInfoService;
    private final AssetService assetService;
    private final TemplateEngine templateEngine;
    private final PdfExportService pdfExportService;

    public ReportController(ServerInfoService serverInfoService,
                            AssetService assetService,
                            TemplateEngine templateEngine,
                            PdfExportService pdfExportService) {
        this.serverInfoService = serverInfoService;
        this.assetService = assetService;
        this.templateEngine = templateEngine;
        this.pdfExportService = pdfExportService;
    }

    @GetMapping({ "/report"})
    public String report(Model model) {
        List<ServerInfo> servers = serverInfoService.listServers();
        model.addAttribute("servers", servers);
        model.addAttribute("total", servers.size());
        model.addAttribute("generatedAt", LocalDateTime.now().format(TS));
        return "report";
    }

    @GetMapping(value = "/export", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> export() {
        LocalDateTime now = LocalDateTime.now();
        List<ServerInfo> servers = serverInfoService.listServers();

        Context ctx = new Context();
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
     * PDF twin of {@link #export()}: renders the print-oriented {@code report-pdf.html} (well-formed
     * XHTML, paged-media CSS — NOT the Bootstrap view template) through openpdf-html. See
     * {@link PdfExportService} for the template constraints and CJK font configuration.
     */
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf() {
        LocalDateTime now = LocalDateTime.now();
        List<ServerInfo> servers = serverInfoService.listServers();

        byte[] body = pdfExportService.renderTemplate("report-pdf", Map.of(
                "servers", servers,
                "total", servers.size(),
                "generatedAt", now.format(TS)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                "creed-server-report-" + now.format(FILE_TS) + ".pdf");
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }
}
