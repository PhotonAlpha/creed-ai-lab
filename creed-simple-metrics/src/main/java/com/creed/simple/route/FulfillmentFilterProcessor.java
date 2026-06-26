package com.creed.simple.route;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step-2 of {@code /api/fulfillment}: filters the aggregated order list down to the orders that are
 * actually <em>fulfillable</em>, combining both data sources from the previous multicast.
 *
 * <p>Business rule — an order is kept only when:
 * <ul>
 *   <li>its status is {@code NEW} or {@code PAID} (i.e. still actionable, not SHIPPED/CANCELLED), and</li>
 *   <li>its ordered {@code item} exists in the catalog, and</li>
 *   <li>the catalog {@code stock} for that item is &ge; the order {@code quantity}.</li>
 * </ul>
 * Everything else is dropped. The body is replaced with {@code {summary, fulfillable[]}}.
 *
 * <p>Referenced from {@code camel-context.xml} as {@code <process ref="fulfillmentFilterProcessor"/>}.
 */
@Component("fulfillmentFilterProcessor")
public class FulfillmentFilterProcessor implements Processor {

    private static final Set<String> ACTIONABLE = Set.of("NEW", "PAID");

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> aggregate = exchange.getMessage().getBody(Map.class);
        // Keys match the "source" header set by the fetch routes: "catalog" and "order".
        JsonNode catalog = (JsonNode) aggregate.get("catalog");
        JsonNode orders = (JsonNode) aggregate.get("order");

        // Index catalog stock by product name (the order's "item" references this name).
        Map<String, Integer> stockByItem = new HashMap<>();
        if (catalog != null) {
            for (JsonNode product : catalog) {
                stockByItem.put(product.path("name").asText(), product.path("stock").asInt());
            }
        }

        List<Map<String, Object>> fulfillable = new ArrayList<>();
        int total = 0;
        if (orders != null) {
            for (JsonNode order : orders) {
                total++;
                String status = order.path("status").asText();
                String item = order.path("item").asText();
                int quantity = order.path("quantity").asInt();
                if (ACTIONABLE.contains(status) && stockByItem.getOrDefault(item, 0) >= quantity) {
                    fulfillable.add(toOrder(order));
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrders", total);
        summary.put("fulfillable", fulfillable.size());
        summary.put("filteredOut", total - fulfillable.size());
        summary.put("catalogItems", stockByItem.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("fulfillable", fulfillable);
        exchange.getMessage().setBody(result);
    }

    private Map<String, Object> toOrder(JsonNode order) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", order.path("id").asText());
        o.put("customer", order.path("customer").asText());
        o.put("item", order.path("item").asText());
        o.put("quantity", order.path("quantity").asInt());
        o.put("total", order.path("total").asDouble());
        o.put("status", order.path("status").asText());
        return o;
    }
}
