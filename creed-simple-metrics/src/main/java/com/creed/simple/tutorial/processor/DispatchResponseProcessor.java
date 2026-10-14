package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The assembly step of API-2.
 *
 * <p>It sits <b>outside</b> the {@code <filter>} on purpose, to prove one thing: when a filter
 * does not match it merely skips the steps <i>inside</i> it - the route itself is not over.
 * So a request with amount &lt; 100 still gets a normal 200 reply, only with {@code placed=false}.
 */
@Component("dispatchResponseProcessor")
public class DispatchResponseProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(DispatchResponseProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> in = exchange.getIn().getBody(Map.class);
        boolean placed = exchange.getIn().getHeader("placed", false, Boolean.class);
        String lane = exchange.getIn().getHeader("lane", "STANDARD", String.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", "OK");
        out.put("orderId", in.get("orderId"));
        out.put("amount", in.get("amount"));
        out.put("channel", in.get("channel"));
        out.put("lane", lane);                 // set by <choice>
        out.put("placed", placed);             // set by <filter>
        out.put("hint", placed
                ? "choice selected the " + lane + " lane, filter passed and the order was placed"
                : "choice selected the " + lane + " lane, but filter did not pass (amount < 100); "
                  + "only the placement was skipped, the route still assembled this reply");

        exchange.getIn().setBody(out);
        log.info("[API-2][assemble] lane={} placed={} reply assembled", lane, placed);
    }
}
