package com.creed.simple.tutorial.strategy;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Used by API-5 and API-6: collects the result of every child message produced by {@code <split>}.
 *
 * <p>By default a split outputs the message as it was <i>before</i> the split, discarding the
 * child results. Only with an AggregationStrategy attached do the N child results become a
 * single message again - the <i>scatter-gather</i> flavour of split.
 */
@Component("itemCollectAggregationStrategy")
public class ItemCollectAggregationStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ItemCollectAggregationStrategy.class);

    @Override
    @SuppressWarnings("unchecked")
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        Object item = newExchange.getIn().getBody();
        if (oldExchange == null) {
            List<Object> list = new ArrayList<>();
            list.add(item);
            newExchange.getIn().setBody(list);
            log.info("[SPLIT-AGG] collected child result 1: {}", item);
            return newExchange;
        }
        List<Object> list = oldExchange.getIn().getBody(List.class);
        list.add(item);
        log.info("[SPLIT-AGG] collected child result {}: {}", list.size(), item);
        return oldExchange;
    }
}
