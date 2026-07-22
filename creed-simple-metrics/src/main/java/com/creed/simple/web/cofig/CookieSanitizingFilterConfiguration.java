package com.creed.simple.web.cofig;

import com.creed.simple.web.filter.CookieSanitizingFilter;
import com.creed.simple.web.filter.AuditLoggingFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CookieSanitizingFilter} explicitly so it can be:
 * <ul>
 *   <li><b>scoped</b> to {@code /camel/*} — the same API surface as {@link AuditLoggingFilter}
 *       (actuator scrape traffic carries no cookies worth sanitizing);</li>
 *   <li><b>ordered just inside</b> the audit filter ({@link Ordered#HIGHEST_PRECEDENCE} {@code + 10}) so
 *       the audit log still records the raw inbound cookies while downstream business code receives the
 *       sanitized view.</li>
 * </ul>
 * Registering here (not via {@code @Component}) avoids Spring Boot's default "map every {@code Filter}
 * bean to {@code /*}" behaviour.
 */
@Configuration(proxyBeanMethods = false)
public class CookieSanitizingFilterConfiguration {

    @Bean
    FilterRegistrationBean<CookieSanitizingFilter> cookieSanitizingFilter() {
        FilterRegistrationBean<CookieSanitizingFilter> registration =
                new FilterRegistrationBean<>(new CookieSanitizingFilter());
        registration.addUrlPatterns("/camel/*");
        registration.setName("cookieSanitizingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
