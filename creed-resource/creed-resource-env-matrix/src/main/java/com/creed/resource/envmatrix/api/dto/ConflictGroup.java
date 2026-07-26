package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * A set of endpoints that collide on the same address inside one conflict scope.
 *
 * @param kind      {@code HOST_PORT} or {@code IP_PORT} — which key collided
 * @param scopeKey  the scope the collision was found in, e.g. {@code UAT/UAT1}; see
 *                  {@link ConflictScope}
 * @param value     the colliding value, e.g. {@code 10.20.0.7:8443}
 * @param endpoints the colliding endpoints, at least two
 */
public record ConflictGroup(
        Kind kind,
        String scopeKey,
        String value,
        List<EndpointDto> endpoints) {

    public enum Kind {
        /** Two endpoints resolve to the same {@code host:port}. */
        HOST_PORT,
        /** Two endpoints resolve to the same {@code ip:port} — often two DNS names, one address. */
        IP_PORT
    }

    /** Human-readable key used in tooltips, e.g. {@code "ip:port 10.20.0.7:8443"}. */
    public String label() {
        return (kind == Kind.HOST_PORT ? "host:port " : "ip:port ") + value;
    }
}
