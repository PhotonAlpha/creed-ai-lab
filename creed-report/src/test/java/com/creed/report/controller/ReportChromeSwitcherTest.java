package com.creed.report.controller;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.ReportCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The country/language switcher in {@code fragments/report-chrome} is shared by every report page,
 * so the one thing it must not do is hard-code where its links go: it used to point at
 * {@code /report}, which silently moved a reader off {@code /dynamic} the moment they switched
 * language. The target is now the {@code page} argument, and these tests hold it there.
 */
class ReportChromeSwitcherTest {

    private static final String SWITCHER =
            "<div th:replace=\"~{fragments/report-chrome :: switcher(${profile}, ${countries}, ${page})}\"></div>";

    private SpringTemplateEngine engine;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setCharacterEncoding(StandardCharsets.UTF_8.name());
        files.setOrder(1);
        // Only real template names go to the classpath resolver, so the one-liner below falls
        // through to the string resolver instead of being looked up as a file.
        files.setResolvablePatterns(java.util.Set.of("fragments/*"));
        // The fragment takes parameters, so it is called from a one-line template rather than
        // selected by name.
        StringTemplateResolver inline = new StringTemplateResolver();
        inline.setOrder(2);
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(inline);
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
    }

    @Test
    void theDynamicPageSwitchesCountryAndLanguageOnItself() {
        String html = render("/dynamic", ReportCountry.VN, Locale.forLanguageTag("vi"));

        assertThat(html)
                .contains("href=\"/dynamic?country=th\"")
                .contains("href=\"/dynamic?country=vn\"")
                .contains("href=\"/dynamic?lang=vi\"")
                .contains("href=\"/dynamic?lang=en\"");
        // The regression: a language switch used to land on the server report instead.
        assertThat(html).doesNotContain("/report?");
    }

    @Test
    void theServerReportSwitchesOnItself() {
        String html = render("/report", ReportCountry.GLOBAL, Locale.ENGLISH);

        assertThat(html)
                .contains("href=\"/report?country=vn\"")
                .contains("href=\"/report?lang=zh-CN\"")
                .doesNotContain("/dynamic?");
    }

    @Test
    void theCurrentCountryAndLanguageAreTheActiveButtons() {
        String html = render("/dynamic", ReportCountry.VN, Locale.forLanguageTag("vi"));

        assertThat(html).containsPattern("class=\"[^\"]*active\"\\s*href=\"/dynamic\\?country=vn\"");
        assertThat(html).containsPattern("class=\"[^\"]*active\"\\s*href=\"/dynamic\\?lang=vi\"");
    }

    private String render(String page, ReportCountry country, Locale language) {
        CountryProfile profile = CountryProfile.of(country, language);
        // @{/...} is context-relative, so the switcher only renders on an IWebContext — the very
        // reason the offline exports pass `countries` null and skip it.
        WebContext ctx = new WebContext(exchange(), profile.locale());
        ctx.setVariable("profile", profile);
        ctx.setVariable("countries", List.of(ReportCountry.values()));
        ctx.setVariable("page", page);
        return engine.process(SWITCHER, ctx);
    }

    private static IServletWebExchange exchange() {
        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication application =
                JakartaServletWebApplication.buildApplication(servletContext);
        return application.buildExchange(new MockHttpServletRequest(servletContext),
                new MockHttpServletResponse());
    }
}
