package com.creed.gateway.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

/**
 * Requirement 5: aggregate catalog and order data, fetched from the two resource servers through
 * Spring Cloud LoadBalancer ({@code lb://} service IDs) over HTTPS.
 *
 * <p>Each {@code lb://<service-id>/...} URL is resolved to a concrete instance URL explicitly via
 * {@link ReactiveLoadBalancer.Factory} (the same factory the gateway routes use), then called over
 * the SSL-bundle-trusted {@code resourceWebClient}. Resolving explicitly — rather than relying on a
 * load-balancer {@code ExchangeFilterFunction} — keeps the {@code lb} scheme from leaking into the
 * Netty connector and composes deterministically with the custom SSL connector.
 */
@RestController
@RequestMapping("/api/aggregate")
public class AggregateController {

    private final WebClient webClient;
    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;
    private final String catalogItemsUrl;
    private final String orderItemsUrl;

    public AggregateController(
            WebClient resourceWebClient,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory,
            @Value("${creed.resource.catalog-url:lb://catalog-resource/api/catalog/items}") String catalogItemsUrl,
            @Value("${creed.resource.order-url:lb://order-resource/api/order/items}") String orderItemsUrl) {
        this.webClient = resourceWebClient;
        this.loadBalancerFactory = loadBalancerFactory;
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

    private Mono<JsonNode> getJson(String lbUrl, String accessToken) {
        URI original = URI.create(lbUrl);
        String serviceId = original.getHost();
        ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerFactory.getInstance(serviceId);
        if (loadBalancer == null) {
            return Mono.error(new IllegalStateException(
                    "No LoadBalancer configured for service '" + serviceId + "'"));
        }
        return Mono.from(loadBalancer.choose())
                .flatMap(response -> {
                    if (!response.hasServer()) {
                        return Mono.error(new IllegalStateException(
                                "No instances available for service '" + serviceId + "'"));
                    }
                    ServiceInstance instance = response.getServer();
                    // Force the concrete transport scheme from the instance's TLS flag. The
                    // SimpleDiscoveryClient instance carries no scheme, so a naive reconstruct would
                    // keep the original "lb" scheme and leak it to the Netty connector.
                    String scheme = instance.isSecure() ? "https" : "http";
                    URI resolved = UriComponentsBuilder.fromUri(original)
                            .scheme(scheme)
                            .host(instance.getHost())
                            .port(instance.getPort())
                            .build(true)
                            .toUri();
                    return webClient.get()
                            .uri(resolved)
                            .headers(h -> h.setBearerAuth(accessToken))
                            .retrieve()
                            .bodyToMono(JsonNode.class);
                });
    }
}
