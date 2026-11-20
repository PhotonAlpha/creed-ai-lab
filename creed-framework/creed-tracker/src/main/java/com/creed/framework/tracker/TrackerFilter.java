package com.creed.framework.tracker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Starts a journey at a start path (a new {@code sid} cookie, SameSite=Lax) and puts the request's sid on the MDC for
 * everything logged while it runs - Logbook's inbound filter included, which runs inside this one
 * ({@code LOWEST_PRECEDENCE}).
 *
 * <p>Async dispatches (an SSE answer finishing) are filtered too, so their lines keep the sid; they never start a
 * journey. A cookie value that does not look like a sid is ignored: it is printed into every log line, so client text
 * must not reach the log as is.
 *
 * <p>After the chain the response's headers are measured: Tomcat refuses headers over
 * {@code server.tomcat.max-http-response-header-size} (8KB) when it commits the response and answers 500 on its own - the
 * application, Logbook included, logs the status it set, never the 500. The WARN here names the request, its sid and
 * the largest headers.
 */
public class TrackerFilter extends OncePerRequestFilter {

    /** Inbound lines - {@code TrackingSink} writes Logbook's server side here too. */
    static final Logger INBOUND = LoggerFactory.getLogger("creed.tracker.http.inbound");

    private static final Pattern SID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    /** Status line and the headers Tomcat adds itself (Date, Content-Type, Transfer-Encoding...), roughly. */
    private static final int CONTAINER_OVERHEAD = 128;

    private final TrackerProperties properties;
    private final long headerWarnBytes;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final UrlPathHelper paths = new UrlPathHelper();

    public TrackerFilter(TrackerProperties properties, DataSize serverMaxResponseHeaderSize) {
        this.properties = properties;
        DataSize warn = properties.getResponseHeaderWarnSize() != null
                ? properties.getResponseHeaderWarnSize() : serverMaxResponseHeaderSize;
        this.headerWarnBytes = warn == null ? 8192 : warn.toBytes();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sid = incomingSid(request);
        if (!isAsyncDispatch(request) && isStart(request)) {
            String previous = sid;
            sid = UUID.randomUUID().toString();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(sid, request).toString());
            INBOUND.info("sid={} journey started at {} {} (previous sid={})", sid, request.getMethod(),
                    request.getRequestURI(), previous == null ? TrackerContext.NONE : previous);
        }
        if (sid != null && StringUtils.hasText(properties.getResponseHeader()) && !isAsyncDispatch(request)) {
            response.setHeader(properties.getResponseHeader(), sid);
        }

        String outer = TrackerContext.current();
        TrackerContext.set(sid);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.isAsyncStarted()) {
                checkHeaderSize(request, response, sid);
            }
            TrackerContext.set(outer);
        }
    }

    boolean isStart(HttpServletRequest request) {
        String path = paths.getPathWithinApplication(request);
        return properties.getStartPaths().stream().anyMatch(p -> matcher.match(p, path));
    }

    private String incomingSid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (properties.getCookie().getName().equals(c.getName()) && SID.matcher(c.getValue()).matches()) {
                return c.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String sid, HttpServletRequest request) {
        TrackerProperties.Cookie c = properties.getCookie();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(c.getName(), sid)
                .path(c.getPath())
                .httpOnly(c.isHttpOnly())
                .secure(c.getSecure() != null ? c.getSecure() : request.isSecure())
                .sameSite(c.getSameSite());
        if (c.getMaxAge() != null) {
            builder.maxAge(c.getMaxAge());
        }
        return builder.build();
    }

    void checkHeaderSize(HttpServletRequest request, HttpServletResponse response, String sid) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        long total = CONTAINER_OVERHEAD;
        for (String name : response.getHeaderNames()) {
            int size = 0;
            for (String value : response.getHeaders(name)) {
                // "Name: value\r\n"
                size += name.length() + 4 + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
            }
            sizes.merge(name, size, Integer::sum);
            total += size;
        }
        if (total > headerWarnBytes) {
            String largest = sizes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            INBOUND.warn("sid={} {} {} response headers ~{} bytes exceed {} bytes (server.tomcat.max-http-response-header-size):"
                            + " the container answers 500 instead of status {}; largest headers: {}",
                    sid == null ? TrackerContext.NONE : sid, request.getMethod(), request.getRequestURI(), total,
                    headerWarnBytes, response.getStatus(), largest);
        }
    }
}
