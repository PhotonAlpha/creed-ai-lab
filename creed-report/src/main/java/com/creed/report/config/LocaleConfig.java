package com.creed.report.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Locale handling for the report pages and exports: {@code ?lang=} (BCP-47 or underscore form,
 * e.g. {@code zh-CN} / {@code zh_TW} / {@code en}) switches the locale on any endpoint and is
 * remembered in a cookie; without the cookie the request's {@code Accept-Language} decides.
 * Controllers see the outcome as their {@code Locale} parameter, so views, the offline HTML export
 * and the PDF export all follow the same choice.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        // No default locale set: falls back to Accept-Language until a ?lang= choice is made.
        return new CookieLocaleResolver("creed-report-locale");
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
