package com.creed.partner.api;

import com.creed.partner.web.PartnerClusterProperties;
import com.creed.partner.web.PartnerClusterProperties.ClusterSpec;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Requirement 1: aggregate the configured resource-server APIs through Spring Cloud LoadBalancer. Each
 * cluster ({@code creed.partner.clusters.<name>}) is backed by a dynamically-registered, load-balanced,
 * audited {@code <name>RestClient} bean targeting {@code https://<service-id><path>}; the {@code @LoadBalanced}
 * client resolves the service-id to a concrete, health-checked instance. Using the {@code https} scheme
 * (instead of {@code lb}) keeps a valid scheme through the framework's URI reconstruct, so no scheme-fixing
 * transformer is needed.
 *
 * <p>Both endpoints are fully config-driven: the cluster list comes from {@link PartnerClusterProperties}
 * and the per-cluster clients are looked up by bean name ({@code <name>RestClient}) from the injected map —
 * adding a cluster to YAML makes it appear in {@code /aggregate} and reachable at {@code /{cluster}} with no
 * code change.
 */
@RestController
@RequestMapping("/api/partner")
@Slf4j
public class PartnerAggregateController {

    private final PartnerClusterProperties properties;
    /** All {@link RestClient} beans keyed by bean name; per cluster we use {@code <name>RestClient}. */
    private final Map<String, RestClient> restClients;

    public PartnerAggregateController(PartnerClusterProperties properties, Map<String, RestClient> restClients) {
        this.properties = properties;
        this.restClients = restClients;
    }

    /** Aggregate every configured cluster's items into a single response. */
    @GetMapping("/aggregate")
    public Map<String, Object> aggregate() {
        Map<String, Object> body = new LinkedHashMap<>();
        properties.clusters().forEach((name, spec) -> {
            log.info("aggregate cluster={} url={}", name, businessUrl(spec));
            body.put(name, fetch(name, spec));
        });
        body.put("aggregatedBy", "creed-gateway-partner");
        return body;
    }

    /** Load-balanced passthrough to a single named cluster (e.g. {@code /api/partner/catalog}). */
    @GetMapping("/{cluster}")
    public JsonNode cluster(@PathVariable("cluster") String name) {
        ClusterSpec spec = properties.clusters().get(name);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown cluster: " + name);
        }
        return fetch(name, spec);
    }

    private JsonNode fetch(String name, ClusterSpec spec) {
        return restClients.get(name + "RestClient").get().uri(businessUrl(spec)).retrieve().body(JsonNode.class);
    }

    private static String businessUrl(ClusterSpec spec) {
        return "https://" + spec.serviceId() + spec.path();
    }
}
