package com.creed.simple.route;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-1 aggregation for {@code /api/fulfillment}: merges the parallel catalog + order branches of the
 * first multicast into a single {@code {catalog, orders}} accumulator.
 *
 * <p>Unlike {@link AggregateStrategy}, this also collects per-branch failures: if a branch came back via
 * {@link RemoteClusterProcessor}'s resilient path ({@code branchError=true}), its error map is appended
 * to an {@code errors} list and the worst status code is recorded under the {@code downstreamError} /
 * {@code downstreamStatus} headers of the aggregate. The route reads those headers to decide whether to
 * short-circuit to a failure response (requirement 5).
 *
 * <p>Referenced from {@code camel-context.xml} as {@code aggregationStrategy="fulfillmentAggregateStrategy"}.
 */
@Component("fulfillmentAggregateStrategy")
public class FulfillmentAggregateStrategy implements AggregationStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // Capture the branch's data BEFORE touching any body: when oldExchange is null the accumulator
        // IS newExchange, so seeding its body would otherwise overwrite the branch body we need to read.
        String source = newExchange.getIn().getHeader("source", "unknown", String.class);
        boolean branchError = newExchange.getIn().getHeader("branchError", false, Boolean.class);
        int branchStatus = newExchange.getIn().getHeader("branchStatus", 500, Integer.class);
        Object branchBody = newExchange.getIn().getBody();

        Exchange accumulator = oldExchange != null ? oldExchange : newExchange;
        Map<String, Object> result;
        if (oldExchange == null) {
            result = new LinkedHashMap<>();
            result.put("errors", new ArrayList<Map<String, Object>>());
        } else {
            result = oldExchange.getIn().getBody(Map.class);
        }

        if (branchError) {
            ((List<Object>) result.get("errors")).add(branchBody);
            accumulator.getIn().setHeader("downstreamError", true);
            // Keep the most severe status seen so far.
            int prior = accumulator.getIn().getHeader("downstreamStatus", 0, Integer.class);
            accumulator.getIn().setHeader("downstreamStatus", Math.max(prior, branchStatus));
        } else {
            result.put(source, branchBody);
        }
        accumulator.getIn().setBody(result);
        return accumulator;
    }
}
