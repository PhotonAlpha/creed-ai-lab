package com.creed.resource.veracode.config;

import com.sun.net.httpserver.HttpsConfigurator;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A server with a self-signed certificate issued to another name: the JDK default refuses it on both
 * counts, the Jira client's context accepts it. The keystore is made by the JDK's own keytool - no PKI.
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

    private HttpResponse<String> get(SSLContext context) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().sslContext(context).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + server.getAddress().getPort() + "/"))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void acceptsAnUntrustedCertificateForAnotherHost() throws Exception {
        assertThat(get(TrustAllTls.context()).body()).isEqualTo("ok");
    }

    @Test
    void theJdkDefaultWouldNot() {
        assertThatThrownBy(() -> get(SSLContext.getDefault())).isInstanceOf(SSLHandshakeException.class);
    }
}
