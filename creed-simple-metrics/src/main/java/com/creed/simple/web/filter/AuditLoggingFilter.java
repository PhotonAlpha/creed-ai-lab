package com.creed.simple.web.filter;

import com.creed.simple.web.config.AuditLoggingFilterConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One audit line-block per HTTP exchange: request/response method, status, latency, headers, cookies
 * and (textual) body — emitted as a single multi-line {@code log.info} so the block stays contiguous
 * even under concurrent traffic.
 *
 * <h2>Why {@link ContentCachingRequestWrapper} / {@link ContentCachingResponseWrapper}</h2>
 * A servlet request/response body is a <em>one-shot stream</em>: whoever reads it first consumes it.
 * If this filter read the raw body, Camel's servlet downstream would get an empty stream (and vice
 * versa for the response). The caching wrappers tee the bytes as they flow, so the body can be read
 * for logging <em>without</em> stealing it. Two non-negotiable rules that this filter follows:
 * <ul>
 *   <li><b>Request:</b> {@link ContentCachingRequestWrapper#getContentAsByteArray()} is only populated
 *       <em>after</em> the downstream has actually read the stream. So the body is read
 *       <em>post-chain</em>, in the {@code finally} block — never before {@code doFilter}. If a handler
 *       never reads the body (e.g. a 404, or a bodyless GET) the cache is legitimately empty.</li>
 *   <li><b>Response:</b> {@link ContentCachingResponseWrapper} buffers the whole response; nothing
 *       reaches the client until {@link ContentCachingResponseWrapper#copyBodyToResponse()} is called.
 *       That call therefore runs in {@code finally}, and audit logging is wrapped so a logging failure
 *       can never swallow the response body.</li>
 * </ul>
 *
 * <p>Registered (scoped to {@code /camel/*}, not annotated {@code @Component}) by
 * {@link AuditLoggingFilterConfiguration} so it does not also wrap {@code /actuator}.
 */
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditLoggingFilter.class);

    /** Bodies larger than this (in bytes) are truncated in the log — protects against large feeds. */
    private static final int MAX_PAYLOAD_BYTES = 4_096;

    /** Header values never printed verbatim (credentials). Cookies ARE printed on purpose. */
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "proxy-authorization");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper req = request instanceof ContentCachingRequestWrapper cached
                ? cached : new ContentCachingRequestWrapper(request, MAX_PAYLOAD_BYTES);
        ContentCachingResponseWrapper resp = response instanceof ContentCachingResponseWrapper cached
                ? cached : new ContentCachingResponseWrapper(response);

        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(req, resp);
        } finally {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            try {
                log.info("AuditLoggingFilter \n{}", render(req, resp, ms));
            } catch (RuntimeException ex) {
                // Auditing must never break the response — log and carry on to copyBodyToResponse().
                log.warn("audit rendering failed: {}", ex.toString());
            } finally {
                // MUST run last: flushes the buffered body to the real client response.
                resp.copyBodyToResponse();
            }
        }
    }

    private String render(ContentCachingRequestWrapper req, ContentCachingResponseWrapper resp, long ms) {
        String query = StringUtils.hasText(req.getQueryString()) ? "?" + req.getQueryString() : "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("========== AUDIT ").append(req.getMethod()).append(' ')
                .append(req.getRequestURI()).append(query).append(" ==========\n");

        sb.append("--- REQUEST ---\n");
        appendRequestHeaders(sb, req);
        appendRequestCookies(sb, req);
        appendBody(sb, req.getContentAsByteArray(), req.getContentType());

        sb.append("--- RESPONSE (status=").append(resp.getStatus()).append(", ").append(ms).append("ms) ---\n");
        appendResponseHeaders(sb, resp);
        appendResponseCookies(sb, resp);
        appendBody(sb, resp.getContentAsByteArray(), resp.getContentType());

        sb.append("==========================================================");
        return sb.toString();
    }

    private void appendRequestHeaders(StringBuilder sb, HttpServletRequest req) {
        sb.append("  headers:\n");
        Collections.list(req.getHeaderNames()).forEach(name ->
                Collections.list(req.getHeaders(name)).forEach(value ->
                        sb.append("    ").append(name).append(": ").append(mask(name, value)).append('\n')));
    }

    private void appendResponseHeaders(StringBuilder sb, HttpServletResponse resp) {
        sb.append("  headers:\n");
        for (String name : resp.getHeaderNames()) {
            // Set-Cookie is rendered separately below; skip it here to avoid duplication.
            if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                continue;
            }
            for (String value : resp.getHeaders(name)) {
                sb.append("    ").append(name).append(": ").append(mask(name, value)).append('\n');
            }
        }
    }

    private void appendRequestCookies(StringBuilder sb, HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null || cookies.length == 0) {
            return;
        }
        sb.append("  cookies:\n");
        for (Cookie c : cookies) {
            sb.append("    ").append(c.getName()).append('=').append(c.getValue()).append('\n');
        }
    }

    private void appendResponseCookies(StringBuilder sb, HttpServletResponse resp) {
        List<String> setCookies = resp.getHeaders(HttpHeaders.SET_COOKIE).stream().toList();
        if (setCookies.isEmpty()) {
            return;
        }
        sb.append("  set-cookies:\n");
        setCookies.forEach(value -> sb.append("    ").append(value).append('\n'));
    }

    /** Prints the body only when it is present and looks textual; truncates to {@link #MAX_PAYLOAD_BYTES}. */
    private void appendBody(StringBuilder sb, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            return;
        }
        if (!isTextual(contentType)) {
            sb.append("  body   : <").append(content.length).append(" bytes, ")
                    .append(contentType == null ? "unknown" : contentType).append(" — not logged>\n");
            return;
        }
        int shown = Math.min(content.length, MAX_PAYLOAD_BYTES);
        String text = new String(content, 0, shown, charsetOf(contentType));
        sb.append("  body   : ").append(text);
        if (content.length > shown) {
            sb.append(" …(truncated, ").append(content.length).append(" bytes total)");
        }
        sb.append('\n');
    }

    private static String mask(String headerName, String value) {
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT)) ? "****" : value;
    }

    private static boolean isTextual(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            String subtype = type.getSubtype().toLowerCase(Locale.ROOT);
            return "text".equalsIgnoreCase(type.getType())
                    || "json".equals(subtype) || subtype.endsWith("+json")
                    || "xml".equals(subtype) || subtype.endsWith("+xml")
                    || "x-www-form-urlencoded".equals(subtype);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Charset charsetOf(String contentType) {
        try {
            Charset cs = MediaType.parseMediaType(contentType).getCharset();
            return cs != null ? cs : StandardCharsets.UTF_8;
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }
}
