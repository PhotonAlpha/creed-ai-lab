package com.creed.report.controller;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code dynamic-report-export} the way {@link DynamicReportController#export} does — on a
 * plain non-web {@link Context} — for every country, checking that a caller-defined table gets the
 * same chrome, country styling and localization as the built-in server report.
 */
class DynamicReportTemplateTest {

    private static final String HEADERS = "host,ip,app,uptimeDays";
    private static final String DATA = """
            [{"host":"creed-th-gw-01","ip":"10.30.1.11","app":"creed-gateway","uptimeDays":1234},
             {"host":"creed-th-pay-01","ip":"10.30.2.21","app":"creed-resource-payment","uptimeDays":7}]
            """;

    private final CountryStyles countryStyles = new CountryStyles();
    private final DynamicTableService tableService = new DynamicTableService(
            new ObjectMapper(), new MessageSourceConfig().messageSource(), new DynamicTableProperties());

    private SpringTemplateEngine engine;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
    }

    @Test
    void theHeaderRowIsWhateverHeadersNamed() {
        String html = render(ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(html).contains(">Host<").contains(">IP<").contains(">App<")
                // No message for this key, so the key itself is the label.
                .contains(">uptimeDays<");
    }

    @Test
    void theRowsComeFromTheJsonAndFollowTheColumnOrder() {
        assertThat(render(ReportCountry.GLOBAL, Locale.ENGLISH))
                .contains("creed-th-gw-01").contains("10.30.1.11").contains("creed-resource-payment");
    }

    @Test
    void knownColumnKeysAreTranslatedAndValuesFormattedForTheCountry() {
        // Same headers=, same data=, different edition: labels follow the language, the number
        // grouping follows the country.
        assertThat(render(ReportCountry.VN, Locale.forLanguageTag("vi")))
                .contains(">Máy chủ<").contains(">Ứng dụng<").contains("1.234");
        assertThat(render(ReportCountry.GLOBAL, Locale.ENGLISH)).contains("1,234");
        assertThat(render(ReportCountry.TH, Locale.forLanguageTag("th"))).contains(">โฮสต์<");
    }

    @Test
    void everyCountryRendersWithItsOwnStyleAndContentBlock() {
        for (ReportCountry country : ReportCountry.values()) {
            String html = render(country, Locale.ENGLISH);
            assertThat(html).as("country %s", country.code())
                    .contains("<body class=\"" + country.styleClass() + "\"")
                    .contains("report-table");
        }
        assertThat(render(ReportCountry.TH, Locale.ENGLISH)).contains("Asia/Bangkok").contains("#a51931");
        assertThat(render(ReportCountry.MY, Locale.ENGLISH)).contains("Asia/Kuala_Lumpur").contains("#010066");
    }

    @Test
    void theChromeIsTheSameAsTheServerReports() {
        // Both reports pull header and footer from fragments/report-chrome, so a new report cannot
        // grow chrome of its own.
        String html = render(ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(html).contains("navbar navbar-dark report-header shadow-sm")
                .contains("class=\"report-footer small mt-3 mb-0\"")
                // The definition form and switchers belong to the live page, not the download.
                .doesNotContain("<form").doesNotContain("btn-outline-light");
    }

    @Test
    void anEmptyDataSetStillRendersTheDeclaredColumns() {
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        DynamicTable table = tableService.build(
                new DynamicTableRequest(null, HEADERS, null), profile, profile.locale());
        String html = render(profile, table);
        assertThat(html).contains(">Host<").contains("No server records.")
                // One spanned cell across the index column plus the four declared ones.
                .contains("colspan=\"5\"");
    }

    private String render(ReportCountry country, Locale language) {
        CountryProfile profile = CountryProfile.of(country, language);
        return render(profile, tableService.build(
                new DynamicTableRequest(null, HEADERS, DATA), profile, profile.locale()));
    }

    private String render(CountryProfile profile, DynamicTable table) {
        Context ctx = new Context(profile.locale());
        ctx.setVariable("profile", profile);
        ctx.setVariable("table", table);
        ctx.setVariable("total", String.valueOf(table.size()));
        ctx.setVariable("generatedAt", "2026-09-02 12:00:00");
        ctx.setVariable("bootstrapCss", "/* css */");
        ctx.setVariable("bootstrapJs", "/* js */");
        ctx.setVariable("reportCss", "/* shared css */");
        ctx.setVariable("countryCss", countryStyles.browser(profile.country()));
        return engine.process("dynamic-report-export", ctx);
    }
}
