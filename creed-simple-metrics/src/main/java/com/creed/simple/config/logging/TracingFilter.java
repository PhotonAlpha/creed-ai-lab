package com.creed.simple.config.logging;

import brave.baggage.BaggageFields;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static com.creed.simple.config.logging.MyMDCScopeDecorator.CORRELATION_FIELD;


@Component
@Order(1)
@Slf4j
public class TracingFilter extends OncePerRequestFilter {

    @Resource
    Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String correlateId = UUID.randomUUID().toString();
        var tracedId = tracer.currentSpan().context().traceId();
        CORRELATION_FIELD.updateValue(correlateId);
        MDC.put(BaggageFields.TRACE_ID.name(), correlateId);
        // MDC.put(BRAVE_TRACE_ID, tracedId);
        response.addHeader(BaggageFields.TRACE_ID.name(), correlateId);
        log.info("correlateId:[{}] traceId: [{}], uri: [{}]", correlateId, tracedId, request.getRequestURI());
        filterChain.doFilter(request, response);
    }


}
