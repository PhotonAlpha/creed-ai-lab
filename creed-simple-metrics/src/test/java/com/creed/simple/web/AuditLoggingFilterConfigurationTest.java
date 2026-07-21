package com.creed.simple.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditLoggingFilterConfiguration}: the {@link AuditLoggingFilter} is registered
 * scoped to {@code /camel/*} and ordered outermost ({@link Ordered#HIGHEST_PRECEDENCE}) so its caching
 * wrappers are installed before any other filter reads the body.
 */
class AuditLoggingFilterConfigurationTest {

    private final AuditLoggingFilterConfiguration config = new AuditLoggingFilterConfiguration();

    @Test
    void registersAuditFilterScopedToCamelAndOutermost() {
        FilterRegistrationBean<AuditLoggingFilter> registration = config.auditLoggingFilter();

        assertThat(registration.getFilter()).isInstanceOf(AuditLoggingFilter.class);
        assertThat(registration.getUrlPatterns()).containsExactly("/camel/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
