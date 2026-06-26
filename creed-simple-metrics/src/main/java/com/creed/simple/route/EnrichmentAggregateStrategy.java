package com.creed.simple.route;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step-3 aggregation for {@code /api/fulfillment}: merges the {@code shipping} and {@code risk}
 * enrichment fragments produced by the second multicast back onto the filtered base.
 *
 * <p>The filtered body ({@code {summary, fulfillable[]}}) is carried into the multicast as the
 * {@code fulfillmentBase} exchange property (each branch gets a copy). This strategy seeds the result
 * from that base and nests every branch fragment under {@code enrichment.<source>}, yielding
 * {@code {summary, fulfillable[], enrichment:{shipping, risk}}}.
 *
 * <p>Referenced from {@code camel-context.xml} as {@code aggregationStrategy="enrichmentAggregateStrategy"}.
 */
@Component("enrichmentAggregateStrategy")
public class EnrichmentAggregateStrategy implements AggregationStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // Capture the branch fragment BEFORE seeding: when oldExchange is null the accumulator IS
        // newExchange, so setting its body first would clobber the fragment we still need to read.
        String source = newExchange.getIn().getHeader("source", "unknown", String.class);
        Object fragment = newExchange.getIn().getBody();

        Exchange accumulator = oldExchange != null ? oldExchange : newExchange;
        Map<String, Object> result;
        if (oldExchange == null) {
            // Seed from the filtered base stashed before the multicast (copied onto every branch).
            Map<String, Object> base = newExchange.getProperty("fulfillmentBase", Map.class);
            result = new LinkedHashMap<>(base);
            result.put("enrichment", new LinkedHashMap<String, Object>());
        } else {
            result = oldExchange.getIn().getBody(Map.class);
        }

        Map<String, Object> enrichment = (Map<String, Object>) result.get("enrichment");
        enrichment.put(source, fragment);
        accumulator.getIn().setBody(result);
        return accumulator;
    }
}
