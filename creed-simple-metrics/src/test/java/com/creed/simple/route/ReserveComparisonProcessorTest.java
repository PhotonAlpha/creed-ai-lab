package com.creed.simple.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The reserve pair: both clients produce the same envelope, and a downstream 4xx surfaces as the same
 * envelope whichever exception type the client threw.
 */
class ReserveComparisonProcessorTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReserveComparisonProcessor processor =
            new ReserveComparisonProcessor(builder.build(), new ObjectMapper());

    private static Exchange request(Map<String, Object> body) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody(body);
        return exchange;
    }

    @Test
    @SuppressWarnings("unchecked")
    void restClientPostsTheValidatedPayloadAndWrapsTheReply() {
        server.expect(requestTo(ReserveComparisonProcessor.DOWNSTREAM))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"sku\":\"CAT-1\",\"quantity\":2}"))
                .andRespond(withSuccess("{\"sku\":\"CAT-1\",\"total\":7.0}", MediaType.APPLICATION_JSON));
        Exchange exchange = request(Map.of("sku", "CAT-1", "quantity", 2, "ignored", true));

        processor.viaRestClient(exchange);

        Map<String, Object> envelope = exchange.getMessage().getBody(Map.class);
        assertThat(envelope).containsEntry("client", ReserveComparisonProcessor.CLIENT_REST_CLIENT)
                .containsEntry("status", 200).containsKey("elapsedMs");
        assertThat((Map<String, Object>) envelope.get("body")).containsEntry("total", 7.0);
        server.verify();
    }

    @Test
    void httpComponentGetsAJsonStringBodyAndTheSameEnvelopeBack() throws Exception {
        Exchange exchange = request(Map.of("sku", "CAT-1"));
        processor.prepareHttpComponent(exchange);
        assertThat(exchange.getMessage().getBody(String.class)).isEqualTo("{\"sku\":\"CAT-1\",\"quantity\":1}");

        exchange.getMessage().setBody("{\"total\":3.5}");   // what camel-http leaves in the body
        processor.finishHttpComponent(exchange);
        assertThat(exchange.getMessage().getBody(Map.class))
                .containsEntry("client", ReserveComparisonProcessor.CLIENT_HTTP_COMPONENT)
                .containsEntry("status", 200).containsEntry("body", Map.of("total", 3.5));
    }

    @Test
    void aDownstream409IsTheSameEnvelopeFromEitherClient() {
        String conflict = "{\"error\":\"insufficient stock\",\"available\":3}";

        server.expect(requestTo(ReserveComparisonProcessor.DOWNSTREAM))
                .andRespond(withStatus(HttpStatus.CONFLICT).body(conflict).contentType(MediaType.APPLICATION_JSON));
        Exchange viaRest = request(Map.of("sku", "CAT-1", "quantity", 9));
        assertThatThrownBy(() -> processor.viaRestClient(viaRest))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(ex -> viaRest.setProperty(Exchange.EXCEPTION_CAUGHT, ex));
        processor.failure(viaRest);

        Exchange viaCamel = request(Map.of("sku", "CAT-1", "quantity", 9));
        viaCamel.setProperty("reserveClient", ReserveComparisonProcessor.CLIENT_HTTP_COMPONENT);
        viaCamel.setProperty(Exchange.EXCEPTION_CAUGHT, new HttpOperationFailedException(
                ReserveComparisonProcessor.DOWNSTREAM, 409, "Conflict", null, Map.of(), conflict));
        processor.failure(viaCamel);

        for (Exchange e : new Exchange[] {viaRest, viaCamel}) {
            assertThat(e.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(409);
            assertThat(e.getMessage().getBody(Map.class)).containsEntry("status", 409)
                    .containsEntry("body", Map.of("error", "insufficient stock", "available", 3));
        }
    }

    @Test
    void aMissingSkuIs400BeforeAnyCall() {
        Exchange exchange = request(Map.of("quantity", 1));
        assertThatThrownBy(() -> processor.viaRestClient(exchange))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> exchange.setProperty(Exchange.EXCEPTION_CAUGHT, ex));
        processor.failure(exchange);

        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(400);
        server.verify();   // nothing was sent
    }

    @Test
    void aDownstream5xxBecomes502() {
        Exchange exchange = request(Map.of("sku", "CAT-1"));
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new HttpOperationFailedException(
                ReserveComparisonProcessor.DOWNSTREAM, 503, "Unavailable", null, Map.of(), ""));
        processor.failure(exchange);
        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(502);
        assertThat(exchange.getMessage().getBody(Map.class)).containsEntry("status", 503);
    }
}
