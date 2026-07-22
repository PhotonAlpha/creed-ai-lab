package com.creed.report.service;

import com.creed.report.model.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Thymeleaf -> openpdf-html pipeline without a Spring context: a standalone
 * {@link SpringTemplateEngine} (SpEL, like the auto-configured engine — the plain TemplateEngine
 * would require OGNL, which is not on the classpath) resolving {@code classpath:/templates/}.
 */
class PdfExportServiceTest {

    private PdfExportService service;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        service = new PdfExportService(engine, "");
    }

    @Test
    void rendersReportPdfTemplateToPdfBytes() {
        List<ServerInfo> servers = List.of(
                new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server",
                        "CN", "auth", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-gw-02", "10.10.2.22", "creed-gateway",
                        "SG", "gateway", "staging", "ap-se-1a", "green"));

        byte[] pdf = service.renderTemplate("report-export-pdf", Map.of(
                "servers", servers,
                "total", servers.size(),
                "generatedAt", "2026-07-22 12:00:00"));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // A real laid-out page, not an empty shell.
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void rendersArbitraryWellFormedXhtml() {
        byte[] pdf = service.renderHtml(
                "<html><head><title>t</title></head><body><p>hello pdf</p></body></html>");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void sloppyMarkupIsRepairedByTheBundledLenientParser() {
        // openpdf-html parses via neko-htmlunit, which repairs HTML instead of failing on it —
        // unlike classic Flying Saucer's strict XML parser. Templates are still kept well-formed
        // for predictable layout; this test just pins the lenient behaviour.
        byte[] pdf = service.renderHtml("<html><body><p>unclosed");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
