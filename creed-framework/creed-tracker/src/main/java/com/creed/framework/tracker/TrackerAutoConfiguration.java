package com.creed.framework.tracker;

import com.creed.framework.tracker.logbook.TrackingSink;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import org.zalando.logbook.HttpLogFormatter;
import org.zalando.logbook.Sink;

/**
 * {@code creed.tracker.*} - on by default in a servlet application that has this jar.
 *
 * <p>Runs before Logbook's auto-configuration, so {@link TrackingSink} is the {@code Sink} Logbook's
 * {@code @ConditionalOnMissingBean} finds (Logbook's own {@code DefaultSink} backs off; a {@code logbook.write.chunk-size}
 * still wraps it).
 */
@AutoConfiguration(beforeName = "org.zalando.logbook.autoconfigure.LogbookAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "creed.tracker.enabled", matchIfMissing = true)
@EnableConfigurationProperties(TrackerProperties.class)
public class TrackerAutoConfiguration {

    /**
     * Right after the trace filters (Boot's {@code ServerHttpObservationFilter} is {@code HIGHEST_PRECEDENCE + 1}) and
     * long before Logbook's ({@code LOWEST_PRECEDENCE}), so its request line already has the sid. ASYNC too: an SSE
     * answer completes on an async dispatch, which Logbook logs.
     */
    @Bean
    FilterRegistrationBean<TrackerFilter> trackerFilter(TrackerProperties properties, Environment environment) {
        DataSize max = Binder.get(environment)
                .bind("server.tomcat.max-http-response-header-size", DataSize.class)
                .orElse(DataSize.ofKilobytes(8));
        FilterRegistrationBean<TrackerFilter> registration =
                new FilterRegistrationBean<>(new TrackerFilter(properties, max));
        registration.setName("trackerFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Sink.class)
    @ConditionalOnBooleanProperty(name = "creed.tracker.logbook.enabled", matchIfMissing = true)
    static class LogbookSinkConfiguration {

        @Bean
        @ConditionalOnMissingBean(Sink.class)
        Sink trackingSink(HttpLogFormatter formatter) {
            return new TrackingSink(formatter);
        }
    }
}
