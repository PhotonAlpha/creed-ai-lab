package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.model.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Thymeleaf -> openpdf-html pipeline without a Spring context: a standalone
 * {@link SpringTemplateEngine} (SpEL, like the auto-configured engine — the plain TemplateEngine
 * would require OGNL, which is not on the classpath) resolving {@code classpath:/templates/}, the
 * production {@link MessageSourceConfig} bundle chain, and the bundled Noto fonts from
 * {@code classpath:/fonts/}.
 *
 * <p>The locale tests assert on the embedded font's PostScript name in the raw PDF bytes
 * ({@code /BaseFont /XXXXXX+NotoSansSC-Regular} is written uncompressed): it only appears when the
 * locale's message bundle resolved, the CSS font stack pointed at the right family, AND the font
 * file registered — so one assertion covers the whole localization chain.
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
        // The production bundle chain itself, so a basename/encoding change cannot pass here and
        // fail at runtime. Instantiated directly: outside a container the @Bean methods are plain
        // methods, and messageSource() wires its own parent.
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
        service = new PdfExportService(engine, new PathMatchingResourcePatternResolver(),
                "classpath:/fonts/*.ttf,classpath:/fonts/*.otf");
    }

    @Test
    void rendersReportPdfTemplateToPdfBytes() {
        byte[] pdf = renderReport(Locale.ENGLISH);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // A real laid-out page, not an empty shell.
        assertThat(pdf.length).isGreaterThan(1000);
        // English text renders in the embedded Noto Sans (Latin) face.
        assertThat(ascii(pdf)).contains("NotoSans-Regular");
    }

    @Test
    void simplifiedChineseLocaleRendersWithNotoSansSC() {
        String pdf = ascii(renderReport(Locale.SIMPLIFIED_CHINESE));
        assertThat(pdf).contains("NotoSansSC-Regular");
    }

    @Test
    void traditionalChineseLocaleRendersWithNotoSansTC() {
        String pdf = ascii(renderReport(Locale.TRADITIONAL_CHINESE));
        assertThat(pdf).contains("NotoSansTC-Regular");
    }

    @Test
    void unknownLocaleFallsBackToEnglishBundle() {
        String pdf = ascii(renderReport(Locale.forLanguageTag("fr")));
        assertThat(pdf).contains("NotoSans-Regular")
                .doesNotContain("NotoSansSC-Regular")
                .doesNotContain("NotoSansTC-Regular");
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

    private byte[] renderReport(Locale locale) {
        List<ServerInfo> servers = List.of(
                new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server",
                        "CN", "auth", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-gw-02", "10.10.2.22", "creed-gateway",
                        "SG", "gateway", "staging", "ap-se-1a", "green"));
        return service.renderTemplate("report-export-pdf", Map.of(
                "servers", servers,
                "total", servers.size(),
                "generatedAt", "2026-07-22 12:00:00"), locale);
    }

    /** Lossless byte-to-char view for searching ASCII names inside binary PDF output. */
    private static String ascii(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }
}
