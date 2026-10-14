package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Used by API-1: {@code <process ref="orderValidateProcessor"/>}
 *
 * <p><b>What a Processor is for</b>: a block of custom Java inside a route.
 * It receives the whole {@link Exchange} (the full context of one message exchange:
 * the In message, headers, properties, exceptions) and may read or write any of it.
 * Good for validation, instrumentation and non-trivial rewriting; for a simple lookup
 * or comparison prefer a {@code <simple>} expression instead of writing Java.
 */
@Component("orderValidateProcessor")
public class OrderValidateProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(OrderValidateProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        log.info("[API-1][validate] incoming order payload: {}", body);

        if (body == null || body.get("orderId") == null) {
            // Throwing moves the Exchange into a failed state, catchable by onException / errorHandler
            throw new IllegalArgumentException("orderId must not be null");
        }
        Number amount = (Number) body.getOrDefault("amount", 0);
        if (amount.doubleValue() <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }

        // Headers are metadata that travels with the message; downstream <simple>/<log> can read them
        exchange.getIn().setHeader("orderId", body.get("orderId"));
        exchange.getIn().setHeader("validated", true);
        log.info("[API-1][validate] passed, orderId={}, amount={}", body.get("orderId"), amount);
    }
}
