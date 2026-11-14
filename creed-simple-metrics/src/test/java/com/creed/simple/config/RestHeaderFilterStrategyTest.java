package com.creed.simple.config;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.http.common.DefaultHttpBinding;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through Camel's own servlet binding ({@code DefaultHttpBinding}, what the REST consumer uses), with the lists as
 * application.yml has them: what reaches the exchange from a request, and what goes back as response headers.
 */
class RestHeaderFilterStrategyTest {

    private static final RestHeaderFilterStrategy STRATEGY = new RestHeaderFilterStrategy(
            List.of("Authorization", "Proxy-Authorization", "Connection", "Keep-Alive", "Proxy-Connection", "TE",
                    "Trailer", "Transfer-Encoding", "Upgrade"),
            List.of("Cache-Control", "Content-Disposition", "Location", "Retry-After", "ETag", "Last-Modified"));

    private DefaultCamelContext camel;
    private DefaultHttpBinding binding;

    @BeforeEach
    void start() {
        camel = new DefaultCamelContext();
        camel.start();
        binding = new DefaultHttpBinding();
        binding.setHeaderFilterStrategy(STRATEGY);
    }

    @AfterEach
    void stop() {
        camel.stop();
    }

    @Test
    void credentialsAndHopByHopHeadersStayOutOfTheExchange() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/simple/camel/api/payment");
        request.addHeader("Authorization", "Bearer secret-token");
        request.addHeader("Proxy-Authorization", "Basic Zm9vOmJhcg==");
        request.addHeader("Connection", "keep-alive");
        request.addHeader("CamelHttpUri", "http://evil.example/");
        request.addHeader("Cookie", "stickyId=a; SESSION=s");
        request.addHeader("X-Channel", "APP");
        request.setQueryString("cluster=order&resilient=true");
        request.addParameter("cluster", "order");
        request.addParameter("resilient", "true");

        Exchange exchange = new DefaultExchange(camel);
        binding.readRequest(request, exchange.getIn());

        assertThat(exchange.getIn().getHeaders())
                .doesNotContainKeys("Authorization", "Proxy-Authorization", "Connection")
                // a client's Camel* header never gets in: this is the binding's own, from the request itself
                .containsEntry("CamelHttpUri", "/simple/camel/api/payment")
                // what the routes read: the sticky cookie, a client header, the query parameters
                .containsEntry("Cookie", "stickyId=a; SESSION=s")
                .containsEntry("X-Channel", "APP")
                .containsEntry("cluster", "order")
                .containsEntry("resilient", "true");
    }

    @Test
    void onlyListedHeadersGoBackToTheClient() throws Exception {
        Exchange exchange = new DefaultExchange(camel);
        Message message = exchange.getMessage();
        // what the message holds by the end of a route: the request's headers, internal ones, a downstream's answer
        message.setHeader("Cookie", "SESSION=secret-session");
        message.setHeader("Authorization", "Bearer secret-token");
        message.setHeader("X-Api-Key", "k-123");
        message.setHeader("X-Trace-Note", "x".repeat(8000));
        message.setHeader("source", "catalog");
        message.setHeader("branchError", "boom");
        message.setHeader("Set-Cookie", "JSESSIONID=downstream; Path=/");
        message.setHeader("CamelHttpResponseCode", 201);
        message.setHeader("Content-Type", "application/json");
        message.setHeader("Cache-Control", "no-store");
        message.setHeader("Location", "/simple/camel/api/orders/7");
        message.setBody("{\"id\":7}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        binding.doWriteResponse(message, response, exchange);

        assertThat(response.getHeaderNames()).containsExactlyInAnyOrder("Content-Type", "Cache-Control", "Location");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":7}");
    }

    @Test
    void aListedNameIsNeverACamelHeader() {
        RestHeaderFilterStrategy careless = new RestHeaderFilterStrategy(List.of(), List.of("CamelHttpUri"));

        assertThat(careless.applyFilterToCamelHeaders("CamelHttpUri", "/x", null)).isTrue();
    }

    @Test
    void namesMatchInAnyCase() {
        assertThat(STRATEGY.applyFilterToCamelHeaders("cache-control", "no-store", null)).isFalse();
        assertThat(STRATEGY.applyFilterToCamelHeaders("CACHE-CONTROL", "no-store", null)).isFalse();
        assertThat(STRATEGY.applyFilterToCamelHeaders("x-api-key", "k", null)).isTrue();
        assertThat(STRATEGY.applyFilterToExternalHeaders("AUTHORIZATION", "Bearer x", null)).isTrue();
        assertThat(STRATEGY.applyFilterToExternalHeaders("cookie", "a=b", null)).isFalse();
    }
}
