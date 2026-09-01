package com.creed.report.controller;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.creed.report.model.ServerInfo;
import com.creed.report.service.ServerInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code report-export} exactly the way {@link ReportController#export} does — on a plain
 * {@link Context}, <b>not</b> a web context — for every country edition.
 *
 * <p>That distinction is the point: the offline export has no request behind it, so any
 * {@code @{...}} link expression that reaches this template fails at render time. One did, because
 * {@code th:replace} outranks {@code th:if} and pulled the switcher into the export regardless of
 * its condition — a 500 the country/language unit tests could not see.
 */
class OfflineHtmlExportTemplateTest {

    private final CountryStyles countryStyles = new CountryStyles();

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
    void everyCountryEditionRendersWithoutAWebContext() {
        for (ReportCountry country : ReportCountry.values()) {
            String html = render(country, Locale.ENGLISH);
            assertThat(html).as("country %s", country.code())
                    .contains("<body class=\"" + country.styleClass() + "\"")
                    // The switcher belongs to the live page only.
                    .doesNotContain("btn-outline-light");
        }
    }

    @Test
    void theHeaderAndFooterAreIdenticalInEveryCountry() {
        // The requirement this layout exists for: only the body between them may differ.
        String reference = null;
        for (ReportCountry country : ReportCountry.values()) {
            String html = render(country, Locale.ENGLISH);
            String chrome = between(html, "<nav", "</nav>")
                    + "|" + between(html, "<p class=\"report-footer", "</p>");
            // The country name in the meta line is the one country-varying token; blank it out.
            chrome = chrome.replace(countryName(country), "<country>");
            if (reference == null) {
                reference = chrome;
            }
            assertThat(chrome).as("chrome for %s", country.code()).isEqualTo(reference);
        }
    }

    @Test
    void eachCountryInlinesItsOwnStylesheet() {
        // Only the selected country's sheet is inlined, which is why the country CSS files need no
        // .country-<code> prefix — and why one country can never pick up another's palette.
        assertThat(render(ReportCountry.TH, Locale.ENGLISH)).contains("#a51931").doesNotContain("#010066");
        assertThat(render(ReportCountry.MY, Locale.ENGLISH)).contains("#010066").doesNotContain("#a51931");
        assertThat(render(ReportCountry.VN, Locale.ENGLISH)).contains("#da251d");
    }

    @Test
    void aCountryRendersItsOwnContentBlock() {
        assertThat(render(ReportCountry.TH, Locale.ENGLISH))
                .contains("Thailand")
                .contains("Asia/Bangkok")
                .contains("Buddhist era")
                // Thailand stacks its notice; only Vietnam uses a definition list.
                .doesNotContain("<dl");
        assertThat(render(ReportCountry.VN, Locale.forLanguageTag("vi")))
                .contains("Việt Nam")
                .contains("Asia/Ho_Chi_Minh")
                .contains("<dl");
    }

    @Test
    void theTableIsScopedToTheCountry() {
        assertThat(render(ReportCountry.TH, Locale.ENGLISH))
                .contains("creed-th-gw-01").doesNotContain("creed-my-gw-01");
        assertThat(render(ReportCountry.GLOBAL, Locale.ENGLISH))
                .contains("creed-th-gw-01").contains("creed-my-gw-01");
    }

    private String render(ReportCountry country, Locale language) {
        CountryProfile profile = CountryProfile.of(country, language);
        List<ServerInfo> servers = new ServerInfoService().listServers(country);

        Context ctx = new Context(profile.locale());
        ctx.setVariable("profile", profile);
        ctx.setVariable("servers", servers);
        ctx.setVariable("total", String.valueOf(servers.size()));
        ctx.setVariable("generatedAt", "2026-09-01 12:00:00");
        ctx.setVariable("bootstrapCss", "/* css */");
        ctx.setVariable("bootstrapJs", "/* js */");
        ctx.setVariable("reportCss", "/* shared css */");
        ctx.setVariable("countryCss", countryStyles.browser(country));
        return engine.process("report-export", ctx);
    }

    private static String countryName(ReportCountry country) {
        return new MessageSourceConfig().messageSource().getMessage("report.country.name", null,
                CountryProfile.of(country, Locale.ENGLISH).locale());
    }

    private static String between(String html, String start, String end) {
        int from = html.indexOf(start);
        int to = html.indexOf(end, from);
        assertThat(from).isNotNegative();
        assertThat(to).isNotNegative();
        return html.substring(from, to + end.length());
    }
}
