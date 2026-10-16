package com.creed.simple.route;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One downstream POST, two clients — the {@code POST /api/reserve/*} pair in {@code camel-context.xml}.
 *
 * <p>Both endpoints send the same {@code {"sku","quantity"}} to {@code catalog-resource}'s
 * {@code POST /api/catalog/reserve} and answer the same envelope, so the two can be compared call for
 * call:
 * <ul>
 *   <li>{@code /api/reserve/http-component} — a Camel {@code <to uri="https://catalog-resource/...">}
 *       served by {@code CamelConfig}'s {@code "https"} {@code HttpComponent}: the service id is
 *       resolved at connect time by {@code LoadBalancerRoutePlanner}, and the call is audited by that
 *       component's exec chain (Logbook → Observation → {@code CamelLoadBalancerAuditExecHandler});</li>
 *   <li>{@code /api/reserve/rest-client} — Java code calling the {@code @LoadBalanced}
 *       {@code clusterRestClient}: the service id is resolved by Spring Cloud's
 *       {@code LoadBalancerInterceptor} before the request leaves, which also runs the
 *       {@code LoadBalancerLifecycle} callbacks and is logged by {@code LoadBalancerAuditInterceptor}.</li>
 * </ul>
 * Same mTLS pool underneath, same downstream answer; what differs is where the service id becomes an
 * instance, which logs and metrics the call produces, and how a 4xx/5xx surfaces
 * ({@link HttpOperationFailedException} vs {@link RestClientResponseException}) — both mapped to the
 * same envelope by {@link #failure}.
 */
@Slf4j
@Component("reserveComparison")
public class ReserveComparisonProcessor {

    static final String DOWNSTREAM = "https://catalog-resource/api/catalog/reserve";
    static final String CLIENT_HTTP_COMPONENT = "camel-http (\"https\" HttpComponent)";
    static final String CLIENT_REST_CLIENT = "RestClient (clusterRestClient, @LoadBalanced)";

    private static final String PROP_CLIENT = "reserveClient";
    private static final String PROP_STARTED = "reserveStartedNanos";

    private final RestClient clusterRestClient;
    private final ObjectMapper mapper;

    public ReserveComparisonProcessor(@Qualifier("clusterRestClient") RestClient clusterRestClient,
                                      ObjectMapper mapper) {
        this.clusterRestClient = clusterRestClient;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ "https" HttpComponent route

    /** Before the {@code <to uri="https://...">}: validated JSON string body; the route sets method and Content-Type. */
    public void prepareHttpComponent(Exchange exchange) throws Exception {
        Map<String, Object> payload = payload(exchange, CLIENT_HTTP_COMPONENT);
        exchange.getMessage().setBody(mapper.writeValueAsString(payload));
    }

    /** After it: the reply is the raw downstream body (skipResponseHeaders=true), wrapped in the envelope. */
    public void finishHttpComponent(Exchange exchange) throws Exception {
        Object body = parse(exchange.getMessage().getBody(String.class));
        exchange.getMessage().setBody(envelope(exchange, 200, body));
    }

    // ------------------------------------------------------------------ clusterRestClient route

    /** The whole call in Java: the RestClient serialises the Map itself and hands back status + body. */
    public void viaRestClient(Exchange exchange) {
        Map<String, Object> payload = payload(exchange, CLIENT_REST_CLIENT);
        ResponseEntity<String> response = clusterRestClient.post()
                .uri(DOWNSTREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String.class);
        exchange.getMessage().setBody(envelope(exchange, response.getStatusCode().value(), parse(response.getBody())));
    }

    // ------------------------------------------------------------------ shared

    /**
     * doCatch of both routes. A downstream 4xx is passed through (409 = not enough stock), a 5xx or
     * no answer at all becomes 502, a bad request body is 400 before any call is made.
     */
    public void failure(Exchange exchange) {
        Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        int downstreamStatus;
        Object downstreamBody;
        int responseCode;
        if (ex instanceof HttpOperationFailedException http) {
            downstreamStatus = http.getStatusCode();
            downstreamBody = parse(http.getResponseBody());
            responseCode = downstreamStatus >= 500 ? 502 : downstreamStatus;
        } else if (ex instanceof RestClientResponseException rest) {
            downstreamStatus = rest.getStatusCode().value();
            downstreamBody = parse(rest.getResponseBodyAsString());
            responseCode = downstreamStatus >= 500 ? 502 : downstreamStatus;
        } else if (ex instanceof IllegalArgumentException) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.getMessage().setBody(Map.of("client", clientOf(exchange), "status", 400, "error", ex.getMessage()));
            return;
        } else {
            downstreamStatus = 0;
            downstreamBody = String.valueOf(ex);
            responseCode = 502;
        }
        log.warn("reserve via {} failed: downstream status={} -> {}", clientOf(exchange), downstreamStatus, responseCode);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, responseCode);
        exchange.getMessage().setBody(envelope(exchange, downstreamStatus, downstreamBody));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Exchange exchange, String client) {
        exchange.setProperty(PROP_CLIENT, client);
        Map<String, Object> body = exchange.getMessage().getBody(Map.class);
        if (body == null || !(body.get("sku") instanceof String sku) || sku.isBlank()) {
            throw new IllegalArgumentException("reserve requires a non-blank 'sku'");
        }
        int quantity = body.get("quantity") instanceof Number n ? n.intValue() : 1;
        if (quantity <= 0) {
            throw new IllegalArgumentException("'quantity' must be positive");
        }
        exchange.setProperty(PROP_STARTED, System.nanoTime());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", sku);
        payload.put("quantity", quantity);
        return payload;
    }

    /** Identical for both clients, so two responses differ only where the clients really differ. */
    private static Map<String, Object> envelope(Exchange exchange, int downstreamStatus, Object downstreamBody) {
        Long started = exchange.getProperty(PROP_STARTED, Long.class);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("client", clientOf(exchange));
        envelope.put("downstream", "POST " + DOWNSTREAM);
        envelope.put("status", downstreamStatus);
        envelope.put("elapsedMs", started == null ? null : (System.nanoTime() - started) / 1_000_000);
        envelope.put("body", downstreamBody);
        return envelope;
    }

    private static String clientOf(Exchange exchange) {
        return exchange.getProperty(PROP_CLIENT, "unknown", String.class);
    }

    /** Downstream bodies are JSON in practice but not guaranteed (a proxy error page) — fall back to the text. */
    private Object parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, Object.class);
        } catch (Exception notJson) {
            return body;
        }
    }
}
