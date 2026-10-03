package com.creed.resource.envmatrix.service.splunk;

import com.creed.resource.envmatrix.config.SplunkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLContext;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Splunk Web form login over Spring's {@link RestClient}: {@code POST <loginUrl>} with
 * {@code username=…&password=…}, then the session cookie ({@code splunkd_8000} by default) read off
 * the response's {@code Set-Cookie}.
 *
 * <p><b>{@code env-matrix.splunk.enabled} is the switch.</b> Off (the default), {@link #login()}
 * returns a fabricated value and sends nothing, so the page and the audit trail work with no Splunk
 * to reach. On, it makes the two calls below.
 *
 * <p>Two details decide whether the real call works at all:
 * <ul>
 *   <li><b>Redirects are never followed.</b> A successful login may answer 303 with the cookie on
 *       that response; following it would hand back the next page's headers and lose the cookie. The
 *       request factory wraps a JDK client built with {@code Redirect.NEVER} for exactly this.</li>
 *   <li><b>{@code cval} is fetched first</b> ({@code prefetch-cval}). Splunk Web 7+ sets a
 *       {@code cval} cookie on the login page and rejects a POST that does not echo it — as both a
 *       cookie and a form field — answering 200 without the session cookie.</li>
 * </ul>
 *
 * <p>Both calls use {@code exchange}, not {@code retrieve}: a 401 or 3xx is an answer to inspect,
 * not an exception, and {@code retrieve} would throw on the 401 before its headers could be read.
 */
@Slf4j
public class RestClientSplunkLoginClient implements SplunkLoginClient {

    private final SplunkProperties properties;
    private final RestClient restClient;
    private final SecureRandom random = new SecureRandom();

    public RestClientSplunkLoginClient(SplunkProperties properties, RestClient.Builder builder, SSLContext sslContext) {
        this.properties = properties;
        HttpClient.Builder http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout());
        if (sslContext != null) {
            http.sslContext(sslContext);
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http.build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    @Override
    public String mode() {
        return properties.mode();
    }

    @Override
    public Result login() {
        if (!properties.enabled()) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return new Result("mock-" + HexFormat.of().formatHex(bytes), 0);
        }

        URI loginUri = URI.create(properties.loginUrl());
        Map<String, String> cookies = new LinkedHashMap<>();
        if (properties.prefetchCval()) {
            Reply page = call(() -> restClient.get().uri(loginUri).exchange((request, response) ->
                    new Reply(response.getStatusCode().value(), cookiesOf(response.getHeaders()))), loginUri);
            cookies.putAll(page.cookies());
            log.debug("splunk login page answered {} with cookies {}", page.status(), cookies.keySet());
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", properties.username());
        form.add("password", properties.password());
        if (cookies.containsKey("cval")) {
            form.add("cval", cookies.get("cval"));
        }

        Reply reply = call(() -> restClient.post().uri(loginUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (!cookies.isEmpty()) {
                        headers.add(HttpHeaders.COOKIE, cookies.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining("; ")));
                    }
                })
                .body(form)
                .exchange((request, response) ->
                        new Reply(response.getStatusCode().value(), cookiesOf(response.getHeaders()))), loginUri);

        String value = reply.cookies().get(properties.sessionCookie());
        if (value == null || value.isBlank()) {
            // 401 is a bad password; 200 without the cookie is usually a cval/CSRF rejection.
            throw new SplunkLoginException("no_session_cookie", reply.status(),
                    "Splunk answered " + reply.status() + " without a " + properties.sessionCookie() + " cookie", null);
        }
        return new Result(value, reply.status());
    }

    private static Reply call(Supplier<Reply> exchange, URI uri) {
        try {
            return exchange.get();
        } catch (RestClientException e) {
            throw new SplunkLoginException("io_error", 0,
                    "could not reach Splunk at " + uri + ": " + e.getMessage(), e);
        }
    }

    /** Every {@code Set-Cookie} on a response, name → value. A later header wins, as a browser's would. */
    static Map<String, String> cookiesOf(HttpHeaders headers) {
        Map<String, String> cookies = new LinkedHashMap<>();
        List<String> values = headers.getOrEmpty(HttpHeaders.SET_COOKIE);
        for (String header : values) {
            try {
                for (HttpCookie cookie : HttpCookie.parse(header)) {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            } catch (IllegalArgumentException e) {
                log.debug("ignoring unparseable Set-Cookie header: {}", e.getMessage());
            }
        }
        return cookies;
    }

    private record Reply(int status, Map<String, String> cookies) {
    }
}
