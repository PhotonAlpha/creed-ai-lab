package com.creed.simple.route;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregation strategy for the {@code /aggregate} route's multicast: it merges the catalog and order
 * branch results into a single JSON object. Each branch tags itself with a {@code source} header
 * (set in {@code camel/routes.xml}); this strategy reads that header to place the unmarshalled branch
 * body under the matching key.
 *
 * <p>Referenced from {@code camel/routes.xml} as {@code aggregateStrategy}.
 */
@Component("aggregateStrategy")
public class AggregateStrategy implements AggregationStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        Map<String, Object> result;
        if (oldExchange == null) {
            // First branch: seed the accumulator on the incoming exchange.
            result = new LinkedHashMap<>();
            result.put("aggregatedBy", "creed-simple-metrics");
            put(result, newExchange);
            newExchange.getIn().setBody(result);
            return newExchange;
        }
        // Subsequent branches: merge into the accumulator carried by oldExchange.
        result = oldExchange.getIn().getBody(Map.class);
        put(result, newExchange);
        return oldExchange;
    }

    private void put(Map<String, Object> result, Exchange branch) {
        String source = branch.getIn().getHeader("source", "unknown", String.class);
        result.put(source, branch.getIn().getBody());
    }
}
