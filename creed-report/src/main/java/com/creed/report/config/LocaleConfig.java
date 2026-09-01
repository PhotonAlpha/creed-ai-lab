package com.creed.report.config;

import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Presentation locale for the report pages and exports, on <b>two independent axes</b>:
 *
 * <ul>
 *   <li><b>Language</b> — {@code ?lang=} (BCP-47 or underscore form, e.g. {@code th} / {@code ms} /
 *       {@code zh-CN}), remembered in the {@code creed-report-locale} cookie, else
 *       {@code Accept-Language}.</li>
 *   <li><b>Country</b> — {@code ?country=} ({@code global} / {@code th} / {@code my} / {@code vn}),
 *       remembered in the {@code creed-report-country} cookie, else a hint from the language.</li>
 * </ul>
 *
 * <p>{@link CountryLocaleResolver} folds the two into one {@link java.util.Locale} whose region
 * subtag is the country, so controllers keep seeing a plain {@code Locale} parameter and the views,
 * the offline HTML export, the PDF export and the Excel export all follow the same choice — with
 * the country reaching the message bundles as the {@code _en_MY} style region layer.
 *
 * <p>Both parameters work on any endpoint and are independent: {@code ?country=my&lang=en} renders
 * Malaysia in English. A language a country does not offer falls back to that country's default,
 * and so does a bare {@code ?country=} switch by a visitor who has never picked a language.
 */
@Configuration
@EnableConfigurationProperties(CountryProperties.class)
public class LocaleConfig implements WebMvcConfigurer {

    /** Query parameter and cookie of the language axis; the country axis names them differently. */
    static final String LANGUAGE_PARAM = "lang";
    static final String LANGUAGE_COOKIE = "creed-report-locale";

    private final CountryProperties countryProperties;

    public LocaleConfig(CountryProperties countryProperties) {
        this.countryProperties = countryProperties;
    }

    @Bean
    public LocaleResolver localeResolver(CountryCatalog countryCatalog) {
        // No default locale set: falls back to Accept-Language until a ?lang= choice is made.
        CookieLocaleResolver languageResolver = new CookieLocaleResolver(LANGUAGE_COOKIE);
        if (countryProperties.getCookieMaxAge() != null) {
            // Kept in step with the country cookie, so one axis cannot outlive the other.
            languageResolver.setCookieMaxAge(countryProperties.getCookieMaxAge());
        }
        return new CountryLocaleResolver(languageResolver, LANGUAGE_PARAM, LANGUAGE_COOKIE,
                countryCatalog, countryProperties.getParamName(), countryProperties.getCookieName());
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANGUAGE_PARAM);
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Bean
    public CountryChangeInterceptor countryChangeInterceptor() {
        return new CountryChangeInterceptor(countryProperties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(countryChangeInterceptor());
    }
}
