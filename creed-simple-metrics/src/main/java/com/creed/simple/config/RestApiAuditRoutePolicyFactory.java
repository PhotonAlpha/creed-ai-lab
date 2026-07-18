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
 * Attaches a {@link RestApiAuditSynchronization} to every exchange that enters through the
 * camel-servlet REST consumer, so a completion audit line fires once per REST API request.
 *
 * <p>Just a {@code @Component}: the classic {@code <camelContext>} factory bean
 * ({@code AbstractCamelContextFactoryBean}) discovers every {@code RoutePolicyFactory} in the Spring
 * registry and adds it to the context, which then applies the policy to ALL routes. The policy
 * itself therefore guards on the exchange's from-endpoint: only {@code servlet:} consumers (the REST
 * transport) get the synchronization; the nested {@code direct:} routes see the same exchange begin
 * again and are skipped by the idempotency property.
 */
@Component
public class RestApiAuditRoutePolicyFactory implements RoutePolicyFactory {

    /** Exchange property marking that the audit synchronization is already attached. */
    static final String AUDIT_ATTACHED = "CreedRestApiAuditAttached";

    private final RoutePolicy policy = new RoutePolicySupport() {
        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            if (!exchange.getFromEndpoint().getEndpointUri().startsWith("servlet:")
                    || exchange.getProperty(AUDIT_ATTACHED) != null) {
                return;
            }
            exchange.setProperty(AUDIT_ATTACHED, Boolean.TRUE);
            // Capture the request line NOW — the fetch-* routes strip CamelHttp* headers later.
            String method = exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class);
            String uri = exchange.getIn().getHeader(Exchange.HTTP_URI, String.class);
            String query = exchange.getIn().getHeader(Exchange.HTTP_QUERY, String.class);
            if (uri == null) {
                uri = exchange.getIn().getHeader(Exchange.HTTP_PATH, String.class);
            }
            if (query != null && !query.isEmpty()) {
                uri = uri + "?" + query;
            }
            exchange.getExchangeExtension().addOnCompletion(new RestApiAuditSynchronization(method, uri));
        }
    };

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        return policy;
    }
}
