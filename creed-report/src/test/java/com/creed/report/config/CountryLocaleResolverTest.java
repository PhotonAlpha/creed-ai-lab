package com.creed.report.config;

import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-level wiring of the two axes: that {@code ?lang=} and {@code ?country=} are really
 * independent, that each is remembered separately, and that the pair lands on one locale.
 */
class CountryLocaleResolverTest {

    private final CountryProperties properties = new CountryProperties();
    private final CountryCatalog catalog = new CountryCatalog(properties);
    private final CountryLocaleResolver resolver = new CountryLocaleResolver(
            new CookieLocaleResolver(LocaleConfig.LANGUAGE_COOKIE), LocaleConfig.LANGUAGE_PARAM,
            LocaleConfig.LANGUAGE_COOKIE, catalog,
            properties.getParamName(), properties.getCookieName());
    private final CountryChangeInterceptor interceptor = new CountryChangeInterceptor(properties);

    @Test
    void bothAxesCombineIntoOneLocale() {
        MockHttpServletRequest request = get("/report");
        request.addParameter("country", "my");
        request.addParameter("lang", "en");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("en", "MY"));
    }

    @Test
    void switchingCountryWithoutAChosenLanguageAdoptsThatCountrysLanguage() {
        // Clicking the Thailand button must not leave a Thai edition rendered in English merely
        // because the browser sends Accept-Language: en.
        MockHttpServletRequest request = get("/report");
        request.addParameter("country", "th");
        request.addPreferredLocale(Locale.ENGLISH);

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("th", "TH"));
    }

    @Test
    void theCountrysLanguageStillAppliesOnLaterRequestsThatCarryNoParameters() {
        // The exports have no query string of their own, so the rule has to key off the cookie too
        // -- otherwise the page renders in Thai and its downloads come back in English.
        MockHttpServletRequest request = get("/export");
        request.setCookies(new Cookie("creed-report-country", "th"));
        request.addPreferredLocale(Locale.ENGLISH);

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("th", "TH"));
    }

    @Test
    void aChosenLanguageSurvivesACountrySwitch() {
        // ...but once the visitor has actually picked one, it wins wherever the country offers it.
        MockHttpServletRequest request = get("/report");
        request.setCookies(new Cookie(LocaleConfig.LANGUAGE_COOKIE, "en"));
        request.addParameter("country", "th");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("en", "TH"));
    }

    @Test
    void switchingLanguageKeepsTheCountry() {
        // The two cookies are separate on purpose: a language click must not reset the edition.
        MockHttpServletRequest request = get("/report");
        request.setCookies(new Cookie("creed-report-country", "vn"),
                new Cookie(LocaleConfig.LANGUAGE_COOKIE, "en"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("en", "VN"));
    }

    @Test
    void theParameterWinsOverTheCookie() {
        MockHttpServletRequest request = get("/report");
        request.setCookies(new Cookie("creed-report-country", "vn"),
                new Cookie(LocaleConfig.LANGUAGE_COOKIE, "en"));
        request.addParameter("country", "th");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("en", "TH"));
    }

    @Test
    void acceptLanguageAloneSelectsBothAxes() {
        MockHttpServletRequest request = get("/report");
        request.addPreferredLocale(Locale.forLanguageTag("th-TH"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("th", "TH"));
    }

    @Test
    void languageChangesAreStillHandledByTheStandardInterceptor() throws Exception {
        // LocaleChangeInterceptor calls setLocale on the resolver; delegation has to reach the
        // wrapped CookieLocaleResolver or ?lang= silently stops working.
        MockHttpServletRequest request = get("/report");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addParameter("lang", "vi");
        request.addParameter("country", "vn");

        // LocaleChangeInterceptor reaches the resolver through the request attribute DispatcherServlet
        // normally sets; there is no DispatcherServlet here, so stand it in.
        request.setAttribute(DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE, resolver);
        LocaleChangeInterceptor localeChange = new LocaleChangeInterceptor();
        localeChange.setParamName("lang");
        localeChange.preHandle(request, response, null);
        interceptor.preHandle(request, response, null);

        assertThat(response.getCookie(LocaleConfig.LANGUAGE_COOKIE)).isNotNull()
                .extracting(Cookie::getValue).isEqualTo("vi");
        assertThat(response.getCookie("creed-report-country")).isNotNull()
                .extracting(Cookie::getValue).isEqualTo("vn");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.of("vi", "VN"));
    }

    @Test
    void anUnknownCountryIsIgnoredRatherThanRemembered() {
        MockHttpServletRequest request = get("/report");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addParameter("country", "atlantis");
        request.addPreferredLocale(Locale.ENGLISH);

        interceptor.preHandle(request, response, null);

        assertThat(response.getCookie("creed-report-country")).isNull();
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void theCountryCookieIsScopedToTheContextPath() {
        MockHttpServletRequest request = get("/report");
        request.setContextPath("/report");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addParameter("country", "my");

        interceptor.preHandle(request, response, null);

        assertThat(response.getCookie("creed-report-country").getPath()).isEqualTo("/report");
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
