package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The assembly step of API-6: builds a consistently shaped reply whether or not the
 * {@code <filter>} let the message through.
 *
 * <p>That is exactly the point of a filter: <b>a message that does not match skips the steps
 * inside the filter, but the route itself is not terminated</b>, so the nodes after it still
 * run. Which is why a single assembly step here can serve both branches.
 */
@Component("complexResponseProcessor")
public class ComplexResponseProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ComplexResponseProcessor.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        String decision = exchange.getIn().getHeader("decision", "UNKNOWN", String.class);
        String orderLevel = exchange.getIn().getHeader("orderLevel", "NORMAL", String.class);
        Map<String, Object> original = exchange.getProperty("originalOrder", Map.class);
        List<Object> fulfilled = exchange.getProperty("fulfilResult", List.class);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "APPROVED".equals(decision) ? "OK" : "REJECTED");
        resp.put("orderId", original == null ? null : original.get("orderId"));
        resp.put("orderLevel", orderLevel);
        resp.put("decision", decision);
        resp.put("totalScore", exchange.getProperty("totalScore"));
        resp.put("checks", exchange.getProperty("checkDetail"));
        resp.put("fulfilment", fulfilled == null ? List.of() : fulfilled);
        resp.put("message", "APPROVED".equals(decision)
                ? "risk checks passed, order split and allocated"
                : "risk checks failed, fulfilment skipped (filter did not pass, "
                  + "but the route still completed normally)");
        resp.put("exchangeId", exchange.getExchangeId());
        resp.put("finishedAt", LocalDateTime.now().format(FMT));

        exchange.getIn().setBody(resp);
        log.info("[API-6][assemble] decision={} items={} reply assembled",
                decision, fulfilled == null ? 0 : fulfilled.size());
    }
}
