package com.creed.partner.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Requirement 3: audit every load-balanced downstream call, logging the request body, headers and
 * cookies and the response body, headers and cookies — plus a summary line with the resolved target
 * (host/port/full URL), the call duration and the response status code.
 *
 * <p>Registered <em>after</em> the {@code @LoadBalanced} load-balancer interceptor (so it runs
 * innermost), it observes the request <strong>after</strong> the service ID has been resolved to a
 * concrete instance — hence {@code request.getURI()} here is e.g. {@code https://localhost:8081/...},
 * the real target, not the logical {@code https://catalog-resource/...}. The client's request factory
 * is buffered so the response body can be read here and still consumed by the caller.
 */
@Slf4j
@Component
public class LoadBalancerAuditInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        URI uri = request.getURI();
        HttpHeaders requestHeaders = request.getHeaders();
        log.info("[LB-AUDIT][request] {} {} (host={} port={})",
                request.getMethod(), uri, uri.getHost(), uri.getPort());
        log.info("[LB-AUDIT][request headers] {}", requestHeaders);
        log.info("[LB-AUDIT][request cookies] {}", cookies(requestHeaders.get(HttpHeaders.COOKIE)));
        log.info("[LB-AUDIT][request body] {}", body.length == 0 ? "<empty>" : new String(body, StandardCharsets.UTF_8));

        long startNanos = System.nanoTime();
        ClientHttpResponse response = execution.execute(request, body);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        HttpStatusCode status = response.getStatusCode();
        HttpHeaders responseHeaders = response.getHeaders();
        String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        log.info("[LB-AUDIT][response headers] {}", responseHeaders);
        log.info("[LB-AUDIT][response cookies] {}", cookies(responseHeaders.get(HttpHeaders.SET_COOKIE)));
        log.info("[LB-AUDIT][response body] {}", responseBody.isEmpty() ? "<empty>" : responseBody);
        // Summary: real target + status + elapsed time.
        log.info("[LB-AUDIT][summary] {} {} -> {} in {} ms", request.getMethod(), uri, status, elapsedMillis);
        return response;
    }

    private static Object cookies(List<String> cookieHeaders) {
        return (cookieHeaders == null || cookieHeaders.isEmpty()) ? "<none>" : cookieHeaders;
    }
}
