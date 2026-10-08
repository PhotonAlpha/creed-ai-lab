package com.creed.gatewayproxy.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The proxy's routes — nginx's {@code location} + {@code proxy_pass}, matched in list order (put the
 * catch-all {@code /**} last).
 *
 * @param routes        at least one
 * @param healthTimeout TCP connect timeout of the {@code tunnels} health check, per route
 */
@ConfigurationProperties("creed.proxy")
public record ProxyProperties(List<Route> routes, Duration healthTimeout) {

    public ProxyProperties {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("creed.proxy.routes must name at least one route");
        }
        routes = List.copyOf(routes);
        healthTimeout = healthTimeout == null ? Duration.ofSeconds(1) : healthTimeout;
    }

    /**
     * @param id           route id, shown in logs and the health details
     * @param path         Spring path pattern, e.g. {@code /**} or {@code /grafana/**}
     * @param uri          where the tunnel lands on this server, e.g. {@code http://127.0.0.1:15173}
     * @param stripPrefix  leading path segments to drop before forwarding ({@code /grafana/x} → {@code /x} with 1)
     * @param preserveHost send the client's Host header instead of the target's. Off by default: Vite
     *                     (and most dev servers) reject a Host they do not know with 403, while
     *                     {@code 127.0.0.1:<port>} always passes.
     */
    public record Route(String id, String path, URI uri, int stripPrefix, boolean preserveHost) {

        public Route {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("creed.proxy.routes[].id is required");
            path = path == null || path.isBlank() ? "/**" : path;
            if (uri == null || uri.getHost() == null) {
                throw new IllegalArgumentException("creed.proxy.routes[" + id + "].uri needs a host, e.g. http://127.0.0.1:15173");
            }
            // http(s) only: the gateway upgrades to ws(s) by itself when a request asks for a WebSocket
            // (Vite's HMR does). lb:// would need a load balancer this module deliberately lacks.
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("creed.proxy.routes[" + id + "].uri must be http:// or https://, got " + uri);
            }
            if (stripPrefix < 0) throw new IllegalArgumentException("creed.proxy.routes[" + id + "].strip-prefix must be >= 0");
        }

        /** The port the tunnel listens on, defaulted from the scheme. */
        public int port() {
            if (uri.getPort() > 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
    }
}
