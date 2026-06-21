package com.creed.partner.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Requirement 1: aggregate the catalog and order resource-server APIs through Spring Cloud
 * LoadBalancer. Each {@code lb://<service-id>/...} URL is resolved to a concrete, health-checked
 * instance via {@link LoadBalancerClient#choose(String)} (which consults the health-check supplier
 * from {@code PartnerLoadBalancerConfiguration}), then called over the audited {@code partnerRestClient}.
 *
 * <p>The scheme is taken from the chosen instance's TLS flag rather than the {@code lb} URL, so the
 * {@code lb} scheme never reaches the underlying JDK client.
 */
@RestController
@RequestMapping("/api/partner")
@Slf4j
public class PartnerAggregateController {

    private final RestClient partnerRestClient;
    private final LoadBalancerClient loadBalancerClient;
    private final String catalogUrl;
    private final String orderUrl;

    public PartnerAggregateController(
            RestClient partnerRestClient,
            LoadBalancerClient loadBalancerClient,
            @Value("${creed.partner.catalog-url:lb://catalog-resource/api/catalog/items}") String catalogUrl,
            @Value("${creed.partner.order-url:lb://order-resource/api/order/items}") String orderUrl) {
        this.partnerRestClient = partnerRestClient;
        this.loadBalancerClient = loadBalancerClient;
        this.catalogUrl = catalogUrl;
        this.orderUrl = orderUrl;
    }

    /** Aggregate catalog + order items into a single response. */
    @GetMapping("/aggregate")
    public Map<String, Object> aggregate() {
        log.info("aggregate catalog={} order={}", catalogUrl, orderUrl);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog", fetch(catalogUrl));
        body.put("orders", fetch(orderUrl));
        body.put("aggregatedBy", "creed-gateway-partner");
        return body;
    }

    /** Load-balanced passthrough to the catalog resource server. */
    @GetMapping("/catalog")
    public JsonNode catalog() {
        return fetch(catalogUrl);
    }

    /** Load-balanced passthrough to the order resource server. */
    @GetMapping("/order")
    public JsonNode order() {
        return fetch(orderUrl);
    }

    private JsonNode fetch(String lbUrl) {
        URI original = URI.create(lbUrl);
        String serviceId = original.getHost();
        ServiceInstance instance = loadBalancerClient.choose(serviceId);
        if (instance == null) {
            throw new IllegalStateException("No healthy instances available for service '" + serviceId + "'");
        }
        // Force the transport scheme from the instance's TLS flag; the lb:// scheme must not leak down.
        String scheme = instance.isSecure() ? "https" : "http";
        URI resolved = UriComponentsBuilder.fromUri(original)
                .scheme(scheme)
                .host(instance.getHost())
                .port(instance.getPort())
                .build(true)
                .toUri();
        log.info("lb://{} -> {}", serviceId, resolved);
        return partnerRestClient.get().uri(resolved).retrieve().body(JsonNode.class);
    }
}
