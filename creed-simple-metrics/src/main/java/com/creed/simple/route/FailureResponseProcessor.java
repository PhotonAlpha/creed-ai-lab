package com.creed.simple.route;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-5 of {@code /api/fulfillment}: when any downstream branch failed, builds the failure response.
 *
 * <p>Reads the worst downstream status captured by {@link FulfillmentAggregateStrategy}
 * ({@code downstreamStatus} header) and the collected {@code errors} list from the aggregate body, then
 * replaces the body with a failure envelope and sets {@code Exchange.HTTP_RESPONSE_CODE} so the REST DSL
 * returns that status (defaulting to 502 Bad Gateway since the failure is in an upstream dependency).
 *
 * <p>Referenced from {@code camel-context.xml} as {@code <process ref="failureResponseProcessor"/>}.
 */
@Component("failureResponseProcessor")
public class FailureResponseProcessor implements Processor {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> aggregate = exchange.getMessage().getBody(Map.class);
        int downstreamStatus = exchange.getMessage().getHeader("downstreamStatus", 502, Integer.class);
        // Map a downstream 5xx to 502 (bad gateway); pass a downstream 4xx through unchanged.
        int responseCode = downstreamStatus >= 500 ? 502 : downstreamStatus;

        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("status", "FAILED");
        failure.put("message", "one or more downstream resource clusters failed");
        failure.put("downstreamStatus", downstreamStatus);
        failure.put("errors", aggregate != null ? aggregate.getOrDefault("errors", List.of()) : List.of());

        exchange.getMessage().setBody(failure);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, responseCode);
    }
}
