package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvEndpoint;

import java.time.Instant;
import java.util.List;

/**
 * Read model for one endpoint: the stored row plus the two derived attributes the UI renders but
 * never stores — whether the row takes part in a host/ip/port conflict, and its (currently mocked)
 * health state.
 *
 * @param conflict     {@code true} when this endpoint shares a host:port or ip:port with another
 *                     endpoint inside the configured conflict scope
 * @param conflictKeys the colliding keys, e.g. {@code ["ip:port 10.20.0.7:8443"]} — shown in the
 *                     cell tooltip so the operator sees *why* the cell is highlighted
 * @param health       {@code UP} / {@code DOWN} / {@code DEGRADED} / {@code UNKNOWN}
 */
public record EndpointDto(
        Long id,
        String appSystem,
        String tier,
        String envInstance,
        String country,
        String service,
        String instance,
        String scheme,
        String host,
        String ip,
        Integer port,
        String note,
        String url,
        boolean conflict,
        List<String> conflictKeys,
        HealthState health,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static EndpointDto of(EnvEndpoint e, boolean conflict, List<String> conflictKeys, HealthState health) {
        return new EndpointDto(
                e.getId(), e.getAppSystem(), e.getTier(), e.getEnvInstance(), e.getCountry(),
                e.getService(), e.getInstance(), e.getScheme(), e.getHost(), e.getIp(), e.getPort(),
                e.getNote(),
                e.getScheme() + "://" + e.getHost() + ":" + e.getPort(),
                conflict, conflictKeys, health,
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
