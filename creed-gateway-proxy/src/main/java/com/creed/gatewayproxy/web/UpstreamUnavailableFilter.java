package com.creed.gatewayproxy.web;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers "connection refused" from a route's target with a 502 that says what is wrong — the
 * env-matrix backend is down, or an {@code ssh -R} session is gone so nothing listens on its port.
 * The gateway's default is a bare 500 that reads like a bug in the proxy. Under {@code /api/} the
 * body is the {@code {error, message}} JSON the frontend's client unwraps; elsewhere plain text.
 */
@Slf4j
@Component
public class UpstreamUnavailableFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(UpstreamUnavailableFilter::isConnectFailure, e -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) return Mono.error(e);
            URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            String address = target == null ? "the target" : target.getHost() + ":" + target.getPort();
            String routeId = route == null ? "?" : route.getId();
            log.warn("{} {} -> {} (route {}): {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), address, routeId, e.getMessage());
            String message = "nothing is listening on " + address + " (route " + routeId
                    + ") — is that service running, or, for an ssh -R tunnel, is the session connected?";
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
            byte[] body;
            if (exchange.getRequest().getPath().value().startsWith("/api/")) {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                body = ("{\"error\":\"bad_gateway\",\"message\":\"" + message.replace("\"", "\\\"") + "\",\"time\":\""
                        + Instant.now() + "\"}").getBytes(StandardCharsets.UTF_8);
            } else {
                response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
                body = ("502 Bad Gateway: " + message + "\n").getBytes(StandardCharsets.UTF_8);
            }
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }

    static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException) return true;
        }
        return false;
    }

    /** Outermost, so it sees the routing filter's error. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
