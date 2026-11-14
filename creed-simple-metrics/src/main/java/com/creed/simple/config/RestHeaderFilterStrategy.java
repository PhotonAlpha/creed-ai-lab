package com.creed.simple.config;

import org.apache.camel.Exchange;
import org.apache.camel.http.common.HttpHeaderFilterStrategy;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The header filter of the REST consumer ({@code camel.component.servlet.header-filter-strategy}): which request
 * headers enter the exchange, and which exchange headers go back as response headers (asked for, 2026-11-14).
 *
 * <p><b>Out: an allowlist.</b> Camel's servlet binding writes every message header into the HTTP response, and the
 * message starts out holding every request header - so {@code Cookie}, {@code Authorization}, {@code X-Api-Key} came
 * back verbatim, with the routes' internal headers ({@code source}, {@code lane}, {@code branchError}...) and whatever
 * a downstream answered ({@code Set-Cookie: JSESSIONID} included). It also overflowed the response header buffer
 * ({@code HeadersTooLargeException}, see {@code docs/http-header-size.md}). Only the listed names go out now;
 * {@code Content-Type} needs no listing - the binding sets it on its own, before the headers.
 *
 * <p><b>In: a denylist.</b> Query parameters reach the exchange through the same filter as headers, and the routes read
 * many of them ({@code cluster}, {@code resilient}, {@code fail}...) - an allowlist would drop them silently. Denied:
 * hop-by-hop headers (meaningless past this hop, harmful if a route forwards them) and credentials no route uses.
 * {@code Cookie} stays - payment's sticky routing reads {@code stickyId} from it.
 *
 * <p>Camel's own rules stay underneath: {@code Camel*} / {@code org.apache.camel*} never cross either way.
 * Only the REST consumer uses it; the camel-http calls to the downstream services have their own component.
 */
public class RestHeaderFilterStrategy extends HttpHeaderFilterStrategy {

    private final Set<String> outboundAllowed;

    public RestHeaderFilterStrategy(Collection<String> inboundDenied, Collection<String> outboundAllowed) {
        // super() ran initialize(): Camel's lists, lower-case matching
        inboundDenied.stream().map(RestHeaderFilterStrategy::lower).filter(h -> !h.isEmpty()).forEach(getInFilter()::add);
        this.outboundAllowed = outboundAllowed.stream().map(RestHeaderFilterStrategy::lower).filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@code true} = not written to the response. A listed name goes out even when Camel's own outbound list would
     * stop it ({@code cache-control}, {@code date}, {@code pragma}... - meant for a proxy relaying a downstream's
     * answer): listing it is the decision. So never list {@code Content-Length} / {@code Transfer-Encoding} - the
     * container writes those.
     */
    @Override
    public boolean applyFilterToCamelHeaders(String headerName, Object headerValue, Exchange exchange) {
        if (headerName == null) {
            return true;
        }
        String name = lower(headerName);
        return !outboundAllowed.contains(name) || name.startsWith("camel") || name.startsWith("org.apache.camel");
    }

    public Set<String> outboundAllowed() {
        return outboundAllowed;
    }

    private static String lower(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
