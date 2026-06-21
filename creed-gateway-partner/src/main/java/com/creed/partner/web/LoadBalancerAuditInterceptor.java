package com.creed.partner.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Requirement 3: audit every load-balanced downstream call, logging the request body, headers and
 * cookies and the response body, headers and cookies.
 *
 * <p>Registered on the {@code @LoadBalanced} {@link org.springframework.web.client.RestClient}, so it
 * runs for every {@code lb://} call the aggregator makes. The client's request factory is wrapped in a
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory} so the response body can be
 * read here for the audit and still be consumed by the caller.
 */
@Slf4j
@Component
public class LoadBalancerAuditInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpHeaders requestHeaders = request.getHeaders();
        log.info("[LB-AUDIT][request] {} {}", request.getMethod(), request.getURI());
        log.info("[LB-AUDIT][request headers] {}", requestHeaders);
        log.info("[LB-AUDIT][request cookies] {}", cookies(requestHeaders.get(HttpHeaders.COOKIE)));
        log.info("[LB-AUDIT][request body] {}", body.length == 0 ? "<empty>" : new String(body, StandardCharsets.UTF_8));

        ClientHttpResponse response = execution.execute(request, body);

        HttpHeaders responseHeaders = response.getHeaders();
        String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        log.info("[LB-AUDIT][response] {} {}", response.getStatusCode(), request.getURI());
        log.info("[LB-AUDIT][response headers] {}", responseHeaders);
        log.info("[LB-AUDIT][response cookies] {}", cookies(responseHeaders.get(HttpHeaders.SET_COOKIE)));
        log.info("[LB-AUDIT][response body] {}", responseBody.isEmpty() ? "<empty>" : responseBody);
        return response;
    }

    private static Object cookies(List<String> cookieHeaders) {
        return (cookieHeaders == null || cookieHeaders.isEmpty()) ? "<none>" : cookieHeaders;
    }
}
