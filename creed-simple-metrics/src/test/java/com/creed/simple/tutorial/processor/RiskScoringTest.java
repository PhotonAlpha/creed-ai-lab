package com.creed.simple.tutorial.processor;

import com.creed.simple.tutorial.strategy.ScoreAggregationStrategy;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the threshold API-6 branches on (and the guide's slides quote): the three risk checks of
 * API-4, summed by {@link ScoreAggregationStrategy}, against {@link RiskDecisionProcessor}'s pass
 * line of 240. {@code amount <= 50000} must stay APPROVED at 270, {@code amount > 60000} REJECTED
 * at 165 - change a score or the line and the two API-6 regression cases stop covering both branches.
 */
class RiskScoringTest {

    private final CamelContext camel = new DefaultCamelContext();

    private Exchange decide(double amount) throws Exception {
        ScoreAggregationStrategy strategy = new ScoreAggregationStrategy();
        Exchange aggregated = null;
        for (String check : new String[] {"RISK", "CREDIT", "INVENTORY"}) {
            Exchange branch = new DefaultExchange(camel);
            branch.getIn().setHeader("checkName", check);
            branch.getIn().setBody(Map.of("amount", amount));
            new ScoreProcessor().process(branch);
            aggregated = strategy.aggregate(aggregated, branch);
        }
        new RiskDecisionProcessor().process(aggregated);
        return aggregated;
    }

    @Test
    void anOrdinaryOrderPasses() throws Exception {
        Exchange e = decide(20_000);
        assertThat(e.getProperty("totalScore")).isEqualTo(270);
        assertThat(e.getIn().getHeader("decision")).isEqualTo("APPROVED");
    }

    @Test
    void aLargeOrderIsRejected() throws Exception {
        Exchange e = decide(90_000);
        assertThat(e.getProperty("totalScore")).isEqualTo(165);
        assertThat(e.getIn().getHeader("decision")).isEqualTo("REJECTED");
    }
}
