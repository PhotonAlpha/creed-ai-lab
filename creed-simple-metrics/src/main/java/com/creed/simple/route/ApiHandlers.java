package com.creed.simple.route;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backing bean for the self-contained demo routes. Returning a {@link Map} lets the REST DSL's JSON
 * binding ({@code camel.rest.binding-mode=json}) marshal the response with Jackson, so the routes stay
 * pure XML and only delegate the data production here.
 *
 * <p>Referenced from {@code camel/routes.xml} as {@code bean:apiHandlers?method=...}.
 */
@Component("apiHandlers")
public class ApiHandlers {

    /** Body for {@code GET /api/hello}. */
    public Map<String, Object> hello() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "hello from camel");
        body.put("service", "creed-simple-metrics");
        return body;
    }

    /** Body for {@code GET /api/time}. */
    public Map<String, Object> time() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("now", OffsetDateTime.now().toString());
        body.put("epochMillis", System.currentTimeMillis());
        return body;
    }
}
