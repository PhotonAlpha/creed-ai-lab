package com.creed.simple.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Logs the <em>concrete instance</em> Spring Cloud LoadBalancer picked for each downstream call.
 *
 * <p>It is added to the {@code @LoadBalanced} RestClient <strong>after</strong> the load-balancer
 * interceptor (see {@link LoadBalancedRestClientConfiguration}), so it runs innermost: by the time it
 * executes, the LB has already rewritten {@code https://<service-id>/...} to the chosen instance's
 * {@code host:port}, which is what {@link HttpRequest#getURI()} reports here.
 */
@Component
public class LoadBalancerAuditInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerAuditInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        URI uri = request.getURI();
        long startNanos = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("LB resolved -> instance={}:{} {} {} status={} in {}ms",
                    uri.getHost(), uri.getPort(), request.getMethod(), uri.getPath(),
                    response.getStatusCode().value(), ms);
            return response;
        } catch (IOException | RuntimeException ex) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("LB resolved -> instance={}:{} {} {} FAILED in {}ms: {}",
                    uri.getHost(), uri.getPort(), request.getMethod(), uri.getPath(), ms, ex.toString());
            throw ex;
        }
    }
}
