package com.creed.gatewayproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.creed.gatewayproxy.service.TunnelHealthIndicator;

/**
 * The tunnel's server end is just a loopback port, so a JDK HttpServer stands in for
 * "ssh -R + the laptop's Vite": the stub echoes the path and Host it received. A second route points
 * at a port nothing listens on — a tunnel whose ssh session is gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ProxyRoutingTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final int DEAD_PORT = freePort();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String up = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("creed.proxy.routes[0].id", () -> "dead");
        registry.add("creed.proxy.routes[0].path", () -> "/dead/**");
        registry.add("creed.proxy.routes[0].uri", () -> "http://127.0.0.1:" + DEAD_PORT);
        registry.add("creed.proxy.routes[1].id", () -> "stripped");
        registry.add("creed.proxy.routes[1].path", () -> "/grafana/**");
        registry.add("creed.proxy.routes[1].uri", () -> up);
        registry.add("creed.proxy.routes[1].strip-prefix", () -> "1");
        registry.add("creed.proxy.routes[2].id", () -> "vite");
        registry.add("creed.proxy.routes[2].path", () -> "/**");
        registry.add("creed.proxy.routes[2].uri", () -> up);
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    TunnelHealthIndicator tunnels;

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void catchAllForwardsPathAndQueryWithTheTargetsHost() {
        String body = client().get().uri("/src/main.tsx?t=1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        // Host is the target's, not localhost:<proxy port> — what keeps Vite's allowedHosts check happy.
        // No X-Forwarded-*: gateway 4.3 adds them only for clients matching trusted-proxies, unset here.
        assertThat(body).contains("path=/src/main.tsx?t=1")
                .contains("host=127.0.0.1:" + UPSTREAM.getAddress().getPort())
                .contains("x-forwarded-host=null");
    }

    @Test
    void stripPrefixDropsTheLeadingSegment() {
        client().get().uri("/grafana/d/abc").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("path=/d/abc"));
    }

    @Test
    void deadTunnelIsA502ThatSaysSo() {
        client().get().uri("/dead/x").exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody(String.class).value(b -> assertThat(b)
                        .contains("127.0.0.1:" + DEAD_PORT).contains("ssh -R tunnel"));
    }

    @Test
    void healthIsDownWhileAnyTunnelIs() {
        var health = tunnels.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKeys("dead", "stripped", "vite");
        assertThat(health.getDetails().get("vite").toString()).contains("status=UP");
        assertThat(health.getDetails().get("dead").toString()).contains("status=DOWN");
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = ("path=" + exchange.getRequestURI()
                        + "\nhost=" + exchange.getRequestHeaders().getFirst("Host")
                        + "\nx-forwarded-host=" + exchange.getRequestHeaders().getFirst("X-Forwarded-Host"))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
