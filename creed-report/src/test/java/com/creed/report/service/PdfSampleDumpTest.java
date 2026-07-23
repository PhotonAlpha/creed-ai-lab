package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.model.ServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Throwaway sample dump for manual inspection; enabled with -Dpdf.sample.dir=<dir>. */
class PdfSampleDumpTest {

    @Test
    @EnabledIfSystemProperty(named = "pdf.sample.dir", matches = ".+")
    void dumpSamples() throws Exception {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
        PdfExportService service = new PdfExportService(engine,
                new PathMatchingResourcePatternResolver(), "classpath:/fonts/*.ttf,classpath:/fonts/*.otf");

        List<ServerInfo> servers = List.of(
                new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server",
                        "CN", "auth", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-gw-02", "10.10.2.22", "creed-gateway",
                        "SG", "gateway", "staging", "ap-se-1a", "green"));

        Path dir = Path.of(System.getProperty("pdf.sample.dir"));
        for (Locale locale : List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            byte[] pdf = service.renderTemplate("report-export-pdf", Map.of(
                    "servers", servers,
                    "total", servers.size(),
                    "generatedAt", "2026-07-24 00:00:00"), locale);
            Files.write(dir.resolve("report-" + locale.toLanguageTag() + ".pdf"), pdf);
        }
    }
}
