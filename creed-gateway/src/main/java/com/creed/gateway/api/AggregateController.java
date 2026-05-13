package com.creed.gateway.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/aggregate")
public class AggregateController {

    private final WebClient webClient;
    private final String catalogItemsUrl;
    private final String orderItemsUrl;

    public AggregateController(
            WebClient.Builder webClientBuilder,
            @Value("${creed.resource.catalog-url:http://127.0.0.1:8081/api/catalog/items}") String catalogItemsUrl,
            @Value("${creed.resource.order-url:http://127.0.0.1:8082/api/order/items}") String orderItemsUrl) {
        this.webClient = webClientBuilder.build();
        this.catalogItemsUrl = catalogItemsUrl;
        this.orderItemsUrl = orderItemsUrl;
    }

    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (JwtAuthenticationToken) ctx.getAuthentication())
                .map(JwtAuthenticationToken::getToken)
                .map(token -> token.getTokenValue())
                .flatMap(accessToken -> Mono.zip(
                                getJson(catalogItemsUrl, accessToken),
                                getJson(orderItemsUrl, accessToken))
                        .map(tuple -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("catalog", tuple.getT1());
                            body.put("orders", tuple.getT2());
                            body.put("aggregatedBy", "creed-gateway");
                            return body;
                        }));
    }

    private Mono<JsonNode> getJson(String url, String accessToken) {
        return webClient.get()
                .uri(url)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class);
    }
}
