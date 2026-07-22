package com.creed.simple.web;

import com.creed.simple.web.filter.CookieSanitizingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link CookieSanitizingFilter} end-to-end via Spring's servlet mocks, asserting that
 * RFC 6265-invalid cookies are stripped before the downstream chain (request side) and before the
 * client (response side), while valid cookies pass through untouched.
 */
class CookieSanitizingFilterTest {

    private final CookieSanitizingFilter filter = new CookieSanitizingFilter();

    /** Runs the filter over a fresh cookieless request and returns the response the client would get. */
    private MockHttpServletResponse runResponse(FilterChain business) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/camel/x"), response, business);
        return response;
    }

    // ---------------------------------------------------------------- request side

    @Test
    void stripsInvalidInboundCookiesFromGetCookies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/camel/orders");
        request.setCookies(
                new Cookie("session", "abc123"),                // valid
                new Cookie("evilSpace", "a b"),                 // invalid value: 0x20 space
                new Cookie("evilCrlf", "x\r\nSet-Cookie: h"));  // invalid value: CRLF injection

        List<String> seenNames = new ArrayList<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            Cookie[] cookies = ((HttpServletRequest) req).getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    seenNames.add(c.getName());
                }
            }
        });

        assertThat(seenNames).containsExactly("session");
    }

    @Test
    void rebuildsCookieHeaderToValidPairsOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/camel/orders");
        request.setCookies(new Cookie("session", "abc123"), new Cookie("evilSpace", "a b"));
        request.addHeader("Cookie", "session=abc123; evilSpace=a b");

        String[] seenHeader = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenHeader[0] = ((HttpServletRequest) req).getHeader("Cookie"));

        assertThat(seenHeader[0]).isEqualTo("session=abc123");
    }

    @Test
    void doesNotWrapRequestWhenAllCookiesValid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/camel/x");
        request.setCookies(new Cookie("a", "1"), new Cookie("b", "2"));

        HttpServletRequest[] downstream = new HttpServletRequest[1];
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> downstream[0] = (HttpServletRequest) req);

        // Nothing was stripped, so the original request instance is passed through unwrapped.
        assertThat(downstream[0]).isSameAs(request);
    }

    // ---------------------------------------------------------------- response side

    @Test
    void addCookieDropsInvalidKeepsValid() throws Exception {
        MockHttpServletResponse response = runResponse((req, res) -> {
            HttpServletResponse h = (HttpServletResponse) res;
            h.addCookie(new Cookie("auth", "tok"));   // valid
            h.addCookie(new Cookie("bad", "v w"));    // invalid value: space
        });

        assertThat(stream(response.getCookies()).map(Cookie::getName)).containsExactly("auth");
    }

    @Test
    void addHeaderDropsInvalidSetCookieKeepsValid() throws Exception {
        MockHttpServletResponse response = runResponse((req, res) -> {
            HttpServletResponse h = (HttpServletResponse) res;
            h.addHeader("Set-Cookie", "lang=en; Path=/");        // valid
            h.addHeader("Set-Cookie", "evil=1; Domain=-nope-");  // invalid Domain (leading '-')
            h.addHeader("Set-Cookie", "inj=a\r\nb");             // invalid value (CRLF)
        });

        assertThat(response.getHeaders("Set-Cookie")).containsExactly("lang=en; Path=/");
    }

    @Test
    void setHeaderKeepsValidSetCookie() throws Exception {
        MockHttpServletResponse response = runResponse((req, res) ->
                ((HttpServletResponse) res).setHeader("Set-Cookie", "over=ok; Path=/app"));

        assertThat(response.getHeaders("Set-Cookie")).contains("over=ok; Path=/app");
    }

    @Test
    void setHeaderDropsInvalidSetCookie() throws Exception {
        MockHttpServletResponse response = runResponse((req, res) ->
                ((HttpServletResponse) res).setHeader("Set-Cookie", "evil=1; Domain=-nope-"));

        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void leavesNonCookieHeadersUntouched() throws Exception {
        MockHttpServletResponse response = runResponse((req, res) -> {
            HttpServletResponse h = (HttpServletResponse) res;
            h.addHeader("X-Trace-Id", "abc");
            h.setHeader("Content-Language", "en");
        });

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("abc");
        assertThat(response.getHeader("Content-Language")).isEqualTo("en");
    }
}
