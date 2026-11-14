package com.creed.simple.web.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module's header limits, as {@code application.yml} sets them, on a real Tomcat - without the PKI or Camel: the
 * servlet below echoes every request header into the response, as Camel's servlet binding did before
 * {@code RestHeaderFilterStrategy} (the limits stay a margin should headers reach the response again).
 */
@SpringBootTest(classes = HttpHeaderSizeTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpHeaderSizeTest {

    @LocalServerPort
    int port;

    @Value("${server.max-http-request-header-size}")
    DataSize requestLimit;

    @Value("${server.tomcat.max-http-response-header-size}")
    DataSize responseLimit;

    /**
     * The case seen in production: a request header about as large as the request limit lets through (the request
     * line and the client's other headers take the rest), echoed back. With the response limit equal to the request
     * limit (Tomcat's default 8KB each) this is the 500.
     */
    @Test
    void anAcceptedRequestsHeadersFitWhenEchoed() throws Exception {
        int edge = (int) requestLimit.toBytes() - 150;
        HttpResponse<String> echoed = send(header(edge), 0);

        assertThat(echoed.statusCode()).isEqualTo(200);
        assertThat(echoed.headers().firstValue("X-Trace-Note")).hasValueSatisfying(v -> assertThat(v).hasSize(edge));
    }

    @Test
    void aRequestOverTheRequestLimitIsA400() throws Exception {
        assertThat(send(header((int) requestLimit.toBytes() + 100), 0).statusCode()).isEqualTo(400);
    }

    /** What "no room available in the buffer" looks like to the client: a 500, nothing of the response. */
    @Test
    void aResponseOverTheResponseLimitIsA500() throws Exception {
        assertThat(send("x", (int) responseLimit.toBytes() + 100).statusCode()).isEqualTo(500);
    }

    @Test
    void theConfiguredLimitsLeaveRoomForTheEcho() {
        assertThat(HttpHeaderSizeCheck.problem(requestLimit, responseLimit)).isEmpty();
        assertThat(HttpHeaderSizeCheck.problem(DataSize.ofKilobytes(16), DataSize.ofKilobytes(8)))
                .hasValueSatisfying(m -> assertThat(m).contains("HeadersTooLargeException"));
    }

    private HttpResponse<String> send(String note, int extraResponseBytes) throws Exception {
        // HTTP/1.1: the default (h2c upgrade) adds headers of its own, which would be echoed too
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple/echo?extra=" + extraResponseBytes))
                    .header("X-Trace-Note", note).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String header(int bytes) {
        return "x".repeat(bytes);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class App {

        /** Every request header back into the response (as Camel does), plus {@code extra} bytes of its own. */
        @Bean
        ServletRegistrationBean<HttpServlet> echo() {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    for (String name : Collections.list(req.getHeaderNames())) {
                        resp.setHeader(name, req.getHeader(name));
                    }
                    int extra = Integer.parseInt(req.getParameter("extra"));
                    if (extra > 0) {
                        resp.setHeader("X-Extra", "y".repeat(extra));
                    }
                    resp.getWriter().write("ok");
                }
            }, "/echo");
        }
    }
}
