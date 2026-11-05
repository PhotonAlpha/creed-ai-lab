package com.creed.simple.config;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.RoutePolicySupport;
import org.springframework.stereotype.Component;

/**
 * Keeps every REST request's original JSON body as the exchange property {@value #ORIGINAL_REQUEST}, so any
 * processor of any route can read it - multicast branches and wireTap copies included, because exchange
 * properties are copied into those exchanges.
 *
 * <p>Why not {@code exchange.getUnitOfWork().getOriginalInMessage()}: it throws while
 * {@code allowUseOriginalMessage} is off (Camel 4's default), and even when on, every multicast branch and
 * wireTap copy gets its own UnitOfWork whose "original" is the body at the split, not the HTTP request.
 *
 * <p>Timing: a route policy's {@code onExchangeBegin} runs before the REST binding advice ({@code RouteReifier}
 * adds the policy advice first), so the body is still the raw request, not yet the {@code bindingMode="json"}
 * Map. It is a one-shot stream: read once, it is put back as a String, which the binding unmarshals just the
 * same. JSON only - a multipart upload (tutorial API-8) must keep its stream.
 *
 * <p>Like {@link RestApiAuditRoutePolicyFactory}: discovered by the {@code <camelContext>} factory bean, applied
 * to every route, guarded to {@code servlet:} consumers, once per exchange (the nested {@code direct:} routes
 * see the same exchange begin again).
 */
@Component
public class OriginalRequestRoutePolicyFactory implements RoutePolicyFactory {

    /** The inbound REST request body, as received ({@code String}); absent for a request without a JSON body. */
    public static final String ORIGINAL_REQUEST = "originalRequest";

    private final RoutePolicy policy = new RoutePolicySupport() {
        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            if (!exchange.getFromEndpoint().getEndpointUri().startsWith("servlet:")
                    || exchange.getProperty(ORIGINAL_REQUEST) != null) {
                return;
            }
            String contentType = exchange.getIn().getHeader(Exchange.CONTENT_TYPE, String.class);
            if (contentType == null || !contentType.toLowerCase().contains("json")) {
                return;
            }
            String body = exchange.getIn().getBody(String.class);
            if (body != null) {
                exchange.getIn().setBody(body);
                exchange.setProperty(ORIGINAL_REQUEST, body);
            }
        }
    };

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        return policy;
    }
}
