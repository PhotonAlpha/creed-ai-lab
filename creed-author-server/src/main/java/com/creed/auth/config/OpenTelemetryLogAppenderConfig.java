package com.creed.auth.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the autoconfigured {@link OpenTelemetry} SDK into the static
 * {@link OpenTelemetryAppender} declared in logback-spring.xml, so log events captured by
 * Logback can be exported through the same OTLP pipeline as traces / metrics.
 */
@Configuration
@ConditionalOnBean(OpenTelemetry.class)
public class OpenTelemetryLogAppenderConfig {

    private final OpenTelemetry openTelemetry;

    public OpenTelemetryLogAppenderConfig(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @PostConstruct
    public void installOpenTelemetryAppender() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}