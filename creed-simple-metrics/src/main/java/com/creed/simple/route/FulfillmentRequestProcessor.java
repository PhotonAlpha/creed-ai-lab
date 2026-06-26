package com.creed.simple.route;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reads the optional JSON request body of {@code POST /api/fulfillment} and lifts the fault-injection
 * flags into headers the downstream fetch routes read.
 *
 * <p>Body shape (all fields optional, default {@code false}):
 * <pre>{ "failCatalog": false, "failOrder": false }</pre>
 * An empty/absent body means "no faults". The body is cleared afterwards so the multicast branches start
 * from a clean exchange.
 *
 * <p>The REST DSL is configured with {@code bindingMode="json"} + {@code consumes="application/json"}, so
 * Camel has already unmarshalled the request into a {@link Map} — we read that directly (re-parsing the
 * stringified body would fail, since {@code Map.toString()} is not JSON).
 *
 * <p>Referenced from {@code camel-context.xml} as {@code <process ref="fulfillmentRequestProcessor"/>}.
 */
@Component("fulfillmentRequestProcessor")
public class FulfillmentRequestProcessor implements Processor {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> body = exchange.getMessage().getBody(Map.class);
        boolean failCatalog = body != null && Boolean.TRUE.equals(body.get("failCatalog"));
        boolean failOrder = body != null && Boolean.TRUE.equals(body.get("failOrder"));
        exchange.getMessage().setHeader("failCatalog", failCatalog);
        exchange.getMessage().setHeader("failOrder", failOrder);
        exchange.getMessage().setBody(null);
    }
}
