package com.creed.framework.tracker;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerFilterTest {

    private static final String SID = "0b8f3c1e-6a43-4d6f-9b38-6f1f0c3e2a11";

    private final TrackerProperties properties = new TrackerProperties();

    TrackerFilterTest() {
        properties.setStartPaths(List.of("/api/veracode/jira/connection"));
    }

    private TrackerFilter filter() {
        return new TrackerFilter(properties, DataSize.ofKilobytes(8));
    }

    @Test
    void aStartPathIssuesANewLaxSidAndLogsUnderIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/veracode/jira/connection");
        request.setCookies(new Cookie("sid", SID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter().doFilter(request, response, (req, res) -> seen.set(TrackerContext.current()));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("sid=").contains("Path=/", "HttpOnly", "SameSite=Lax").doesNotContain("Secure");
        String issued = setCookie.substring(4, setCookie.indexOf(';'));
        assertThat(issued).isNotEqualTo(SID).hasSize(36);
        assertThat(seen.get()).isEqualTo(issued);
        assertThat(response.getHeader("X-Session-Id")).isEqualTo(issued);
        assertThat(TrackerContext.current()).isNull();
    }

    @Test
    void laterRequestsKeepTheCookiesSidAndSetNoCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/veracode/analysis");
        request.setCookies(new Cookie("sid", SID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter().doFilter(request, response, (req, res) -> seen.set(TrackerContext.current()));

        assertThat(seen.get()).isEqualTo(SID);
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getHeader("X-Session-Id")).isEqualTo(SID);
    }

    @Test
    void aCookieThatIsNoSidNeverReachesTheLog() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/veracode/settings");
        request.setCookies(new Cookie("sid", "x\nFAKE LOG LINE"));
        AtomicReference<String> seen = new AtomicReference<>("unset");

        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(TrackerContext.current()));

        assertThat(seen.get()).isNull();
    }

    @Test
    void theStartPathIsMatchedWithoutTheContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ctx/api/veracode/jira/connection");
        request.setContextPath("/ctx");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Set-Cookie")).startsWith("sid=");
    }

    @Test
    void secureFollowsTheRequestUnlessConfigured() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/veracode/jira/connection");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("Set-Cookie")).contains("Secure");

        properties.getCookie().setSecure(false);
        MockHttpServletResponse plain = new MockHttpServletResponse();
        filter().doFilter(request, plain, new MockFilterChain());
        assertThat(plain.getHeader("Set-Cookie")).doesNotContain("Secure");
    }
}
