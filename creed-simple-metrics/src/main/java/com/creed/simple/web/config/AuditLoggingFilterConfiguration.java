package com.creed.simple.web.config;

import com.creed.simple.web.filter.AuditLoggingFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>SUPERSEDED by Zalando Logbook's auto-registered servlet filter (see the {@code logbook.*} keys
 * in {@code application.yml}); kept behind {@code creed.audit.legacy-filter.enabled=true} for
 * side-by-side comparison, otherwise the same traffic would be audited twice.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "creed.audit.legacy-filter.enabled", havingValue = "true")
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
