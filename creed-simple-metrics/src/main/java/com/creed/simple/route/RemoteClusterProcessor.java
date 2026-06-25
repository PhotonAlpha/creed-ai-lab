package com.creed.simple.route;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls a downstream resource-server <em>cluster</em> from inside a Camel route, using the
 * {@code @LoadBalanced} {@link RestClient} so Spring Cloud LoadBalancer picks the instance — replacing
 * Camel's {@code <loadBalance>} round-robin EIP.
 *
 * <p>The target is a {@code https://<service-id>/...} URL passed in the {@code clusterUrl} header (set
 * by the {@code fetch-catalog} / {@code fetch-order} routes); the response JSON is bound to a
 * {@link JsonNode} and set as the message body so it can be nested into the aggregate result.
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
        JsonNode body = clusterRestClient.get()
                .uri(clusterUrl)
                .retrieve()
                .body(JsonNode.class);
        exchange.getMessage().setBody(body);
    }
}
