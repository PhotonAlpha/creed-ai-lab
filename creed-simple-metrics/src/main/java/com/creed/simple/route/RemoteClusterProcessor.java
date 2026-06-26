package com.creed.simple.route;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls a downstream resource-server <em>cluster</em> from inside a Camel route, using the
 * {@code @LoadBalanced} {@link RestClient} so Spring Cloud LoadBalancer picks the instance — replacing
 * Camel's {@code <loadBalance>} round-robin EIP.
 *
 * <p>The target is a {@code https://<service-id>/...} URL passed in the {@code clusterUrl} header (set
 * by the {@code fetch-catalog} / {@code fetch-order} routes); the response JSON is bound to a
 * {@link JsonNode} and set as the message body so it can be nested into the aggregate result.
 *
 * <p><b>Resilient mode.</b> When the {@code resilient} header is {@code true} (set by the fulfillment
 * fetch routes), downstream failures are <em>not</em> propagated as exceptions: instead the branch is
 * marked with {@code branchError=true} / {@code branchStatus=<code>} and its body is replaced with an
 * error map. This lets the aggregation strategy collect per-branch failures and the route return a
 * failure response body (requirement 5). Without the header the original throwing behaviour is kept, so
 * the existing {@code /catalog} / {@code /order} / {@code /aggregate} endpoints are unaffected.
 *
 * <p>Referenced from {@code camel-context.xml} as {@code <process ref="remoteClusterProcessor"/>}.
 */
@Component("remoteClusterProcessor")
public class RemoteClusterProcessor implements Processor {

    private final RestClient clusterRestClient;

    public RemoteClusterProcessor(RestClient clusterRestClient) {
        this.clusterRestClient = clusterRestClient;
    }

    @Override
    public void process(Exchange exchange) {
        String clusterUrl = exchange.getMessage().getHeader("clusterUrl", String.class);
        boolean resilient = exchange.getMessage().getHeader("resilient", false, Boolean.class);
        try {
            JsonNode body = clusterRestClient.get()
                    .uri(clusterUrl)
                    .retrieve()
                    .body(JsonNode.class);
            exchange.getMessage().setHeader("branchStatus", 200);
            exchange.getMessage().setBody(body);
        } catch (RestClientResponseException ex) {
            // Downstream answered with a 4xx/5xx status code.
            if (!resilient) {
                throw ex;
            }
            markError(exchange, ex.getStatusCode().value(), ex.getStatusText());
        } catch (ResourceAccessException ex) {
            // No status code (connection refused / timeout / TLS) — surface it as 503.
            if (!resilient) {
                throw ex;
            }
            markError(exchange, 503, ex.getMessage());
        }
    }

    private void markError(Exchange exchange, int status, String message) {
        String source = exchange.getMessage().getHeader("source", "unknown", String.class);
        exchange.getMessage().setHeader("branchError", true);
        exchange.getMessage().setHeader("branchStatus", status);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("source", source);
        error.put("status", status);
        error.put("message", message);
        exchange.getMessage().setBody(error);
    }
}
