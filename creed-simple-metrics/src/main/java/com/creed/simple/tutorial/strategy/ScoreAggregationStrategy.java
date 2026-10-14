package com.creed.simple.tutorial.strategy;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Used by API-4 and API-6: {@code <multicast aggregationStrategy="scoreAggregationStrategy">}
 *
 * <p><b>What an AggregationStrategy is</b>: multicast, split, aggregate and enrich - every EIP
 * that turns one message into many or many into one - has to answer the same question:
 * <i>how do the replies of several branches become a single message?</i> This interface is
 * that answer.
 *
 * <p><b>The contract</b> (worth memorising; getting it wrong is the usual bug):
 * <ul>
 *   <li>on the first call {@code oldExchange == null}, so return {@code newExchange} as the seed;</li>
 *   <li>for every later branch, merge it into {@code oldExchange} and <b>return oldExchange</b>;</li>
 *   <li>Camel calls this serially by default (it holds a lock), so no synchronisation is needed
 *       here; only {@code parallelAggregate} demands a thread-safe implementation.</li>
 * </ul>
 *
 * <p>If a multicast declares no aggregationStrategy, Camel's default is to <b>discard every
 * branch reply</b> and keep the message as it was before the multicast - the single most common
 * surprise for newcomers.
 */
@Component("scoreAggregationStrategy")
public class ScoreAggregationStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ScoreAggregationStrategy.class);

    @Override
    @SuppressWarnings("unchecked")
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        Map<String, Object> incoming = newExchange.getIn().getBody(Map.class);

        if (oldExchange == null) {
            // ===== first branch: initialise the accumulator =====
            Map<String, Object> acc = new LinkedHashMap<>();
            List<Map<String, Object>> checks = new ArrayList<>();
            checks.add(incoming);
            acc.put("checks", checks);
            acc.put("totalScore", scoreOf(incoming));
            newExchange.getIn().setBody(acc);
            log.info("[AGGREGATE] seed branch  <- {} (total {})", incoming, acc.get("totalScore"));
            return newExchange;
        }

        // ===== later branches: merge into oldExchange =====
        Map<String, Object> acc = oldExchange.getIn().getBody(Map.class);
        List<Map<String, Object>> checks = (List<Map<String, Object>>) acc.get("checks");
        checks.add(incoming);
        int total = ((Number) acc.get("totalScore")).intValue() + scoreOf(incoming);
        acc.put("totalScore", total);
        log.info("[AGGREGATE] merge branch <- {} ({} branches so far, total {})",
                incoming, checks.size(), total);
        return oldExchange;
    }

    private static int scoreOf(Map<String, Object> m) {
        Object s = m == null ? null : m.get("score");
        return s instanceof Number n ? n.intValue() : 0;
    }
}
