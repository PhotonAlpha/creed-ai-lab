package com.creed.simple.tutorial.component.httpexchange;

import com.creed.simple.tutorial.client.ArtisanClient;
import com.creed.simple.tutorial.client.MyAccountDetailsVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code httpexchange:} component end to end without HTTP: the scheme is found through
 * {@code META-INF/services}, the request template's text reaches the {@code @RequestBody String}
 * parameter, the {@code ResponseEntity} is unwrapped, and the response template plus
 * {@code unmarshalTo} turn the reply into the Map the REST binding serialises. Same templates as
 * the API-7 routes.
 */
class HttpExchangeComponentTest {

    private CamelContext camel;

    /** Stands in for the {@code @HttpExchange} proxy; the producer resolves the method on the interface. */
    static final class FakeArtisan implements ArtisanClient {
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final AtomicReference<Exchange> exchangeDuringCall = new AtomicReference<>();

        @Override
        public ResponseEntity<List<MyAccountDetailsVO>> listUser(String userJson, Map<String, String> params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseEntity<MyAccountDetailsVO> createUser(String userJson) {
            lastBody.set(userJson);
            exchangeDuringCall.set(HttpExchangeContext.current());
            return ResponseEntity.status(201).body(new MyAccountDetailsVO("ACC-G-7002", "ethan", "WEB", 500.0));
        }

        @Override
        public ResponseEntity<MyAccountDetailsVO> getUser(String queryJson) {
            throw new UnsupportedOperationException();
        }
    }

    @AfterEach
    void stop() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersTheRequestCallsTheInterfaceAndMapsTheReply() throws Exception {
        FakeArtisan artisan = new FakeArtisan();
        camel = new DefaultCamelContext();
        camel.getRegistry().bind("fakeArtisan", artisan);
        camel.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:create")
                        .to("httpexchange:fakeArtisan/createUser"
                                + "?requestTemplate=tutorial/templates/artisan-request.ftl"
                                + "&responseTemplate=tutorial/templates/artisan-account-response.ftl"
                                + "&unmarshalTo=java.util.Map");
            }
        });
        camel.start();
        ProducerTemplate template = camel.createProducerTemplate();

        Exchange result = template.request("direct:create", e -> {
            e.getIn().setBody(Map.of("orderId", "G-7002", "channel", "WEB", "amount", 500));
            e.getIn().setHeader("userName", "ethan");
            e.getIn().setHeader("orderId", "G-7002");
        });

        // the request template's text went to the @RequestBody String parameter verbatim
        Map<String, Object> sent = new ObjectMapper().readValue(artisan.lastBody.get(), Map.class);
        assertThat(sent).containsEntry("userId", "G-7002").containsEntry("userName", "ethan")
                .containsEntry("channel", "WEB").containsEntry("amount", 500);
        // the Exchange was visible to header policies (ArtisanHeaders) during the call, and only then
        assertThat(artisan.exchangeDuringCall.get()).isNotNull();
        assertThat(HttpExchangeContext.current()).isNull();
        // ResponseEntity unwrapped: status as a header, the record rendered through the response template
        assertThat(result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(201);
        Map<String, Object> reply = result.getMessage().getBody(Map.class);
        assertThat(reply).containsEntry("code", "OK").containsEntry("orderId", "G-7002");
        assertThat((Map<String, Object>) reply.get("account"))
                .containsEntry("id", "ACC-G-7002").containsEntry("owner", "ethan").containsEntry("amount", 500);
    }
}
