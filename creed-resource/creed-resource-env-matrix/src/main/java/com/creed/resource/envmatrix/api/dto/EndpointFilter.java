package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Multi-select filter over the endpoint dimensions. Every field is optional; {@code null} or an
 * empty list leaves that dimension unconstrained.
 *
 * <p>Bound from repeated query parameters, e.g.
 * {@code ?tier=UAT&tier=SIT&scheme=https} — which is what lets the UI's {@code Select mode="multiple"}
 * filters map straight onto the query string.
 */
public record EndpointFilter(
        List<String> appSystem,
        List<String> tier,
        List<String> envInstance,
        List<String> country,
        List<String> service,
        List<String> instance,
        List<String> scheme,
        String keyword) {

    /** Unconstrained filter — matches every endpoint. */
    public static EndpointFilter none() {
        return new EndpointFilter(null, null, null, null, null, null, null, null);
    }
}
