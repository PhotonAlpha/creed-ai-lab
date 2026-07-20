package com.creed.simple.web;

import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Strategy;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Custom Logbook {@link Strategy} that gates <em>response-body</em> buffering on two independent axes,
 * complementing the request-side {@code requestCondition} gate (see {@link LogbookAuditConfiguration}):
 *
 * <ul>
 *   <li><b>Response content-type</b> — only buffer/log the response body when its {@code Content-Type}
 *       matches {@code allowedResponseContentTypes} (default {@code application/json}). This is the
 *       response counterpart the request condition cannot cover: {@code Logbook.condition()} is a
 *       {@code Predicate<HttpRequest>} evaluated <em>before the request is even sent</em>, so it has no
 *       response to inspect — the response content-type can only be seen here, in
 *       {@link #process(HttpRequest, HttpResponse)}.</li>
 *   <li><b>Error-only</b> (optional, {@code bodyOnError}) — additionally require the status to be
 *       {@code >= minimumStatus}, so the (larger) response body is only kept on failures.</li>
 * </ul>
 *
 * <p><b>Why this is a genuine buffering skip, not a suppressed log line.</b> Every built-in strategy
 * ({@code DefaultStrategy}/{@code StatusAtLeastStrategy}/{@code BodyOnlyIfStatusAtLeastStrategy}/
 * {@code WithoutBodyStrategy}) only overrides the {@code write(...)} methods, which run <em>after</em>
 * both bodies were already buffered by the default {@code process()} methods — they suppress output but
 * still pay the buffering cost. This strategy decides in {@link #process(HttpRequest, HttpResponse)},
 * which the {@link Strategy} contract guarantees runs <em>before</em> the response body is read. For the
 * classic (blocking) hc5 integration this module uses ({@code LogbookHttpExecHandler}) the status line
 * and headers — hence {@code getStatus()} and {@code getContentType()} — are already available at that
 * point even though the entity is not yet consumed, so both gates are reliable and a rejected body is
 * never buffered.
 *
 * <p><b>Asymmetry with the request gate, by design.</b> A rejected <em>request</em> content-type drops
 * the entire audit (nothing has been logged yet — cheapest possible skip). A rejected <em>response</em>
 * content-type only drops the response <em>body</em>: by the time the response type is known the request
 * has already been emitted, so the metadata line (status/headers) is still written — you keep the "a
 * call happened, here's its status" audit value, just without a non-JSON (HTML error page, binary) body.
 *
 * <p>The {@code write(...)} methods are intentionally left at their interface defaults (request emitted
 * at send time, response at receive time) rather than deferring both to the response as the built-in
 * status-gated strategies do: deferring would mean a request that never receives a response (timeout,
 * dropped connection) is never logged at all — the opposite of what an audit log wants. The request body
 * is always buffered ({@code process(HttpRequest)} default) since it is small JSON already admitted by
 * the request gate.
 */
public final class ContentAwareBodyStrategy implements Strategy {

    /** Lowercased allow-list; empty disables the content-type gate (status gate, if any, still applies). */
    private final List<String> allowedResponseContentTypes;
    private final boolean bodyOnError;
    private final int minimumStatus;

    public ContentAwareBodyStrategy(List<String> allowedResponseContentTypes, boolean bodyOnError, int minimumStatus) {
        this.allowedResponseContentTypes = allowedResponseContentTypes;
        this.bodyOnError = bodyOnError;
        this.minimumStatus = minimumStatus;
    }

    @Override
    public HttpResponse process(HttpRequest request, HttpResponse response) throws IOException {
        return shouldBufferResponseBody(response) ? response.withBody() : response.withoutBody();
    }

    private boolean shouldBufferResponseBody(HttpResponse response) {
        if (bodyOnError && response.getStatus() < minimumStatus) {
            return false;
        }
        return contentTypeAllowed(response.getContentType());
    }

    private boolean contentTypeAllowed(String contentType) {
        if (allowedResponseContentTypes.isEmpty()) {
            return true;
        }
        if (contentType == null || contentType.isBlank()) {
            return true; // no body / unknown type — nothing to suppress
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return allowedResponseContentTypes.stream().anyMatch(lower::startsWith);
    }
}
