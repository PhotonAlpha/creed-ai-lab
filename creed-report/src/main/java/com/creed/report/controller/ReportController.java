package com.creed.report.controller;

import com.creed.report.model.ServerInfo;
import com.creed.report.service.AssetService;
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

@Controller
public class ReportController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInfoService serverInfoService;
    private final AssetService assetService;
    private final TemplateEngine templateEngine;

    public ReportController(ServerInfoService serverInfoService,
                            AssetService assetService,
                            TemplateEngine templateEngine) {
        this.serverInfoService = serverInfoService;
        this.assetService = assetService;
        this.templateEngine = templateEngine;
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }
}
