package com.creed.simple.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creed.simple.web.filter.AuditLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditLoggingFilter}: the response body always reaches the client
 * ({@code copyBodyToResponse} runs in {@code finally}), the request body is captured post-chain, sensitive
 * headers are masked while cookies are printed, non-textual bodies are summarised not dumped, and an
 * already-wrapped request is reused rather than double-wrapped.
 */
class AuditLoggingFilterTest {

    private final AuditLoggingFilter filter = new AuditLoggingFilter();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(AuditLoggingFilter.class)).detachAppender(appender);
    }

    private String auditLine() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void responseBodyReachesTheClientAfterAuditing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/camel/orders");
        request.setContentType("application/json");
        request.setContent("{\"in\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            ((HttpServletRequest) req).getInputStream().readAllBytes(); // downstream consumes the body
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(200);
            r.setContentType("application/json");
            r.getWriter().write("{\"ok\":true}");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.getStatus()).isEqualTo(200);
        // Both the consumed request body and the response body made it into the audit block.
        assertThat(auditLine()).contains("{\"in\":1}").contains("{\"ok\":true}");
    }

    @Test
    void masksSensitiveHeadersButPrintsCookies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/camel/x");
        request.addHeader("Authorization", "Bearer super-secret-token");
        request.setCookies(new Cookie("session", "abc123"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(204));

        String audit = auditLine();
        assertThat(audit).doesNotContain("super-secret-token").contains("****");
        assertThat(audit).contains("session=abc123");
    }

    @Test
    void nonTextualBodyIsSummarisedNotDumped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/camel/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(200);
            r.setContentType("application/octet-stream");
            r.getOutputStream().write(new byte[]{1, 2, 3, 4});
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3, 4);
        assertThat(auditLine()).contains("not logged");
    }

    @Test
    void reusesAnAlreadyWrappedRequestInsteadOfDoubleWrapping() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/camel/x");
        // Spring Framework 7 dropped the single-argument constructor; a cache limit is now
        // mandatory. The value is irrelevant here — the assertion is that the filter reuses an
        // already-wrapped request instead of wrapping it twice.
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(raw, 8192);
        MockHttpServletResponse response = new MockHttpServletResponse();

        HttpServletRequest[] seen = new HttpServletRequest[1];
        filter.doFilter(wrapped, response, (req, res) -> seen[0] = (HttpServletRequest) req);

        assertThat(seen[0]).isSameAs(wrapped);
    }
}
