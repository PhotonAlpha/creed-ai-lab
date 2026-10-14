package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Used by API-5: works out the dynamic target for {@code <toD>}.
 *
 * <p>The URI in {@code <toD uri="direct:item-${header.itemChannel}"/>} is evaluated at runtime,
 * but the endpoint it resolves to must actually exist or Camel throws
 * {@code NoSuchEndpointException}. Collapsing unknown types onto a default channel first is
 * the standard defensive pattern for toD.
 */
@Component("itemNormalizeProcessor")
public class ItemNormalizeProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ItemNormalizeProcessor.class);
    private static final Set<String> KNOWN = Set.of("BOOK", "FOOD");

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> item = exchange.getIn().getBody(Map.class);
        String type = String.valueOf(item.getOrDefault("type", "")).toUpperCase();
        String channel = KNOWN.contains(type) ? type : "OTHER";

        // CamelSplitIndex / CamelSplitSize are headers <split> sets automatically
        Integer idx = exchange.getIn().getHeader(Exchange.SPLIT_INDEX, Integer.class);
        Integer size = exchange.getIn().getHeader(Exchange.SPLIT_SIZE, Integer.class);

        exchange.getIn().setHeader("itemChannel", channel);
        log.info("[API-5][split {}/{}] sku={} type={} -> dynamic endpoint direct:item-{}",
                idx == null ? "?" : idx + 1, size, item.get("sku"), type, channel);
    }
}
