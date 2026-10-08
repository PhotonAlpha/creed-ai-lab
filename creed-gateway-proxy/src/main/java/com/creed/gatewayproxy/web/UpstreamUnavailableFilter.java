package com.creed.gatewayproxy.web;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers "connection refused" from a target with a 502 that says what is wrong. The usual cause is
 * that the {@code ssh -R} session is not (or no longer) connected, so nothing listens on the tunnel
 * port — and the gateway's default answer is a bare 500 that reads like a bug in the proxy.
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
            String address = target == null ? "the target" : target.getHost() + ":" + target.getPort();
            log.warn("{} {} -> {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), address, e.getMessage());
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
            response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
            byte[] body = ("502 Bad Gateway: nothing is listening on " + address
                    + ". Is the ssh -R tunnel connected, and the local service running?\n").getBytes(StandardCharsets.UTF_8);
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
