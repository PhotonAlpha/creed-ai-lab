package com.creed.simple.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strips RFC 6265-invalid cookies out of the exchange <em>before</em> business code sees them, so a
 * malformed inbound {@code Cookie:} pair (or a bad {@code Set-Cookie} written downstream) cannot leak
 * into application logic or back to the client.
 *
 * <h2>Why a separate filter (not {@link AuditLoggingFilter})</h2>
 * The audit filter is deliberately read-only and registered outermost, so it records what actually
 * arrived. This filter <em>mutates</em> the request/response and is registered just inside it — the
 * audit log therefore still captures the raw (possibly hostile) cookies, while the downstream Camel
 * routes only ever receive the sanitized view.
 *
 * <h2>The validation gate</h2>
 * Spring's {@code Rfc6265Utils} (name/value/domain/path validators) is package-private to
 * {@code org.springframework.http}. Building a {@link ResponseCookie} runs those exact four checks in
 * its constructor, so {@link #isRfc6265Valid} uses the public builder as the gate — invalid input
 * throws {@link IllegalArgumentException}. Inbound {@code Cookie:} pairs carry no Domain/Path, so only
 * name and value are checked on the request side; all four apply to response {@code Set-Cookie}.
 */
public class CookieSanitizingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CookieSanitizingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(sanitizeRequest(request), new SanitizingResponseWrapper(response));
    }

    /** Drops invalid inbound cookies; only wraps the request when something was actually removed. */
    private HttpServletRequest sanitizeRequest(HttpServletRequest request) {
        Cookie[] original = request.getCookies();
        if (original == null || original.length == 0) {
            return request;
        }
        List<Cookie> valid = new ArrayList<>(original.length);
        for (Cookie c : original) {
            if (isRfc6265Valid(c.getName(), c.getValue(), null, null)) {
                valid.add(c);
            } else {
                log.warn("dropping invalid inbound cookie '{}' (RFC 6265)", c.getName());
            }
        }
        return valid.size() == original.length ? request : new SanitizedCookieRequest(request, valid);
    }

    /**
     * Request view that exposes only the surviving cookies — both via {@link #getCookies()} and via a
     * rebuilt {@code Cookie} header, so code that re-parses the raw header sees the same clean set.
     */
    private static final class SanitizedCookieRequest extends HttpServletRequestWrapper {

        private final Cookie[] cookies;
        private final String cookieHeader; // null when every cookie was stripped

        SanitizedCookieRequest(HttpServletRequest request, List<Cookie> valid) {
            super(request);
            this.cookies = valid.toArray(new Cookie[0]);
            this.cookieHeader = valid.isEmpty() ? null : valid.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
        }

        @Override
        public Cookie[] getCookies() {
            return cookies.length == 0 ? null : cookies.clone();
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.COOKIE.equalsIgnoreCase(name) ? cookieHeader : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.COOKIE.equalsIgnoreCase(name)) {
                return cookieHeader == null ? Collections.emptyEnumeration()
                        : Collections.enumeration(List.of(cookieHeader));
            }
            return super.getHeaders(name);
        }
    }

    /**
     * Response view that validates cookies at write time — {@code addCookie} plus the raw
     * {@code addHeader}/{@code setHeader} paths — and silently drops any that fail RFC 6265, so an
     * invalid {@code Set-Cookie} never reaches the client.
     */
    private static final class SanitizingResponseWrapper extends HttpServletResponseWrapper {

        SanitizingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void addCookie(Cookie cookie) {
            if (isRfc6265Valid(cookie.getName(), cookie.getValue(), cookie.getDomain(), cookie.getPath())) {
                super.addCookie(cookie);
            } else {
                log.warn("dropping invalid Set-Cookie '{}' (RFC 6265)", cookie.getName());
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (isSetCookie(name) && !isValidSetCookie(value)) {
                log.warn("dropping invalid Set-Cookie header (RFC 6265)");
                return;
            }
            super.addHeader(name, value);
        }

        @Override
        public void setHeader(String name, String value) {
            if (isSetCookie(name) && !isValidSetCookie(value)) {
                log.warn("dropping invalid Set-Cookie header (RFC 6265)");
                return;
            }
            super.setHeader(name, value);
        }

        private static boolean isSetCookie(String name) {
            return HttpHeaders.SET_COOKIE.equalsIgnoreCase(name);
        }
    }

    /**
     * Parses a raw {@code Set-Cookie} header into name=value plus {@code Domain}/{@code Path} and runs
     * it through {@link #isRfc6265Valid}. Non char-set attributes (Max-Age, Secure, HttpOnly, SameSite)
     * are not part of RFC 6265 name/value/domain/path validation and are ignored.
     */
    private static boolean isValidSetCookie(String setCookie) {
        if (!StringUtils.hasText(setCookie)) {
            return false;
        }
        String name = null;
        String value = null;
        String domain = null;
        String path = null;
        boolean first = true;
        for (String part : setCookie.split(";")) {
            String token = part.trim();
            if (first) {
                first = false;
                int eq = token.indexOf('=');
                name = eq >= 0 ? token.substring(0, eq) : token;
                value = eq >= 0 ? token.substring(eq + 1) : null;
            } else if (token.regionMatches(true, 0, "Domain=", 0, 7)) {
                domain = token.substring(7);
            } else if (token.regionMatches(true, 0, "Path=", 0, 5)) {
                path = token.substring(5);
            }
        }
        return isRfc6265Valid(name, value, domain, path);
    }

    /**
     * RFC 6265 validity gate: building a {@link ResponseCookie} runs {@code Rfc6265Utils}'
     * validateCookieName/Value/Domain/Path in its constructor. Invalid input throws
     * {@link IllegalArgumentException}.
     */
    private static boolean isRfc6265Valid(String name, String value, String domain, String path) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        try {
            ResponseCookie.from(name, value).domain(domain).path(path).build();
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
