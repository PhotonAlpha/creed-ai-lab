package com.creed.simple.tutorial.client;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the query-parameter Map that the {@code httpexchange:} component hands to the
 * {@code @RequestParam Map<String,String>} parameter of {@code ArtisanClient#listUser}.
 *
 * <p>Called from the route with {@code <method ref="artisanQueryParams" method="build"/>},
 * which keeps the map construction out of the XML without needing a full Processor.
 */
@Component("artisanQueryParams")
public class ArtisanQueryParams {

    @SuppressWarnings("unchecked")
    public Map<String, String> build(Exchange exchange) {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("scope", "ALL");
        params.put("channel", String.valueOf(body.getOrDefault("channel", "WEB")));
        return params;
    }
}
