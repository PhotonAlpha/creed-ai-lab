package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.creed.report.model.ServerInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Throwaway sample dump for eyeballing the PDF templates without starting the app — the fastest way
 * to iterate on {@code report-export-pdf} / {@code dynamic-report-export-pdf}, since it renders
 * through the real engine, the real bundles and the real fonts but skips Tomcat entirely.
 *
 * <pre>
 * mvn -pl creed-report test -Dtest=PdfSampleDumpTest -Dpdf.sample.dir=/tmp/pdf
 * open /tmp/pdf/dynamic-th-th-TH.pdf
 * </pre>
 *
 * Disabled unless {@code -Dpdf.sample.dir} names an existing directory, so a normal build never
 * writes files.
 */
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

        CountryStyles countryStyles = new CountryStyles();
        Path dir = Path.of(System.getProperty("pdf.sample.dir"));
        // Fixed instant, but formatted the way the endpoints do it -- otherwise the samples would
        // show a Gregorian date in the Thai edition and hide the very thing they exist to preview.
        LocalDateTime generatedAt = LocalDateTime.of(2026, 7, 24, 14, 5, 30);
        // One sample per country edition in each language it offers, so the per-country style
        // blocks and content fragments can be eyeballed side by side.
        for (ReportCountry country : ReportCountry.values()) {
            for (String tag : country.languages()) {
                CountryProfile profile = CountryProfile.of(country, Locale.forLanguageTag(tag));
                byte[] pdf = service.renderTemplate("report-export-pdf", Map.of(
                        "profile", profile,
                        "countryPdfCss", countryStyles.pdf(country),
                        "servers", servers,
                        "total", CountryFormatter.number(servers.size(), profile),
                        "generatedAt", CountryFormatter.timestamp(generatedAt, profile)), profile.locale());
                Files.write(dir.resolve("report-" + country.code() + "-"
                        + profile.locale().toLanguageTag() + ".pdf"), pdf);
            }
        }

        // The dynamic report, with a table wide enough to show what a caller-defined one looks like:
        // a translated key (host/ip/app), a key with no message (uptimeDays), an explicit label,
        // and a number so the country's grouping is visible.
        DynamicTableService tables = new DynamicTableService(new ObjectMapper(),
                new MessageSourceConfig().messageSource(), new DynamicTableProperties());
        DynamicTableRequest definition = new DynamicTableRequest(null,
                "host,ip,app,env,uptimeDays,cost:Cost",
                """
                [{"host":"creed-th-gw-01","ip":"10.30.1.11","app":"creed-gateway","env":"prod","uptimeDays":1234,"cost":98.5},
                 {"host":"creed-th-pay-01","ip":"10.30.2.21","app":"creed-resource-payment","env":"prod","uptimeDays":7,"cost":12.25},
                 {"host":"creed-th-pay-02","ip":"10.30.2.22","app":"creed-resource-payment","env":"staging","uptimeDays":56789,"cost":4.5}]
                """);
        for (ReportCountry country : ReportCountry.values()) {
            for (String tag : country.languages()) {
                CountryProfile profile = CountryProfile.of(country, Locale.forLanguageTag(tag));
                DynamicTable table = tables.build(definition, profile, profile.locale());
                byte[] pdf = service.renderTemplate("dynamic-report-export-pdf", Map.of(
                        "profile", profile,
                        "countryPdfCss", countryStyles.pdf(country),
                        "table", table,
                        "total", CountryFormatter.number(table.size(), profile),
                        "generatedAt", CountryFormatter.timestamp(generatedAt, profile)), profile.locale());
                Files.write(dir.resolve("dynamic-" + country.code() + "-"
                        + profile.locale().toLanguageTag() + ".pdf"), pdf);
            }
        }
    }
}
