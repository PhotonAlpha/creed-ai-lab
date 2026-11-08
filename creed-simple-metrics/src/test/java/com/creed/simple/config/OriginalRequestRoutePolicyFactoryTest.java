package com.creed.simple.config;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.creed.simple.config.OriginalRequestRoutePolicyFactory.carryOver;
import static com.creed.simple.config.OriginalRequestRoutePolicyFactory.originalRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The original request is read-only to route code: no ordinary property write or removal reaches it, while
 * multicast branches and wireTap copies still see it. The policy is driven directly - a {@code servlet:}
 * consumer needs a servlet container - on an exchange whose from-endpoint is a {@code servlet:} one.
 */
class OriginalRequestRoutePolicyFactoryTest {

    private static final String JSON = "{\"orderId\":\"o-1\",\"items\":[1,2]}";

    private final CamelContext camel = new DefaultCamelContext();
    private final RoutePolicy policy = new OriginalRequestRoutePolicyFactory().createRoutePolicy(camel, "rest", null);

    @BeforeEach
    void start() {
        camel.start();
    }

    @AfterEach
    void stop() {
        camel.stop();
    }

    /** A request as the servlet consumer hands it over: a one-shot stream from a {@code servlet:} endpoint. */
    private Exchange restRequest(String uri, String contentType) {
        Endpoint from = mock(Endpoint.class);
        when(from.getEndpointUri()).thenReturn(uri);
        Exchange exchange = new DefaultExchange(camel);
        exchange.getExchangeExtension().setFromEndpoint(from);
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, contentType);
        exchange.getIn().setBody(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)));
        policy.onExchangeBegin(null, exchange);
        return exchange;
    }

    @Test
    void keepsTheJsonBodyAndPutsItBackForTheBinding() {
        Exchange exchange = restRequest("servlet:/api/fulfillment", "application/json; charset=UTF-8");

        assertThat(originalRequest(exchange)).isEqualTo(JSON);
        assertThat(exchange.getIn().getBody()).isEqualTo(JSON);
        // not an ordinary property: nothing under the old key for route code to overwrite
        assertThat(exchange.getProperties()).doesNotContainKey("originalRequest");
    }

    @Test
    void ordinaryPropertyWritesDoNotReachIt() {
        Exchange exchange = restRequest("servlet:/api/fulfillment", "application/json");

        exchange.setProperty("originalRequest", "tampered");
        assertThat(originalRequest(exchange)).isEqualTo(JSON);
        exchange.removeProperty("originalRequest");
        exchange.removeProperties("*");
        assertThat(originalRequest(exchange)).isEqualTo(JSON);
    }

    @Test
    void setOnceTheNestedRoutesDoNotReplaceIt() {
        Exchange exchange = restRequest("servlet:/api/fulfillment", "application/json");
        // a direct: route entered later sees the same exchange begin again, with the body already a Map
        exchange.getIn().setBody("{\"rebound\":true}");
        policy.onExchangeBegin(null, exchange);

        assertThat(originalRequest(exchange)).isEqualTo(JSON);
    }

    @Test
    void onlyJsonRequestsFromAServletConsumer() {
        assertThat(originalRequest(restRequest("servlet:/api/upload", "multipart/form-data; boundary=x"))).isNull();
        assertThat(originalRequest(restRequest("timer:tick", "application/json"))).isNull();
    }

    @Test
    void multicastBranchesAndWireTapCopiesSeeItButCannotRemoveIt() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch tapped = new CountDownLatch(1);
        camel.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:in")
                        .multicast().parallelProcessing().to("direct:a", "direct:b").end()
                        .wireTap("direct:tap")
                        .process(e -> seen.add("main:" + originalRequest(e)));
                // each branch tries both ways of losing it first
                from("direct:a").removeProperties("*").process(e -> seen.add("a:" + originalRequest(e)));
                from("direct:b").setProperty("originalRequest", constant("tampered"))
                        .process(e -> seen.add("b:" + originalRequest(e)));
                from("direct:tap").process(e -> {
                    seen.add("tap:" + originalRequest(e));
                    tapped.countDown();
                });
            }
        });
        Exchange exchange = restRequest("servlet:/api/fulfillment", "application/json");

        camel.createProducerTemplate().send("direct:in", exchange);

        assertThat(tapped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exchange.getException()).isNull();
        assertThat(seen).containsExactlyInAnyOrder("a:" + JSON, "b:" + JSON, "main:" + JSON, "tap:" + JSON);
    }

    @Test
    void carriedOverOntoANewExchangeOnlyWhenItHasNone() {
        Exchange exchange = restRequest("servlet:/api/fulfillment", "application/json");
        Exchange fresh = new DefaultExchange(camel);

        carryOver(exchange, fresh);
        assertThat(originalRequest(fresh)).isEqualTo(JSON);

        Exchange other = restRequest("servlet:/api/other", "application/json");
        other.getIn().setBody("x");
        carryOver(new DefaultExchange(camel), other);
        assertThat(originalRequest(other)).isEqualTo(JSON);
    }
}
