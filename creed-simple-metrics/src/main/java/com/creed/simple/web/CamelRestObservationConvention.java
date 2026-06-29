package com.creed.simple.web;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Gives Camel REST calls a meaningful {@code uri} tag in {@code http_server_requests_seconds}.
 *
 * <p>Spring Boot's {@code ServerHttpObservationFilter} wraps <em>every</em> servlet request (it is a plain
 * filter mapped to {@code /*}), so the Camel REST endpoints served by {@code CamelHttpTransportServlet} at
 * {@code /camel/api/*} are already observed. But because they are not handled by Spring MVC's
 * {@code DispatcherServlet}, there is no matched path pattern, and the default convention tags them
 * {@code uri="UNKNOWN"} — collapsing every Camel endpoint into one undistinguishable series.
 *
 * <p>This convention overrides the {@code uri} key value for {@code /camel/} requests, substituting the
 * request path so each endpoint (e.g. {@code /camel/api/fulfillment}) is broken out. The Camel REST paths
 * in this app are static (no path variables), so using the raw path is cardinality-safe; the substitution
 * is additionally limited to non-error responses so 404 probing on bogus paths can't explode the series
 * count (those keep the default {@code UNKNOWN}/{@code NOT_FOUND} tag).
 */
@Component
public class CamelRestObservationConvention extends DefaultServerRequestObservationConvention {

    private static final String CAMEL_PREFIX = "/camel/";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues keyValues = super.getLowCardinalityKeyValues(context);

        HttpServletRequest request = context.getCarrier();
        HttpServletResponse response = context.getResponse();
        if (request == null) {
            return keyValues;
        }
        String path = request.getRequestURI();
        int status = (response != null) ? response.getStatus() : 0;
        if (path == null || !path.startsWith(CAMEL_PREFIX) || status >= 400) {
            return keyValues;
        }

        // Replace the default uri tag (UNKNOWN for the non-MVC Camel servlet) with the real path.
        KeyValues withoutUri = KeyValues.empty();
        for (KeyValue keyValue : keyValues) {
            if (!"uri".equals(keyValue.getKey())) {
                withoutUri = withoutUri.and(keyValue);
            }
        }
        return withoutUri.and(KeyValue.of("uri", path));
    }
}
