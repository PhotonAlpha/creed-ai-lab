package com.creed.resource.veracode.config;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.Logbook;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The Jira / Confluence client against a rate-limiting server: a short Retry-After is waited out, a long one is not. */
class BoundedRetryTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    private String rateLimited(String retryAfter) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/rest/api/2/search";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aLongRetryAfterFailsAtOnce() throws Exception {
        String url = rateLimited("600");
        long started = System.nanoTime();

        assertThat(get(url)).isEqualTo(429);

        // HttpClient's default would have slept ten minutes here, and then got 429 again
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(hits).hasValue(1);
    }

    @Test
    void aShortRetryAfterIsWaitedOutOnce() throws Exception {
        String url = rateLimited("1");

        assertThat(get(url)).isEqualTo(429);

        assertThat(hits).hasValue(2);
    }

    private static int get(String url) throws Exception {
        try (PoolingHttpClientConnectionManager pool = RemoteClientsConfiguration.connectionManager(Duration.ofSeconds(2), 2);
             CloseableHttpClient client = RemoteClientsConfiguration.httpClient(pool, Duration.ofSeconds(5),
                     Duration.ofSeconds(5), Logbook.create())) {
            return client.execute(new HttpGet(url), response -> response.getCode());
        }
    }
}
