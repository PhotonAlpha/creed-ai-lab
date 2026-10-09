package com.creed.gatewayproxy.service.splunk;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * Splunk Web form login — {@code POST <loginUrl>} with {@code username=…&password=…}, the session
 * cookie read off the response's {@code Set-Cookie}. A port of the Node broker's login-client.js;
 * the same three properties decide whether the real call works at all:
 *
 * <ul>
 *   <li><b>Redirects are never followed.</b> A successful login may answer 303 with the cookie on that
 *       response; following it would lose the cookie. (Reactor Netty does not follow by default.)</li>
 *   <li><b>{@code cval} is fetched and echoed</b> ({@code prefetch-cval}) — as cookie and form field.
 *       Splunk Web 7+ rejects a POST without it with a 200 and no session cookie, which looks exactly
 *       like a wrong password.</li>
 *   <li><b>TLS verification is switched off for this client only</b> ({@code tls-insecure}, on by
 *       request), never process-wide.</li>
 * </ul>
 *
 * <b>Tunnel</b>: the socket goes to the target's {@code tunnel} instead of the URL's host:port, while
 * the URL, Host header and TLS SNI stay the real host's (curl {@code --connect-to}), so Splunk sees a
 * request for itself and a verified certificate is checked against the real name.
 *
 * Blocking on purpose: the broker runs on boundedElastic, and two sequential requests read clearer
 * than a chain.
 */
public class SplunkLoginClient {

    public record LoginResult(String cookieValue, int httpStatus) {
    }

    /** A login that failed on Splunk's side; {@code reason} goes into the audit trail. */
    public static class SplunkLoginException extends RuntimeException {
        private final String reason;
        private final int httpStatus;

        public SplunkLoginException(String reason, int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.httpStatus = httpStatus;
        }

        public String reason() {
            return reason;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private record Reply(int status, Map<String, String> cookies) {
    }

    private final SplunkProperties config;
    private final SslContext sslContext;

    public SplunkLoginClient(SplunkProperties config) {
        this.config = config;
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (config.tlsInsecure()) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else if (config.caFile() != null) {
                builder.trustManager(new File(config.caFile()));
            }
            this.sslContext = builder.build();
        } catch (SSLException e) {
            throw new IllegalStateException("cannot build the Splunk TLS context: " + e.getMessage(), e);
        }
    }

    public String mode() {
        return config.enabled() ? "real" : "mock";
    }

    /**
     * @param sessionCookie the cookie to read off the login response
     * @return the session cookie value — a credential, never log it
     */
    public LoginResult login(SplunkProperties.Target target, String password, String sessionCookie, boolean viaTunnel) {
        if (!config.enabled()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return new LoginResult("mock-" + HexFormat.of().formatHex(random), 0);
        }
        // The broker rejects viaTunnel on a target without one; this is the last line, not the check.
        if (viaTunnel && target.tunnel() == null) throw new IllegalStateException("target " + target.id() + " has no tunnel");
        SplunkProperties.HostPort tunnel = viaTunnel ? target.tunnelAddress() : null;
        URI url = URI.create(target.loginUrl());

        Map<String, String> cookies = new LinkedHashMap<>();
        if (config.prefetchCval()) cookies.putAll(call(url, null, Map.of(), tunnel).cookies());

        StringBuilder form = new StringBuilder()
                .append("username=").append(enc(target.username()))
                .append("&password=").append(enc(password));
        if (cookies.containsKey("cval")) form.append("&cval=").append(enc(cookies.get("cval")));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (!cookies.isEmpty()) {
            headers.put("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        }
        Reply reply = call(url, form.toString(), headers, tunnel);
        String value = reply.cookies().get(sessionCookie);
        if (value == null || value.isEmpty()) {
            // 401 is a bad password; 200 without the cookie is usually a cval/CSRF rejection.
            throw new SplunkLoginException("no_session_cookie", reply.status(),
                    "Splunk answered " + reply.status() + " without a " + sessionCookie + " cookie", null);
        }
        return new LoginResult(value, reply.status());
    }

    /** GET when {@code body} is null, else POST. */
    private Reply call(URI url, String body, Map<String, String> headers, SplunkProperties.HostPort tunnel) {
        try {
            HttpClient client = client(url, tunnel).headers(h -> headers.forEach(h::set));
            // Reactor Netty takes the socket address from an absolute URI and ignores remoteAddress(),
            // so through a tunnel the request is sent as a path, with the real Host header set here.
            String uri = url.toString();
            if (tunnel != null) {
                String path = (url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath())
                        + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
                String host = url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
                client = client.headers(h -> h.set(HttpHeaderNames.HOST, host));
                uri = path;
            }
            HttpClient.ResponseReceiver<?> request = body == null
                    ? client.get().uri(uri)
                    : client.post().uri(uri).send(ByteBufFlux.fromString(Mono.just(body), StandardCharsets.UTF_8,
                    io.netty.buffer.ByteBufAllocator.DEFAULT));
            return request.responseSingle((res, content) -> {
                Reply reply = new Reply(res.status().code(), cookiesOf(res.responseHeaders().getAll(HttpHeaderNames.SET_COOKIE)));
                return content.asByteArray().then(Mono.just(reply)); // drain: only status and headers matter
            }).block(config.connectTimeout().plus(config.readTimeout()).plusSeconds(1));
        } catch (RuntimeException e) {
            String via = tunnel == null ? "" : " via tunnel " + tunnel;
            throw new SplunkLoginException("io_error", 0, "could not reach Splunk at " + url + via + ": " + describe(e), e);
        }
    }

    private HttpClient client(URI url, SplunkProperties.HostPort tunnel) {
        // A fresh connection per request: two logins are minutes apart, and a pooled socket would make
        // the connect timeout meaningless.
        HttpClient client = HttpClient.create(ConnectionProvider.newConnection())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.connectTimeout().toMillis())
                .responseTimeout(config.readTimeout())
                .followRedirect(false);
        if (tunnel != null) {
            // Only the socket moves: the Host header (call) and SNI below keep the real host.
            client = client.remoteAddress(() -> InetSocketAddress.createUnresolved(tunnel.host(), tunnel.port()));
        }
        if ("https".equalsIgnoreCase(url.getScheme())) {
            String realHost = url.getHost().replaceAll("^\\[(.*)]$", "$1");
            boolean insecure = config.tlsInsecure();
            client = client.secure(spec -> spec.sslContext(sslContext).handlerConfigurator(handler -> {
                SSLEngine engine = handler.engine();
                SSLParameters params = engine.getSSLParameters();
                // Off with tls-insecure (the chain is not checked either); otherwise the host name is
                // checked — against the SNI name, i.e. the real host even through a tunnel.
                params.setEndpointIdentificationAlgorithm(insecure ? null : "HTTPS");
                if (!isIpLiteral(realHost)) params.setServerNames(List.of(new SNIHostName(realHost)));
                engine.setSSLParameters(params);
            }));
        }
        return client;
    }

    /** Every {@code Set-Cookie}, name → value; a later header wins, as a browser's would. */
    static Map<String, String> cookiesOf(List<String> setCookie) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : setCookie) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            cookies.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip().replaceAll("^\"(.*)\"$", "$1"));
        }
        return cookies;
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.matches("^[0-9.]+$");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == e ? message : message + " (" + e.getClass().getSimpleName() + ")";
    }
}
