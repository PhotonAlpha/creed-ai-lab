package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The scoring processor shared by the three parallel branches of API-4.
 *
 * <p>Each branch identifies itself through the {@code checkName} header, which makes the
 * execution order of the multicast visible in the console (with parallelProcessing=true
 * the order is not deterministic).
 */
@Component("scoreProcessor")
public class ScoreProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ScoreProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws InterruptedException {
        String checkName = exchange.getIn().getHeader("checkName", String.class);
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        double amount = ((Number) body.getOrDefault("amount", 0)).doubleValue();

        int score;
        long cost;
        switch (checkName) {
            case "RISK"      -> { cost = 200; score = amount > 50_000 ? 30 : 90; }
            case "CREDIT"    -> { cost = 350; score = amount > 60_000 ? 40 : 85; }
            case "INVENTORY" -> { cost = 120; score = 95; }
            // The three scores must total at least 240 (see RiskDecisionProcessor) to pass:
            //   amount <= 50000 -> 90+85+95 = 270 -> APPROVED
            //   amount >  60000 -> 30+40+95 = 165 -> REJECTED
            default -> { cost = 50; score = 60; }
        }
        Thread.sleep(cost);

        log.info("[API-4][branch-{}] thread={} took={}ms score={}",
                checkName, Thread.currentThread().getName(), cost, score);

        exchange.getIn().setBody(Map.of("check", checkName, "score", score, "costMs", cost));
    }
}
