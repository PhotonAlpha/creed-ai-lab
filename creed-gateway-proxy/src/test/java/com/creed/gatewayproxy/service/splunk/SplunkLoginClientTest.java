package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * Loopback Splunk stubs: GET sets {@code cval}; POST answers 303 with {@code splunkd_8000} only when
 * cval came back as cookie and form field — the real Splunk Web 7+ behaviour. HTTPS uses a
 * self-signed certificate for "localhost" made with the JDK's keytool, so no network and no PKI.
 */
class SplunkLoginClientTest {

    record Seen(String method, String host, String cookie, String body) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws java.io.IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestHeaders().getFirst("Host"), cookie, body));
        if ("GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Set-Cookie", "cval=777; Path=/");
            ex.sendResponseHeaders(200, -1);
        } else if (body.contains("password=s3cr%26t") && body.contains("cval=777") && "cval=777".equals(cookie)) {
            ex.getResponseHeaders().add("Location", "/en-US/app/launcher/home");
            ex.getResponseHeaders().add("Set-Cookie", "splunkd_8000=\"abc123\"; Path=/; HttpOnly");
            ex.sendResponseHeaders(303, -1);
        } else {
            ex.sendResponseHeaders(200, -1);
        }
        ex.close();
    }

    private int startHttp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    private int startHttps(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystore.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ctx));
        https.createContext("/", this::handle);
        https.start();
        server = https;
        return https.getAddress().getPort();
    }

    /** keytool from the running JDK: a "localhost" certificate plus its PEM, for ca-file. */
    private static Path[] selfSigned(Path dir) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Path ks = dir.resolve("splunk.p12");
        Path pem = dir.resolve("splunk.pem");
        run(keytool, "-genkeypair", "-alias", "splunk", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit");
        run(keytool, "-exportcert", "-rfc", "-alias", "splunk", "-keystore", ks.toString(), "-storepass", "changeit",
                "-file", pem.toString());
        return new Path[]{ks, pem};
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + "\n" + out);
    }

    private static SplunkLoginClient client(boolean insecure, String caFile, SplunkProperties.Target target) {
        return new SplunkLoginClient(SplunkTestSupport.splunk(true, insecure, caFile, "", List.of(target)));
    }

    @Test
    void echoesCvalAndReadsTheCookieOffThe303WithoutFollowingIt() throws Exception {
        int port = startHttp();
        SplunkProperties.Target target = SplunkTestSupport.target("SIT", "http://127.0.0.1:" + port + "/en-US/account/login", null);
        SplunkLoginClient.LoginResult result = client(true, null, target).login(target, "s3cr&t", "splunkd_8000", false);
        assertThat(result.cookieValue()).isEqualTo("abc123");
        assertThat(result.httpStatus()).isEqualTo(303);
        assertThat(seen).extracting(Seen::method).containsExactly("GET", "POST"); // the redirect was not followed
        assertThat(seen.get(1).body()).isEqualTo("username=admin&password=s3cr%26t&cval=777");
    }

    @Test
    void wrongPasswordIsNoSessionCookie() throws Exception {
        int port = startHttp();
        SplunkProperties.Target target = SplunkTestSupport.target("SIT", "http://127.0.0.1:" + port + "/", null);
        assertThatThrownBy(() -> client(true, null, target).login(target, "nope", "splunkd_8000", false))
                .isInstanceOfSatisfying(SplunkLoginClient.SplunkLoginException.class, e -> {
                    assertThat(e.reason()).isEqualTo("no_session_cookie");
                    assertThat(e.httpStatus()).isEqualTo(200);
                });
    }

    @Test
    void tunnelMovesOnlyTheSocket() throws Exception {
        int port = startHttp();
        // The URL names a host that does not resolve; the tunnel is where the socket really goes.
        SplunkProperties.Target target = SplunkTestSupport.target("UAT", "http://splunk-uat.invalid:3000/en-US/account/login",
                "127.0.0.1:" + port);
        assertThat(client(true, null, target).login(target, "s3cr&t", "splunkd_8000", true).cookieValue()).isEqualTo("abc123");
        assertThat(seen).extracting(Seen::host).containsOnly("splunk-uat.invalid:3000");
    }

    @Test
    void unreachableIsIoError() {
        SplunkProperties.Target target = SplunkTestSupport.target("SIT", "http://127.0.0.1:1/", null);
        assertThatThrownBy(() -> client(true, null, target).login(target, "x", "splunkd_8000", false))
                .isInstanceOfSatisfying(SplunkLoginClient.SplunkLoginException.class, e -> {
                    assertThat(e.reason()).isEqualTo("io_error");
                    assertThat(e.getMessage()).startsWith("could not reach Splunk at http://127.0.0.1:1/");
                });
    }

    @Test
    void httpsSelfSignedPassesOnlyWhenInsecureOrTrusted(@TempDir Path dir) throws Exception {
        Path[] cert = selfSigned(dir);
        int port = startHttps(cert[0]);
        SplunkProperties.Target direct = SplunkTestSupport.target("SIT", "https://localhost:" + port + "/en-US/account/login", null);

        assertThat(client(true, null, direct).login(direct, "s3cr&t", "splunkd_8000", false).cookieValue()).isEqualTo("abc123");
        assertThatThrownBy(() -> client(false, null, direct).login(direct, "s3cr&t", "splunkd_8000", false))
                .isInstanceOfSatisfying(SplunkLoginClient.SplunkLoginException.class, e -> assertThat(e.reason()).isEqualTo("io_error"));
        assertThat(client(false, cert[1].toString(), direct).login(direct, "s3cr&t", "splunkd_8000", false).cookieValue())
                .isEqualTo("abc123");

        // Verified, through a tunnel at 127.0.0.1: the certificate names "localhost", and so does the
        // SNI — the tunnel's address never enters the host-name check.
        SplunkProperties.Target viaTunnel = SplunkTestSupport.target("UAT", "https://localhost:1/en-US/account/login",
                "127.0.0.1:" + port);
        assertThat(client(false, cert[1].toString(), viaTunnel).login(viaTunnel, "s3cr&t", "splunkd_8000", true).cookieValue())
                .isEqualTo("abc123");
        // A URL host the certificate does not name fails the check even though the socket is the same.
        SplunkProperties.Target wrongName = SplunkTestSupport.target("UAT", "https://splunk-uat.invalid:1/", "127.0.0.1:" + port);
        assertThatThrownBy(() -> client(false, cert[1].toString(), wrongName).login(wrongName, "s3cr&t", "splunkd_8000", true))
                .isInstanceOf(SplunkLoginClient.SplunkLoginException.class);
    }

    @Test
    void cookieParsingTakesTheLastAndUnquotes() {
        assertThat(SplunkLoginClient.cookiesOf(List.of("a=1; Path=/", "b=\"x y\"", "a=2", "=bad", "noeq")))
                .containsExactly(java.util.Map.entry("a", "2"), java.util.Map.entry("b", "x y"));
    }
}
