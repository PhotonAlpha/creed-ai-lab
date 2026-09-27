package com.creed.resource.envmatrix.service.splunk;

import com.creed.resource.envmatrix.config.SplunkProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the client against a loopback stand-in for Splunk Web — no real Splunk is contacted.
 * The stub answers the way Splunk 7+ does: a {@code cval} cookie on GET, the session cookie on a
 * 303 to the POST that echoes it.
 */
class RestClientSplunkLoginClientTest {

    HttpServer server;
    final List<String> posts = new CopyOnWriteArrayList<>();
    final List<String> postCookies = new CopyOnWriteArrayList<>();
    volatile boolean followedRedirect;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/en-US/account/login", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Set-Cookie", "cval=424242; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "session_id_8000=abc; Path=/; HttpOnly");
                exchange.sendResponseHeaders(200, -1);
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                posts.add(body);
                postCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
                if (body.contains("password=s3cr%26t") && body.contains("cval=424242")) {
                    exchange.getResponseHeaders().add("Set-Cookie", "splunkd_8000=SESSION%5Evalue; Path=/; Secure; HttpOnly");
                    exchange.getResponseHeaders().add("Location", "/en-US/app/launcher/home");
                    exchange.sendResponseHeaders(303, -1);
                } else {
                    exchange.sendResponseHeaders(401, -1);
                }
            }
            exchange.close();
        });
        server.createContext("/en-US/app/launcher/home", exchange -> {
            followedRedirect = true;
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    SplunkLoginClient client(String password, boolean prefetchCval) {
        return client(true, password, prefetchCval);
    }

    SplunkLoginClient client(boolean enabled, String password, boolean prefetchCval) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/en-US/account/login";
        return new RestClientSplunkLoginClient(new SplunkProperties(enabled, url, "svc-splunk", password,
                "splunkd_8000", "splunkd_8089", "/", prefetchCval,
                Duration.ofSeconds(2), Duration.ofSeconds(5), ""), RestClient.builder(), null);
    }

    @Test
    @DisplayName("switch off (the default): a mock value, and nothing is sent to Splunk")
    void disabledReturnsMock() {
        SplunkLoginClient client = client(false, "s3cr&t", true);

        SplunkLoginClient.Result result = client.login();

        assertThat(client.mode()).isEqualTo("mock");
        assertThat(result.cookieValue()).startsWith("mock-").hasSize(5 + 64);
        assertThat(result.httpStatus()).isZero();
        assertThat(posts).isEmpty();
    }

    @Test
    @DisplayName("posts the form with cval, reads splunkd_8000 off the 303, and does not follow it")
    void login() {
        SplunkLoginClient.Result result = client("s3cr&t", true).login();

        assertThat(result.cookieValue()).isEqualTo("SESSION%5Evalue");
        assertThat(result.httpStatus()).isEqualTo(303);
        assertThat(followedRedirect).isFalse();
        assertThat(posts).singleElement().satisfies(body -> assertThat(body)
                .isEqualTo("username=svc-splunk&password=s3cr%26t&cval=424242"));
        assertThat(postCookies).singleElement().isEqualTo("cval=424242; session_id_8000=abc");
    }

    @Test
    @DisplayName("a response without the session cookie is a failure that carries the status")
    void wrongPassword() {
        assertThatThrownBy(() -> client("nope", true).login())
                .isInstanceOfSatisfying(SplunkLoginClient.SplunkLoginException.class, e -> {
                    assertThat(e.reason()).isEqualTo("no_session_cookie");
                    assertThat(e.httpStatus()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("without the cval prefetch, Splunk 7+ style login is rejected")
    void withoutCval() {
        assertThatThrownBy(() -> client("s3cr&t", false).login())
                .isInstanceOf(SplunkLoginClient.SplunkLoginException.class);
        assertThat(posts).singleElement().satisfies(body -> assertThat(body).doesNotContain("cval"));
    }

    @Test
    @DisplayName("an unreachable Splunk is io_error, not an unhandled exception")
    void unreachable() {
        server.stop(0);
        assertThatThrownBy(() -> client("s3cr&t", true).login())
                .isInstanceOfSatisfying(SplunkLoginClient.SplunkLoginException.class,
                        e -> assertThat(e.reason()).isEqualTo("io_error"));
    }
}
