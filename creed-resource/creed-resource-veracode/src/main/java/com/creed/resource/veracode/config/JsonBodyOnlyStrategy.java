package com.creed.resource.veracode.config;

import org.zalando.logbook.HttpMessage;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Strategy;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Logbook logs a body only when its {@code Content-Type} is on the list ({@code veracode.logbook.allowed-content-types},
 * {@code application/json} by default) - as creed-simple-metrics' {@code ContentAwareBodyStrategy}: a Confluence
 * attachment download (a zip, the report XML) and the multipart uploads attaching files to Jira / Confluence were
 * printed byte for byte (2026-11-13).
 *
 * <p>Decided in {@code process}, which runs before the body is read, so a body left out is never buffered either - the
 * built-in strategies only suppress the write, after buffering. The exchange itself is still logged (method, URL,
 * status, headers): unlike simple-metrics' request gate, which drops a non-JSON request entirely, a failed attachment
 * upload must stay visible. No {@code Content-Type} (no body, a redirect) passes - there is nothing to leave out.
 */
public final class JsonBodyOnlyStrategy implements Strategy {

    /** Lower-cased, matched as prefixes ({@code application/json;charset=UTF-8}); empty = every body. */
    private final List<String> allowed;

    public JsonBodyOnlyStrategy(List<String> allowedContentTypes) {
        this.allowed = allowedContentTypes.stream().map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty()).toList();
    }

    @Override
    public HttpRequest process(HttpRequest request) throws IOException {
        return logsBody(request) ? request.withBody() : request.withoutBody();
    }

    @Override
    public HttpResponse process(HttpRequest request, HttpResponse response) throws IOException {
        return logsBody(response) ? response.withBody() : response.withoutBody();
    }

    boolean logsBody(HttpMessage message) {
        String type = message.getContentType();
        if (allowed.isEmpty() || type == null || type.isBlank()) {
            return true;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(lower::startsWith);
    }
}
