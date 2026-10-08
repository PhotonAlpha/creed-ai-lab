package com.creed.gatewayproxy.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.creed.gatewayproxy.config.ProxyProperties;

/**
 * {@code /actuator/health} component {@code tunnels}: a TCP connect to every route's target. With
 * {@code ssh -R} the server-side port exists only while the session does, so a refused connect means
 * the tunnel is down. An accepted one proves the tunnel only — the laptop's service behind it may
 * still be stopped, which the proxy then answers with a 502 per request.
 */
@Component("tunnels")
@RequiredArgsConstructor
public class TunnelHealthIndicator implements HealthIndicator {

    private final ProxyProperties properties;

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allUp = true;
        int timeout = (int) properties.healthTimeout().toMillis();
        for (ProxyProperties.Route route : properties.routes()) {
            String address = route.uri().getHost() + ":" + route.port();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(route.uri().getHost(), route.port()), timeout);
                details.put(route.id(), Map.of("target", address, "status", "UP"));
            } catch (IOException e) {
                allUp = false;
                details.put(route.id(), Map.of("target", address, "status", "DOWN", "error", String.valueOf(e.getMessage())));
            }
        }
        return (allUp ? Health.up() : Health.down()).withDetails(details).build();
    }
}
