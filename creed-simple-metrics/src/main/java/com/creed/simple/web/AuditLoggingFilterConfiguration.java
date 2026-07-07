package com.creed.simple.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link AuditLoggingFilter} explicitly (rather than as a {@code @Component}) so it can be:
 * <ul>
 *   <li><b>scoped</b> to the API surface {@code /camel/*} — keeping {@code /actuator/*} scrape traffic
 *       out of the audit log;</li>
 *   <li><b>ordered outermost</b> ({@link Ordered#HIGHEST_PRECEDENCE}) so its caching wrappers are in
 *       place before any other filter reads the body.</li>
 * </ul>
 * Instantiating the filter here (not via component scan) also avoids Spring Boot's default
 * "register every {@code Filter} bean against {@code /*}" behaviour, which would double-register it.
 */
@Configuration(proxyBeanMethods = false)
public class AuditLoggingFilterConfiguration {

    @Bean
    FilterRegistrationBean<AuditLoggingFilter> auditLoggingFilter() {
        FilterRegistrationBean<AuditLoggingFilter> registration =
                new FilterRegistrationBean<>(new AuditLoggingFilter());
        registration.addUrlPatterns("/camel/*");
        registration.setName("auditLoggingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
