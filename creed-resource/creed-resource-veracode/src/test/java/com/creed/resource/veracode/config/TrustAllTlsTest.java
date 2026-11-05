package com.creed.resource.veracode.config;

import com.sun.net.httpserver.HttpsConfigurator;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.zalando.logbook.Logbook;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A server with a self-signed certificate issued to another name: HttpClient's defaults refuse it, the Jira
 * client's (the same pool and client the bean uses) accepts it. The keystore is made by the JDK's own keytool -
 * no PKI.
 */
class TrustAllTlsTest {

    @TempDir
    Path dir;

    private HttpsServer server;

    @BeforeEach
    void start() throws Exception {
        Path keystore = dir.resolve("other.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=jira.not-this-host.example", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        assertThat(keytool.waitFor()).as(new String(keytool.getInputStream().readAllBytes())).isZero();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        // the address the server is bound to: "localhost" may resolve to ::1 first
        return "https://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void acceptsAnUntrustedCertificateForAnotherName() throws Exception {
        try (PoolingHttpClientConnectionManager pool = RemoteClientsConfiguration.connectionManager(Duration.ofSeconds(5), 2);
             CloseableHttpClient client = RemoteClientsConfiguration.httpClient(pool, Duration.ofSeconds(5), Logbook.create())) {
            String body = client.execute(new HttpGet(url()), response -> EntityUtils.toString(response.getEntity()));
            assertThat(body).isEqualTo("ok");
        }
    }

    @Test
    void httpClientsDefaultWouldNot() throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            assertThatThrownBy(() -> client.execute(new HttpGet(url()), response -> null))
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }
}
