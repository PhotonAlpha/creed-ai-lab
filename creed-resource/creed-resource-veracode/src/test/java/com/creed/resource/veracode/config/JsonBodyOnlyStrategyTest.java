package com.creed.resource.veracode.config;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.core.DefaultHttpLogFormatter;
import org.zalando.logbook.core.DefaultSink;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The Jira / Confluence HTTP client with the module's Logbook: JSON bodies are logged, a downloaded zip is not. */
class JsonBodyOnlyStrategyTest {

    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0, 0, 0, 'b', 'i', 'n', 'a', 'r', 'y'};

    private HttpServer server;
    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/json", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"issues\":[\"CREED-1\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/download", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, ZIP.length);
            exchange.getResponseBody().write(ZIP);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void jsonBodiesAreLogged() throws Exception {
        HttpPost search = new HttpPost(url("/json"));
        search.setEntity(new StringEntity("{\"jql\":\"project = CREED\"}", ContentType.APPLICATION_JSON));

        String answer = send(search);

        assertThat(answer).isEqualTo("{\"issues\":[\"CREED-1\"]}");
        assertThat(logged).hasSize(2);
        assertThat(logged.get(0)).contains("POST " + url("/json")).contains("{\"jql\":\"project = CREED\"}");
        assertThat(logged.get(1)).contains("200 OK").contains("{\"issues\":[\"CREED-1\"]}");
    }

    @Test
    void aBinaryDownloadIsLoggedWithoutItsBody() throws Exception {
        String answer = send(new HttpGet(url("/download")));

        // the caller still gets every byte: the body was left out of the log, not out of the response
        assertThat(answer.getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(ZIP);
        assertThat(logged).hasSize(2);
        // the exchange is still there (the JDK server spells it Content-type), its bytes are not
        assertThat(logged.get(1)).contains("200 OK").containsIgnoringCase("Content-Type: application/zip")
                .doesNotContain("binary").doesNotContain("PK");
    }

    @Test
    void aMultipartUploadIsLoggedWithoutItsFiles() throws Exception {
        HttpPost attach = new HttpPost(url("/json"));
        attach.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("file", ZIP, ContentType.create("application/zip"), "reports.zip").build());

        send(attach);

        // Logbook sits before HttpClient's protocol step, so the request has no Content-Type header yet: its type comes
        // from the entity, and Logbook writes its placeholder for the body it was not given
        assertThat(logged.get(0)).contains("POST " + url("/json")).contains("<multipart>")
                .doesNotContain("reports.zip").doesNotContain("binary");
        // the answer is JSON: logged
        assertThat(logged.get(1)).contains("{\"issues\":[\"CREED-1\"]}");
    }

    private String send(ClassicHttpRequest request) throws Exception {
        Logbook logbook = Logbook.builder()
                .strategy(new JsonBodyOnlyStrategy(List.of("application/json")))
                .sink(new DefaultSink(new DefaultHttpLogFormatter(), new HttpLogWriter() {
                    @Override
                    public void write(Precorrelation precorrelation, String request) {
                        logged.add(request);
                    }

                    @Override
                    public void write(Correlation correlation, String response) {
                        logged.add(response);
                    }
                }))
                .build();
        try (PoolingHttpClientConnectionManager pool = RemoteClientsConfiguration.connectionManager(Duration.ofSeconds(2), 2);
             CloseableHttpClient client = RemoteClientsConfiguration.httpClient(pool, Duration.ofSeconds(5),
                     Duration.ofSeconds(5), logbook)) {
            return client.execute(request, response -> new String(EntityUtils.toByteArray(response.getEntity()),
                    StandardCharsets.ISO_8859_1));
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
