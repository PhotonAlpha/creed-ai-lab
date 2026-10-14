package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Used by API-6: turns the total score produced by the multicast into a business decision.
 *
 * <p>The decision goes into a header so the following {@code <filter>} can decide whether
 * fulfilment should continue.
 */
@Component("riskDecisionProcessor")
public class RiskDecisionProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionProcessor.class);
    private static final int PASS_LINE = 240;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> agg = exchange.getIn().getBody(Map.class);
        int total = ((Number) agg.getOrDefault("totalScore", 0)).intValue();
        String decision = total >= PASS_LINE ? "APPROVED" : "REJECTED";

        exchange.setProperty("checkDetail", agg.get("checks"));
        exchange.setProperty("totalScore", total);
        exchange.getIn().setHeader("decision", decision);

        log.info("[API-6][decision] total={} threshold={} => {}", total, PASS_LINE, decision);
    }
}
