package com.creed.gatewayproxy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the gateway routes from {@link ProxyProperties} rather than
 * {@code spring.cloud.gateway.server.webflux.routes}: one flat list of path → target is all this
 * module needs, and a typo in it fails startup instead of producing a route that never matches.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ProxyRouteConfig {

    @Bean
    RouteLocator proxyRoutes(RouteLocatorBuilder builder, ProxyProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (int i = 0; i < properties.routes().size(); i++) {
            ProxyProperties.Route route = properties.routes().get(i);
            int order = i; // list order is match order, like nginx's prefix locations written longest first
            routes.route(route.id(), spec -> spec.order(order)
                    .path(route.path())
                    .filters(f -> {
                        if (route.stripPrefix() > 0) f.stripPrefix(route.stripPrefix());
                        if (route.preserveHost()) f.preserveHostHeader();
                        return f;
                    })
                    .uri(route.uri()));
            log.info("proxy route {}: {} -> {} (strip-prefix {}, preserve-host {})",
                    route.id(), route.path(), route.uri(), route.stripPrefix(), route.preserveHost());
        }
        return routes.build();
    }
}
