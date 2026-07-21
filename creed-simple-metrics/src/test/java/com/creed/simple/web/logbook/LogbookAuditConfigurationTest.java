package com.creed.simple.web.logbook;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Strategy;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LogbookAuditConfiguration}: the {@code requestCondition} gate (skip-path patterns
 * plus content-type allow-list, with bodyless requests always passing) and the {@code logbookStrategy}
 * factory producing a {@link ContentAwareBodyStrategy}.
 */
class LogbookAuditConfigurationTest {

    private final LogbookAuditConfiguration config = new LogbookAuditConfiguration();

    private static HttpRequest request(String path, String contentType) {
        HttpRequest request = mock(HttpRequest.class);
        lenient().when(request.getPath()).thenReturn(path);
        lenient().when(request.getContentType()).thenReturn(contentType);
        return request;
    }

    // ---------------------------------------------------------------- requestCondition

    @Test
    void skipPathsAreNeverAudited() {
        Predicate<HttpRequest> condition =
                config.requestCondition(List.of("/camel/bulk/**"), List.of("application/json"));
        assertThat(condition.test(request("/camel/bulk/feed", "application/json"))).isFalse();
    }

    @Test
    void allowedContentTypeIsAudited() {
        Predicate<HttpRequest> condition =
                config.requestCondition(List.of(), List.of("application/json"));
        assertThat(condition.test(request("/camel/orders", "application/json"))).isTrue();
    }

    @Test
    void bodylessRequestAlwaysPassesTheContentGate() {
        Predicate<HttpRequest> condition =
                config.requestCondition(List.of(), List.of("application/json"));
        assertThat(condition.test(request("/camel/orders", null))).isTrue();
    }

    @Test
    void disallowedContentTypeIsNotAudited() {
        Predicate<HttpRequest> condition =
                config.requestCondition(List.of(), List.of("application/json"));
        assertThat(condition.test(request("/camel/orders", "text/html"))).isFalse();
    }

    @Test
    void emptyAllowListDisablesTheContentGate() {
        Predicate<HttpRequest> condition = config.requestCondition(List.of(), List.of());
        assertThat(condition.test(request("/camel/orders", "text/html"))).isTrue();
    }

    @Test
    void contentTypeMatchIsCaseInsensitive() {
        Predicate<HttpRequest> condition =
                config.requestCondition(List.of(), List.of("application/json"));
        assertThat(condition.test(request("/camel/orders", "APPLICATION/JSON; charset=UTF-8"))).isTrue();
    }

    // ---------------------------------------------------------------- logbookStrategy

    @Test
    void logbookStrategyProducesAContentAwareBodyStrategy() {
        Strategy strategy = config.logbookStrategy(List.of("application/json"), false, 500);
        assertThat(strategy).isInstanceOf(ContentAwareBodyStrategy.class);
    }
}
