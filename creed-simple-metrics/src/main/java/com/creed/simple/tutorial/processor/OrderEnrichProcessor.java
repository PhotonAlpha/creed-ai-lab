package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Used by API-1: fills in the remaining fields after validation and assembles the final reply.
 *
 * <p>Shows the second typical use of a Processor: <b>rewriting the Body</b>.
 * After {@code exchange.getIn().setBody(...)} every later step sees the new body.
 */
@Component("orderEnrichProcessor")
public class OrderEnrichProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(OrderEnrichProcessor.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> in = exchange.getIn().getBody(Map.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", "OK");
        out.put("orderId", in.get("orderId"));
        out.put("amount", in.get("amount"));
        out.put("channel", in.getOrDefault("channel", "UNKNOWN"));
        out.put("handledBy", "route:api1-basic-order");
        // exchangeId uniquely identifies this message exchange, which is invaluable when tracing
        out.put("exchangeId", exchange.getExchangeId());
        out.put("processedAt", LocalDateTime.now().format(FMT));

        exchange.getIn().setBody(out);
        log.info("[API-1][enrich] reply assembled: {}", out);
    }
}
