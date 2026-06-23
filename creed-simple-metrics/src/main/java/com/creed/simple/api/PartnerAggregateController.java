package com.creed.simple.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Requirement 1: aggregate the catalog and order resource-server APIs through Spring Cloud
 * LoadBalancer. Each call targets an {@code https://<service-id>/...} URL where {@code <service-id>} is
 * a logical service name (not a host); the {@code @LoadBalanced} {@code partnerRestClient} resolves it
 * to a concrete, health-checked instance. Using the {@code https} scheme (instead of {@code lb}) means
 * the framework's URI reconstruct keeps a valid scheme, so no scheme-fixing transformer is needed.
 */
@RestController
@RequestMapping("/api/partner")
@Slf4j
public class PartnerAggregateController {

    private final RestClient partnerRestClient;
    private final String catalogUrl;
    private final String orderUrl;

    public PartnerAggregateController(
            RestClient partnerRestClient,
            @Value("${creed.partner.catalog-url:https://catalog-resource/api/catalog/items}") String catalogUrl,
            @Value("${creed.partner.order-url:https://order-resource/api/order/items}") String orderUrl) {
        this.partnerRestClient = partnerRestClient;
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
        return partnerRestClient.get().uri(lbUrl).retrieve().body(JsonNode.class);
    }
}
