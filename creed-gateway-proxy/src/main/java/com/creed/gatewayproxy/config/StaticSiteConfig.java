package com.creed.gatewayproxy.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import org.springframework.web.reactive.resource.ResourceResolverChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Serves the built frontend — {@code npm run build}'s {@code dist/} copied into
 * {@code src/main/resources/static/} before packaging (or any {@code creed.proxy.static-locations}).
 *
 * <ul>
 *   <li>{@code /assets/**} is fingerprinted by Vite: cached for a year, immutable.</li>
 *   <li>Everything else is revalidated ({@code no-cache}), so a new index.html picks up new assets.</li>
 *   <li>SPA fallback: a path with no file extension that matches no file is a client-side route
 *       ({@code /aes}, {@code /splunk}) and gets {@code index.html}. Never under {@code api/}.</li>
 * </ul>
 *
 * Lowest priority of all handlers: the Splunk controller and the gateway routes (e.g. {@code /api/**},
 * or the tunnel profile's catch-all) are matched first. Boot's own static mapping is off
 * ({@code spring.web.resources.add-mappings=false}) so it cannot shadow these rules.
 */
@Configuration(proxyBeanMethods = false)
public class StaticSiteConfig implements WebFluxConfigurer {

    private final String[] locations;

    public StaticSiteConfig(@Value("${creed.proxy.static-locations:classpath:/static/}") String[] locations) {
        this.locations = Arrays.stream(locations).map(String::strip).map(l -> l.endsWith("/") ? l : l + "/").toArray(String[]::new);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(Arrays.stream(locations).map(l -> l + "assets/").toArray(String[]::new))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(locations)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    /**
     * {@code GET /} → {@code /index.html}. The resource handler drops an empty path before any
     * resolver sees it, so the root never reaches the SPA fallback. Only when a built frontend is
     * present: under the tunnel profile with no static files, {@code /} must reach the tunnel as is.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter rootToIndexFilter() {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        boolean present = Arrays.stream(locations).anyMatch(l -> loader.getResource(l + "index.html").exists());
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            if (present && "/".equals(request.getPath().value())
                    && (request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.HEAD)) {
                return chain.filter(exchange.mutate().request(request.mutate().path("/index.html").build()).build());
            }
            return chain.filter(exchange);
        };
    }

    static class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Mono<Resource> resolveResourceInternal(ServerWebExchange exchange, String requestPath,
                                                         List<? extends Resource> locations, ResourceResolverChain chain) {
            return super.resolveResourceInternal(exchange, requestPath, locations, chain)
                    .switchIfEmpty(Mono.defer(() -> isClientRoute(requestPath)
                            ? super.resolveResourceInternal(exchange, "index.html", locations, chain)
                            : Mono.empty()));
        }

        /** {@code splunk} or {@code config/releases} — not {@code favicon.ico}, not {@code api/…}. */
        static boolean isClientRoute(String path) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            return !path.startsWith("api/") && !path.equals("api") && !last.contains(".");
        }
    }
}
