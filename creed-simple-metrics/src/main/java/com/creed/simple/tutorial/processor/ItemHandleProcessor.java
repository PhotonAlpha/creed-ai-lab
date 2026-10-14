package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Used by API-5 and API-6: shared by the three direct routes that {@code <toD>} can select.
 * Picks a warehouse strategy from the {@code itemChannel} header.
 */
@Component("itemHandleProcessor")
public class ItemHandleProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ItemHandleProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> item = exchange.getIn().getBody(Map.class);
        String channel = exchange.getIn().getHeader("itemChannel", "OTHER", String.class);

        String warehouse = switch (channel) {
            case "BOOK" -> "WH-BOOK-SH";
            case "FOOD" -> "WH-COLD-HZ";
            default -> "WH-GENERAL-BJ";
        };

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", item.get("sku"));
        out.put("qty", item.getOrDefault("qty", 1));
        out.put("channel", channel);
        out.put("warehouse", warehouse);
        out.put("status", "ALLOCATED");

        exchange.getIn().setBody(out);
        log.info("[ITEM-{}] sku={} allocated to {}", channel, item.get("sku"), warehouse);
    }
}
