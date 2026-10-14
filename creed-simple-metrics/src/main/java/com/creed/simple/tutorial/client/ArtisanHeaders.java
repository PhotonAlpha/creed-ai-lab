package com.creed.simple.tutorial.client;

import com.creed.simple.tutorial.component.httpexchange.HttpExchangeContext;
import org.apache.camel.Exchange;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Map;

/**
 * The single place that decides which per-request headers {@link ArtisanClient} sends.
 *
 * <p>An interceptor on the RestClient that backs {@code ArtisanClient} only, so it runs for every
 * method of that interface and for nothing else: methods declare their business arguments only,
 * and the header policy lives here.
 *
 * <p>The original (Spring Framework 7) version was an {@code HttpRequestValues.Processor} on the proxy
 * factory. That type does not exist in Framework 6.2, which this module runs on; a client interceptor
 * does the same job, one level lower, and needs no method check because the RestClient is not shared.
 *
 * <p>Three layers, each used for what it is good at:
 * <ul>
 *   <li>constant headers - {@code @HttpExchange(headers = ...)} on the interface (X-Client-Id);</li>
 *   <li>per-request headers - this interceptor, reading the Camel Exchange (X-Trace-Id, X-Channel);</li>
 *   <li>a one-off header for a single method - still possible with {@code @RequestHeader} on that
 *       method, which the {@code httpexchange:} component keeps binding from Exchange headers.</li>
 * </ul>
 * The trace context itself (W3C {@code traceparent}) is not set here: the observation-instrumented
 * RestClient adds it on its own.
 */
public class ArtisanHeaders implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        // RestClient is synchronous: this runs on the thread that holds the Exchange
        Exchange exchange = HttpExchangeContext.current();
        if (exchange != null) {
            request.getHeaders().set("X-Trace-Id", exchange.getExchangeId());
            request.getHeaders().set("X-Channel", channelOf(exchange));
        }
        // else: called from plain Java rather than through the httpexchange: component
        return execution.execute(request, body);
    }

    /** An explicit X-Channel header wins, otherwise the channel field of the order, otherwise WEB. */
    static String channelOf(Exchange exchange) {
        String header = exchange.getIn().getHeader("X-Channel", String.class);
        if (header != null) {
            return header;
        }
        if (exchange.getIn().getBody() instanceof Map<?, ?> order && order.get("channel") != null) {
            return String.valueOf(order.get("channel"));
        }
        return "WEB";
    }
}
