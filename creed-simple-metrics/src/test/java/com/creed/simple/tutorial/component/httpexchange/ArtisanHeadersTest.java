package com.creed.simple.tutorial.component.httpexchange;

import com.creed.simple.tutorial.client.ArtisanClient;
import com.creed.simple.tutorial.client.ArtisanHeaders;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;

/**
 * The per-request header policy of {@link ArtisanClient}: read from the Camel Exchange the component
 * publishes in {@link HttpExchangeContext} (hence this package), or nothing outside a component call.
 */
class ArtisanHeadersTest {

    private static HttpHeaders sentHeaders(Exchange exchange) throws Exception {
        AtomicReference<HttpHeaders> sent = new AtomicReference<>();
        Runnable call = () -> {
            try {
                new ArtisanHeaders().intercept(new MockClientHttpRequest(POST, "https://localhost/mock/artisan/list"),
                        new byte[0], (request, body) -> {
                            sent.set(request.getHeaders());
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        if (exchange == null) {
            call.run();
        } else {
            HttpExchangeContext.callWith(exchange, () -> {
                call.run();
                return null;
            });
        }
        return sent.get();
    }

    @Test
    void addsTraceIdAndTheChannelOfTheOrder() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(Map.of("channel", "APP"));

        HttpHeaders headers = sentHeaders(exchange);
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo(exchange.getExchangeId());
        assertThat(headers.getFirst("X-Channel")).isEqualTo("APP");
    }

    @Test
    void anExplicitChannelHeaderWinsAndWebIsTheDefault() throws Exception {
        Exchange explicit = new DefaultExchange(new DefaultCamelContext());
        explicit.getIn().setHeader("X-Channel", "BRANCH");
        explicit.getIn().setBody(Map.of("channel", "APP"));
        assertThat(sentHeaders(explicit).getFirst("X-Channel")).isEqualTo("BRANCH");

        Exchange none = new DefaultExchange(new DefaultCamelContext());
        none.getIn().setBody(Map.of());
        assertThat(sentHeaders(none).getFirst("X-Channel")).isEqualTo("WEB");
    }

    @Test
    void outsideTheComponentNothingIsAdded() throws Exception {
        HttpHeaders headers = sentHeaders(null);
        assertThat(headers.containsHeader("X-Trace-Id")).isFalse();
        assertThat(headers.containsHeader("X-Channel")).isFalse();
    }
}
