package com.creed.simple.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Drops the downstream's {@code JSESSIONID} from every response {@code Set-Cookie}; all other
 * cookies are kept, in order.
 *
 * <p>The response is wrapped rather than edited in place: {@link ClientHttpResponse#getHeaders()}
 * is not guaranteed to be writable (some factories hand out read-only adapters over the native
 * response), so the filtered headers are a copy and everything else delegates.
 *
 * <p>One {@code Set-Cookie} value is one cookie (RFC 6265 §3: servers must not fold them), so the
 * value is never split on commas — {@code Expires} contains one. The cookie name is case-sensitive.
 */
@Slf4j
public class JsessionIdStrippingInterceptor implements ClientHttpRequestInterceptor {

    static final String JSESSIONID = "JSESSIONID";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        if (setCookies.stream().noneMatch(JsessionIdStrippingInterceptor::isJsessionId)) {
            return response;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        List<String> kept = setCookies.stream().filter(value -> !isJsessionId(value)).toList();
        if (kept.isEmpty()) {
            headers.remove(HttpHeaders.SET_COOKIE);
        } else {
            headers.put(HttpHeaders.SET_COOKIE, kept);
        }
        // Name only — the value is a live session id.
        log.debug("removed Set-Cookie {} from {} {}", JSESSIONID, request.getMethod(), request.getURI());
        return new HeaderOverridingResponse(response, HttpHeaders.readOnlyHttpHeaders(headers));
    }

    static boolean isJsessionId(String setCookie) {
        int eq = setCookie.indexOf('=');
        return eq > 0 && JSESSIONID.equals(setCookie.substring(0, eq).strip());
    }

    private record HeaderOverridingResponse(ClientHttpResponse delegate, HttpHeaders headers)
            implements ClientHttpResponse {

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
