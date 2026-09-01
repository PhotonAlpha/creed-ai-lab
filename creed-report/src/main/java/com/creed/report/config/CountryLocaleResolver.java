package com.creed.report.config;

import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.ReportCountry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.LocaleContextResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

/**
 * Folds the two independent presentation axes — language and country — into the single
 * {@link Locale} the rest of Spring MVC runs on.
 *
 * <p>Language keeps coming from a plain {@link CookieLocaleResolver} ({@code ?lang=} via
 * {@link org.springframework.web.servlet.i18n.LocaleChangeInterceptor}, else the cookie, else
 * {@code Accept-Language}); country comes from {@code ?country=}, its own cookie, or a hint in the
 * language. {@link CountryCatalog} then combines them, and the country's region subtag is what
 * makes {@code report-messages_en_MY.properties} resolve.
 *
 * <p>Composition, not inheritance: the delegate owns the locale cookie's parsing, writing and
 * request-attribute caching, so {@code LocaleChangeInterceptor} keeps working unchanged and this
 * class only decides which locale the two cookies add up to. Resolution reads {@code ?country=}
 * directly rather than relying on {@link CountryChangeInterceptor}, because
 * {@code DispatcherServlet} builds the request's {@code LocaleContext} <em>before</em> any
 * interceptor runs — the interceptor only persists the choice.
 */
public class CountryLocaleResolver implements LocaleContextResolver {

    private final CookieLocaleResolver languageResolver;
    private final String languageParam;
    private final String languageCookie;
    private final CountryCatalog catalog;
    private final String countryParam;
    private final String countryCookie;

    public CountryLocaleResolver(CookieLocaleResolver languageResolver, String languageParam,
                                 String languageCookie, CountryCatalog catalog,
                                 String countryParam, String countryCookie) {
        this.languageResolver = languageResolver;
        this.languageParam = languageParam;
        this.languageCookie = languageCookie;
        this.catalog = catalog;
        this.countryParam = countryParam;
        this.countryCookie = countryCookie;
    }

    @Override
    public LocaleContext resolveLocaleContext(HttpServletRequest request) {
        LocaleContext languageContext = languageResolver.resolveLocaleContext(request);
        Locale hinted = languageContext.getLocale() != null ? languageContext.getLocale() : request.getLocale();
        ReportCountry country = catalog.resolve(request.getParameter(countryParam),
                cookieValue(request, countryCookie), hinted);
        // A country the visitor picked lands on that country's own language: clicking the Thailand
        // button must not leave a Thai edition in English just because the browser's
        // Accept-Language says so. A language the visitor actually chose (?lang= or the locale
        // cookie) still wins, which is what keeps ?country=my&lang=en -- Malaysia in English --
        // reachable.
        //
        // This deliberately keys off the *selection* (parameter OR cookie), not off this request
        // carrying ?country=: the exports carry no query string, so keying off the parameter would
        // render the page in Thai and its downloads in English.
        Locale requested = (hasSelectedCountry(request) && !hasChosenLanguage(request)) ? null : hinted;
        Locale effective = catalog.profile(country, requested).locale();

        if (languageContext instanceof TimeZoneAwareLocaleContext timeZoneAware) {
            return new SimpleTimeZoneAwareLocaleContext(effective, timeZoneAware.getTimeZone());
        }
        return new SimpleLocaleContext(effective);
    }

    /**
     * Writes the language half only. The country half is a separate cookie owned by
     * {@link CountryChangeInterceptor}, so a language switch never silently changes the country.
     */
    @Override
    public void setLocaleContext(HttpServletRequest request, @Nullable HttpServletResponse response,
                                 @Nullable LocaleContext localeContext) {
        languageResolver.setLocaleContext(request, response, localeContext);
    }

    /** Whether the visitor picked a country, this request or earlier — as opposed to it being inferred. */
    private boolean hasSelectedCountry(HttpServletRequest request) {
        return ReportCountry.of(request.getParameter(countryParam)).isPresent()
                || ReportCountry.of(cookieValue(request, countryCookie)).isPresent();
    }

    /** Whether the visitor ever picked a language, as opposed to a browser sending Accept-Language. */
    private boolean hasChosenLanguage(HttpServletRequest request) {
        return request.getParameter(languageParam) != null || cookieValue(request, languageCookie) != null;
    }

    @Nullable
    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
