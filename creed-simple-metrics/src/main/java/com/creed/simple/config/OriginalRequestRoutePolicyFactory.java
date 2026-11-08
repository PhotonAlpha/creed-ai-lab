package com.creed.simple.config;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.SafeCopyProperty;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.RoutePolicySupport;
import org.springframework.stereotype.Component;

/**
 * Keeps every REST request's original JSON body on the exchange, read with {@link #originalRequest(Exchange)},
 * so any processor of any route can read it - multicast branches and wireTap copies included.
 *
 * <p>Read-only by construction: it is a <em>safe-copy property</em>
 * ({@code ExchangeExtension.setSafeCopyProperty}), not an ordinary exchange property. Those live in a map of
 * their own, which {@code setProperty}, {@code removeProperty}, {@code removeProperties("*")} (and the XML DSL's
 * {@code <removeProperties pattern="*"/>}) never touch, while {@code Exchange.copy()} - what multicast and wireTap
 * branch from - carries them over. The value is an immutable record only this class can create, and the key is
 * private. An ordinary property could not be protected: the properties are a plain map and
 * {@code DefaultExchange} is final, so there is no write to intercept. The price: Simple's
 * {@code ${exchangeProperty.originalRequest}} cannot see it - call {@link #originalRequest(Exchange)} instead.
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

    private static final String ORIGINAL_REQUEST = "originalRequest";

    /** Immutable, so a branch's copy can share it rather than duplicate the body. */
    private record OriginalRequest(String body) implements SafeCopyProperty {
        @Override
        public SafeCopyProperty safeCopy() {
            return this;
        }
    }

    /** The inbound REST request body as received; {@code null} for a request without a JSON body. */
    public static String originalRequest(Exchange exchange) {
        OriginalRequest request = exchange.getExchangeExtension()
                .getSafeCopyProperty(ORIGINAL_REQUEST, OriginalRequest.class);
        return request == null ? null : request.body();
    }

    /**
     * Carries the original request onto an exchange Camel did not copy from this one - one built for a
     * {@code ProducerTemplate} call, say - unless that exchange already has one. The only way to put it anywhere.
     */
    public static void carryOver(Exchange from, Exchange to) {
        OriginalRequest request = from.getExchangeExtension()
                .getSafeCopyProperty(ORIGINAL_REQUEST, OriginalRequest.class);
        if (request != null && originalRequest(to) == null) {
            to.getExchangeExtension().setSafeCopyProperty(ORIGINAL_REQUEST, request);
        }
    }

    private final RoutePolicy policy = new RoutePolicySupport() {
        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            if (!exchange.getFromEndpoint().getEndpointUri().startsWith("servlet:")
                    || originalRequest(exchange) != null) {
                return;
            }
            String contentType = exchange.getIn().getHeader(Exchange.CONTENT_TYPE, String.class);
            if (contentType == null || !contentType.toLowerCase().contains("json")) {
                return;
            }
            String body = exchange.getIn().getBody(String.class);
            if (body != null) {
                exchange.getIn().setBody(body);
                exchange.getExchangeExtension().setSafeCopyProperty(ORIGINAL_REQUEST, new OriginalRequest(body));
            }
        }
    };

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        return policy;
    }
}
