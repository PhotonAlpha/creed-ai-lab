package com.creed.simple.route;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backing bean for the two internal enrichment routers ({@code direct:enrich-shipping} /
 * {@code direct:enrich-risk}) fanned out by the second multicast in {@code /api/fulfillment} (step 3).
 *
 * <p>Each method receives the filtered fulfillment body ({@code {summary, fulfillable[]}}) and returns a
 * <em>fragment</em> that {@link EnrichmentAggregateStrategy} nests under {@code enrichment.<source>}. The
 * routes stay pure XML and delegate the per-order computation here.
 *
 * <p>Referenced from {@code camel-context.xml} as {@code bean:fulfillmentEnricher?method=...}.
 */
@Component("fulfillmentEnricher")
public class FulfillmentEnricher {

    /** Shipping estimate: larger orders take longer to pick & pack. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> shipping(Map<String, Object> body) {
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.getOrDefault("fulfillable", List.of());
        Map<String, Object> estimates = new LinkedHashMap<>();
        for (Map<String, Object> order : orders) {
            int quantity = ((Number) order.getOrDefault("quantity", 0)).intValue();
            int days = 1 + quantity / 5; // 1 day base, +1 per 5 units
            estimates.put((String) order.get("id"), days);
        }
        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("estimatedShippingDays", estimates);
        fragment.put("carrier", "creed-logistics");
        return fragment;
    }

    /** Risk score: higher-value orders carry more payment/fraud risk. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> risk(Map<String, Object> body) {
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.getOrDefault("fulfillable", List.of());
        Map<String, Object> scores = new LinkedHashMap<>();
        for (Map<String, Object> order : orders) {
            double total = ((Number) order.getOrDefault("total", 0)).doubleValue();
            String level = total > 500 ? "HIGH" : total > 100 ? "MEDIUM" : "LOW";
            scores.put((String) order.get("id"), level);
        }
        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("riskLevel", scores);
        fragment.put("model", "v1");
        return fragment;
    }
}
