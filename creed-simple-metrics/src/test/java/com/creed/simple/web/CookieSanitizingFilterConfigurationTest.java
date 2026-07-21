package com.creed.simple.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CookieSanitizingFilterConfiguration}: the {@link CookieSanitizingFilter} is
 * registered scoped to {@code /camel/*} and ordered just inside the audit filter
 * ({@link Ordered#HIGHEST_PRECEDENCE} {@code + 10}), so the audit log still records the raw cookies
 * while downstream code receives the sanitized view.
 */
class CookieSanitizingFilterConfigurationTest {

    private final CookieSanitizingFilterConfiguration config = new CookieSanitizingFilterConfiguration();

    @Test
    void registersSanitizerScopedToCamelJustInsideTheAuditFilter() {
        FilterRegistrationBean<CookieSanitizingFilter> registration = config.cookieSanitizingFilter();

        assertThat(registration.getFilter()).isInstanceOf(CookieSanitizingFilter.class);
        assertThat(registration.getUrlPatterns()).containsExactly("/camel/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Test
    void sanitizerRunsInsideTheAuditFilter() {
        int auditOrder = new AuditLoggingFilterConfiguration().auditLoggingFilter().getOrder();
        int sanitizerOrder = config.cookieSanitizingFilter().getOrder();
        assertThat(sanitizerOrder).isGreaterThan(auditOrder);
    }
}
