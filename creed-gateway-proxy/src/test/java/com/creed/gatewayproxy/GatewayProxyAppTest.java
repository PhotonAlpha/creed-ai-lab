package com.creed.gatewayproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The deployable shape end to end: Splunk API answered here (mock Splunk, no block window), the
 * built frontend from classpath:/static/ (src/test/resources/static), SPA fallback, and /api/**
 * proxied to a backend that is down — a JSON 502.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "creed.splunk.block.windows=", "creed.secrets.prompt=false"})
class GatewayProxyAppTest {

    private static final int DEAD_PORT = freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // A list binds from one source as a whole, so the route is restated, not just its uri.
        registry.add("creed.proxy.routes[0].id", () -> "env-matrix-api");
        registry.add("creed.proxy.routes[0].path", () -> "/api/**");
        registry.add("creed.proxy.routes[0].uri", () -> "http://127.0.0.1:" + DEAD_PORT);
    }

    @LocalServerPort
    int port;

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void totpInfoHasSixtySecondStepsTargetsAndBlock() {
        client().get().uri("/api/env-matrix/splunk/totp").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.periodSeconds").isEqualTo(60)
                .jsonPath("$.configured").isEqualTo(true)
                .jsonPath("$.splunkMode").isEqualTo("mock")
                .jsonPath("$.targets[0].id").isEqualTo("default")
                .jsonPath("$.targets[0].passwordSet").isEqualTo(false)
                .jsonPath("$.targets[0].configured").isEqualTo(true)
                .jsonPath("$.block.blocked").isEqualTo(false)
                .jsonPath("$.block.windows.length()").isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionFlowThenReplayThenAudit() {
        Map<String, Object> code = client().get().uri("/api/env-matrix/splunk/totp/current").exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody(Map.class).returnResult().getResponseBody();
        String value = (String) code.get("code");
        client().post().uri("/api/env-matrix/splunk/session").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", value)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mode").isEqualTo("mock")
                .jsonPath("$.secure").isEqualTo(true)   // the default login URL is https
                .jsonPath("$.script").value(s -> assertThat((String) s).contains("; Secure; SameSite=Lax"));
        client().post().uri("/api/env-matrix/splunk/session").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", value)).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("otp_replayed");
        client().get().uri("/api/env-matrix/splunk/audit?limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].eventType").isEqualTo("OTP_VERIFY")
                .jsonPath("$[0].reason").isEqualTo("replayed")
                .jsonPath("$[1].eventType").isEqualTo("SPLUNK_LOGIN")
                .jsonPath("$[1].cookieFingerprint").value(f -> assertThat((String) f).hasSize(16));
    }

    @Test
    void malformedRequestsAre400s() {
        client().post().uri("/api/env-matrix/splunk/session").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "12ab")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.fields[0].field").isEqualTo("code");
        client().post().uri("/api/env-matrix/splunk/session").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("validation_failed");
    }

    @Test
    void servesTheFrontendWithSpaFallbackAndCacheRules() {
        client().get().uri("/").exchange().expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noCache())
                .expectBody(String.class).value(b -> assertThat(b).contains("spa-shell"));
        client().get().uri("/splunk").exchange().expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("spa-shell"));
        client().get().uri("/config/releases").exchange().expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("spa-shell"));
        client().get().uri("/assets/app-abc123.js").exchange().expectStatus().isOk()
                .expectHeader().value("Cache-Control", v -> assertThat(v).contains("max-age=31536000", "immutable"));
        client().get().uri("/missing.js").exchange().expectStatus().isNotFound();
    }

    @Test
    void restOfApiGoesToTheBackendAndADeadOneIsAJson502() {
        client().get().uri("/api/env-matrix/aes/records/page").exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("bad_gateway")
                .jsonPath("$.message").value(m -> assertThat((String) m).contains("127.0.0.1:" + DEAD_PORT, "env-matrix-api"));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
