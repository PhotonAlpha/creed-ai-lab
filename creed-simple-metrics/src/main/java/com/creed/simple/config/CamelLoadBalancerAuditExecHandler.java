package com.creed.simple.config;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpEntityContainer;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * camel-http counterpart of {@link com.creed.simple.web.AuditLoggingFilter}, at the downstream hop:
 * an Apache HttpClient 5 exec-chain interceptor that emits one contiguous audit block per outbound
 * call — request/response headers, cookies and (textual) bodies, plus the <em>concrete instance</em>
 * the {@link com.creed.simple.lb.LoadBalancerRoutePlanner} resolved and the per-attempt latency.
 *
 * <p>HttpClient consults the route planner <em>before</em> the exec chain runs, so
 * {@code scope.route.getTargetHost()} is already the chosen instance's {@code host:port} — the
 * endpoint URI Camel sees (and {@code CamelSendTimingEventNotifier} logs) still carries the logical
 * service-id. Installed via {@code addExecInterceptorLast} (innermost, inside the retry exec), so it
 * audits each actual network attempt.
 *
 * <h2>Why entities are buffered and replaced</h2>
 * Like the servlet streams in {@code AuditLoggingFilter}, an {@link HttpEntity} is a one-shot
 * stream: reading it for logging would leave the actual consumer (the wire / Camel / RestClient) an
 * empty body. Non-repeatable entities are therefore drained into a byte array and replaced on the
 * message with a repeatable {@link ByteArrayEntity} <em>before</em> the bytes are used for logging
 * — see {@link #bufferedBodyOf} for why draining uses {@code writeTo}, not {@code getContent()}.
 */
public class CamelLoadBalancerAuditExecHandler implements ExecChainHandler {

    private static final Logger log = LoggerFactory.getLogger(CamelLoadBalancerAuditExecHandler.class);

    /** Bodies larger than this (in bytes) are truncated in the log — protects against large feeds. */
    private static final int MAX_PAYLOAD_BYTES = 4_096;

    /** Header values never printed verbatim (credentials). Cookies ARE printed on purpose. */
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "proxy-authorization");

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        HttpHost target = scope.route.getTargetHost();
        // Buffer BEFORE proceed: the wire write consumes a non-repeatable request entity.
        byte[] requestBody = bufferedBodyOf(request);
        long startNanos = System.nanoTime();
        try {
            ClassicHttpResponse response = chain.proceed(request, scope);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            byte[] responseBody = bufferedBodyOf(response);
            try {
                log.info("\n{}", render(target, request, requestBody, response, responseBody, ms));
            } catch (RuntimeException ex) {
                // Auditing must never break the call — log and hand the response back untouched.
                log.warn("downstream audit rendering failed: {}", ex.toString());
            }
            return response;
        } catch (IOException | HttpException | RuntimeException ex) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("\n{}", renderFailure(target, request, requestBody, ex, ms));
            throw ex;
        }
    }

    /**
     * Returns the message's body bytes, replacing a non-repeatable entity with a buffered copy so the
     * actual consumer (the wire / Camel) still gets the full content afterwards.
     *
     * <p>Draining always goes through {@link HttpEntity#writeTo}, never {@code getContent()}
     * ({@code BufferedHttpEntity}/{@code EntityUtils}): Spring's RestClient request entity
     * ({@code HttpComponentsClientHttpRequest.BodyEntity}) is a write-only entity that reports
     * {@code isRepeatable()=true} yet throws {@code UnsupportedOperationException} from
     * {@code getContent()}; {@code writeTo} is honoured by every entity implementation.
     */
    private static byte[] bufferedBodyOf(HttpEntityContainer message) throws IOException {
        HttpEntity entity = message.getEntity();
        if (entity == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        entity.writeTo(buffer);
        byte[] bytes = buffer.toByteArray();
        message.setEntity(new ByteArrayEntity(bytes, contentTypeObjectOf(entity),
                entity.getContentEncoding(), entity.isChunked()));
        try {
            entity.close();
        } catch (IOException | RuntimeException ignored) {
            // draining via writeTo already consumed the stream; close is best-effort cleanup
        }
        return bytes;
    }

    private static ContentType contentTypeObjectOf(HttpEntity entity) {
        try {
            return entity.getContentType() != null ? ContentType.parse(entity.getContentType()) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String render(HttpHost target, ClassicHttpRequest request, byte[] requestBody,
                          ClassicHttpResponse response, byte[] responseBody, long ms) {
        StringBuilder sb = new StringBuilder(512);
        appendRequestPart(sb, target, request, requestBody);

        sb.append("--- RESPONSE (status=").append(response.getCode()).append(", ").append(ms).append("ms) ---\n");
        appendResponseHeaders(sb, response);
        appendResponseCookies(sb, response);
        appendBody(sb, responseBody, contentTypeOf(response));

        sb.append("==========================================================");
        return sb.toString();
    }

    private String renderFailure(HttpHost target, ClassicHttpRequest request, byte[] requestBody,
                                 Exception failure, long ms) {
        StringBuilder sb = new StringBuilder(512);
        appendRequestPart(sb, target, request, requestBody);
        sb.append("--- RESPONSE FAILED (").append(ms).append("ms): ").append(failure).append(" ---\n");
        sb.append("==========================================================");
        return sb.toString();
    }

    private void appendRequestPart(StringBuilder sb, HttpHost target, ClassicHttpRequest request,
                                   byte[] requestBody) {
        sb.append("========== AUDIT-DOWNSTREAM ").append(request.getMethod()).append(' ')
                .append(target.toURI()).append(request.getRequestUri())
                .append(" ==========\n");
        sb.append("--- REQUEST ---\n");
        appendHeaders(sb, request.getHeaders(), null);
        appendRequestCookies(sb, request);
        appendBody(sb, requestBody, contentTypeOf(request));
    }

    /** Prints all headers, masking credentials; {@code skipHeader} (e.g. Set-Cookie) is rendered elsewhere. */
    private void appendHeaders(StringBuilder sb, Header[] headers, String skipHeader) {
        sb.append("  headers:\n");
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(skipHeader)) {
                continue;
            }
            sb.append("    ").append(header.getName()).append(": ")
                    .append(mask(header.getName(), header.getValue())).append('\n');
        }
    }

    private void appendResponseHeaders(StringBuilder sb, ClassicHttpResponse response) {
        // Set-Cookie is rendered separately below; skip it here to avoid duplication.
        appendHeaders(sb, response.getHeaders(), HttpHeaders.SET_COOKIE);
    }

    private void appendRequestCookies(StringBuilder sb, ClassicHttpRequest request) {
        Header[] cookieHeaders = request.getHeaders("Cookie");
        if (cookieHeaders.length == 0) {
            return;
        }
        sb.append("  cookies:\n");
        for (Header header : cookieHeaders) {
            for (String pair : header.getValue().split(";\\s*")) {
                sb.append("    ").append(pair).append('\n');
            }
        }
    }

    private void appendResponseCookies(StringBuilder sb, ClassicHttpResponse response) {
        Header[] setCookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        if (setCookies.length == 0) {
            return;
        }
        sb.append("  set-cookies:\n");
        for (Header header : setCookies) {
            sb.append("    ").append(header.getValue()).append('\n');
        }
    }

    /** Prints the body only when it is present and looks textual; truncates to {@link #MAX_PAYLOAD_BYTES}. */
    private void appendBody(StringBuilder sb, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            return;
        }
        if (!isTextual(contentType)) {
            sb.append("  body   : <").append(content.length).append(" bytes, ")
                    .append(contentType == null ? "unknown" : contentType).append(" — not logged>\n");
            return;
        }
        int shown = Math.min(content.length, MAX_PAYLOAD_BYTES);
        String text = new String(content, 0, shown, charsetOf(contentType));
        sb.append("  body   : ").append(text);
        if (content.length > shown) {
            sb.append(" …(truncated, ").append(content.length).append(" bytes total)");
        }
        sb.append('\n');
    }

    private static String contentTypeOf(HttpEntityContainer message) {
        HttpEntity entity = message.getEntity();
        return entity != null ? entity.getContentType() : null;
    }

    private static String mask(String headerName, String value) {
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT)) ? "****" : value;
    }

    private static boolean isTextual(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            String subtype = type.getSubtype().toLowerCase(Locale.ROOT);
            return "text".equalsIgnoreCase(type.getType())
                    || "json".equals(subtype) || subtype.endsWith("+json")
                    || "xml".equals(subtype) || subtype.endsWith("+xml")
                    || "x-www-form-urlencoded".equals(subtype);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Charset charsetOf(String contentType) {
        try {
            Charset cs = MediaType.parseMediaType(contentType).getCharset();
            return cs != null ? cs : StandardCharsets.UTF_8;
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }
}
