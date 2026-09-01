package com.creed.report.config;

import com.creed.report.i18n.CountryProperties;
import com.creed.report.i18n.ReportCountry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The country counterpart of
 * {@link org.springframework.web.servlet.i18n.LocaleChangeInterceptor}: remembers a
 * {@code ?country=} choice in a cookie so it survives to the next request, including the export
 * links, which carry no query string of their own.
 *
 * <p>It only <em>persists</em> the choice — {@link CountryLocaleResolver} reads the parameter
 * itself, since the request's locale is resolved before interceptors run. An unknown value is
 * ignored rather than rejected, mirroring {@code LocaleChangeInterceptor}'s
 * {@code ignoreInvalidLocale}.
 */
public class CountryChangeInterceptor implements HandlerInterceptor {

    private final CountryProperties properties;

    public CountryChangeInterceptor(CountryProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String value = request.getParameter(properties.getParamName());
        if (value != null) {
            ReportCountry.of(value).ifPresent(country -> response.addCookie(cookie(request, country)));
        }
        return true;
    }

    private Cookie cookie(HttpServletRequest request, ReportCountry country) {
        Cookie cookie = new Cookie(properties.getCookieName(), country.code());
        // Context-path scoped like the locale cookie, so /report keeps its own preference.
        String contextPath = request.getContextPath();
        cookie.setPath(contextPath.isEmpty() ? "/" : contextPath);
        cookie.setHttpOnly(true);
        if (properties.getCookieMaxAge() != null) {
            cookie.setMaxAge((int) properties.getCookieMaxAge().toSeconds());
        }
        return cookie;
    }
}
