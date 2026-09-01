package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryStyles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.creed.report.i18n.ReportCountry;
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

    private final CountryStyles countryStyles = new CountryStyles();

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
        byte[] pdf = renderReport(ReportCountry.GLOBAL, Locale.ENGLISH);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // A real laid-out page, not an empty shell.
        assertThat(pdf.length).isGreaterThan(1000);
        // English text renders in the embedded Noto Sans (Latin) face.
        assertThat(ascii(pdf)).contains("NotoSans-Regular");
    }

    @Test
    void simplifiedChineseLocaleRendersWithNotoSansSC() {
        String pdf = ascii(renderReport(ReportCountry.GLOBAL, Locale.SIMPLIFIED_CHINESE));
        assertThat(pdf).contains("NotoSansSC-Regular");
    }

    @Test
    void traditionalChineseLocaleRendersWithNotoSansTC() {
        String pdf = ascii(renderReport(ReportCountry.GLOBAL, Locale.TRADITIONAL_CHINESE));
        assertThat(pdf).contains("NotoSansTC-Regular");
    }

    @Test
    void unknownLocaleFallsBackToEnglishBundle() {
        String pdf = ascii(renderReport(ReportCountry.GLOBAL, Locale.forLanguageTag("fr")));
        assertThat(pdf).contains("NotoSans-Regular")
                .doesNotContain("NotoSansSC-Regular")
                .doesNotContain("NotoSansTC-Regular");
    }

    @Test
    void thaiRendersWithNotoSansThai() {
        // Thai is the one added script with no coverage in the Latin/CJK faces; this also pins the
        // th_TH bundle chain, since only it points pdf.font.family at the Thai family.
        String pdf = ascii(renderReport(ReportCountry.TH, Locale.forLanguageTag("th")));
        assertThat(pdf).contains("NotoSansThai-Regular")
                // Flying Saucer picks one family per run, so the Thai face has to carry the
                // Latin host names and IPs too — a fallback to Noto Sans would mean it did not.
                .doesNotContain("NotoSans-Regular");
    }

    @Test
    void latinScriptCountriesKeepTheDefaultNotoSansFace() {
        // Malay and Vietnamese add no script: Noto Sans covers both, Vietnamese diacritics included.
        assertThat(ascii(renderReport(ReportCountry.MY, Locale.forLanguageTag("ms"))))
                .contains("NotoSans-Regular").doesNotContain("NotoSansThai-Regular");
        assertThat(ascii(renderReport(ReportCountry.VN, Locale.forLanguageTag("vi"))))
                .contains("NotoSans-Regular").doesNotContain("NotoSansThai-Regular");
    }

    @Test
    void everyCountryHasItsOwnFragmentTemplateAndStylesheet() {
        // The fragment is resolved by template path (country/<code>/report-pdf) and the CSS by
        // CountryStyles, so a country missing either fails here rather than rendering a bare page.
        for (ReportCountry country : ReportCountry.values()) {
            byte[] pdf = renderReport(country, Locale.ENGLISH);
            assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII))
                    .as("country %s", country.code()).isEqualTo("%PDF-");
            assertThat(countryStyles.pdf(country)).as("pdf stylesheet for %s", country.code())
                    .contains(".total-badge");
        }
    }

    @Test
    void eachCountrysPdfStylesheetIsItsOwnFile() {
        // The refactor's payoff: one file per country instead of one shared block, so no country
        // can silently inherit another's palette.
        assertThat(countryStyles.pdf(ReportCountry.TH)).contains("#a51931").doesNotContain("#010066");
        assertThat(countryStyles.pdf(ReportCountry.MY)).contains("#010066").doesNotContain("#a51931");
        assertThat(countryStyles.pdf(ReportCountry.VN)).contains("#da251d");
    }

    @Test
    void theDynamicReportRendersACallerDefinedTableThroughTheSameChrome() {
        // dynamic-report-export-pdf shares fragments/report-chrome-pdf with the server report, so
        // this also pins that the extracted @page/base CSS still lays a table out.
        DynamicTableService tables = new DynamicTableService(new ObjectMapper(),
                new MessageSourceConfig().messageSource(), new DynamicTableProperties());
        for (ReportCountry country : ReportCountry.values()) {
            CountryProfile profile = CountryProfile.of(country, Locale.ENGLISH);
            DynamicTable table = tables.build(new DynamicTableRequest("Ad hoc", "host,ip,uptimeDays",
                    "[{\"host\":\"a\",\"ip\":\"10.0.0.1\",\"uptimeDays\":1234}]"), profile, profile.locale());

            byte[] pdf = service.renderTemplate("dynamic-report-export-pdf", Map.of(
                    "profile", profile,
                    "countryPdfCss", countryStyles.pdf(country),
                    "table", table,
                    "total", String.valueOf(table.size()),
                    "generatedAt", "2026-09-02 12:00:00"), profile.locale());

            assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII))
                    .as("dynamic pdf for %s", country.code()).isEqualTo("%PDF-");
            assertThat(pdf.length).isGreaterThan(1000);
        }
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

    private byte[] renderReport(ReportCountry country, Locale language) {
        List<ServerInfo> servers = List.of(
                new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server",
                        "CN", "auth", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-gw-02", "10.10.2.22", "creed-gateway",
                        "SG", "gateway", "staging", "ap-se-1a", "green"));
        // Same shape as ReportController: the profile's effective locale is what the template runs in.
        CountryProfile profile = CountryProfile.of(country, language);
        return service.renderTemplate("report-export-pdf", Map.of(
                "profile", profile,
                "countryPdfCss", countryStyles.pdf(country),
                "servers", servers,
                "total", String.valueOf(servers.size()),
                "generatedAt", "2026-07-22 12:00:00"), profile.locale());
    }

    /** Lossless byte-to-char view for searching ASCII names inside binary PDF output. */
    private static String ascii(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }
}
