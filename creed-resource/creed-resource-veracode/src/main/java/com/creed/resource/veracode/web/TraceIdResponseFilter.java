package com.creed.resource.veracode.web;

import java.io.IOException;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every response names its trace ({@code X-Trace-Id} by default): the id a user reports is the one to grep the console
 * for, every line of that request - the parallel Jira searches included - carries it.
 *
 * <p>Ordered right after Boot's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}), which starts the
 * request's span: before it there is no span to name, so the header would never be written. Set before the chain, as
 * a header added once the body is written is dropped - the exception handlers' error bodies keep it too. Blank
 * {@code veracode.trace.response-header} = no header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    private final Tracer tracer;
    private final String header;

    public TraceIdResponseFilter(Tracer tracer, @Value("${veracode.trace.response-header:X-Trace-Id}") String header) {
        this.tracer = tracer;
        this.header = header;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null && StringUtils.hasText(header)) {
            response.setHeader(header, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
