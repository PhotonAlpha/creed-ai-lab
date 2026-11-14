package com.creed.simple.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.Optional;

/**
 * Warns at startup when the response header limit leaves no room for what the request may bring in.
 *
 * <p>Camel's servlet binding writes the exchange's headers back into the HTTP response, so every request header
 * comes back (plus whatever a downstream answered). A request Tomcat accepts under
 * {@code server.max-http-request-header-size} then fails while its response is written:
 * {@code HeadersTooLargeException: ... than there was room available in the buffer. Increase maxHttpHeaderSize ...}
 * - an HTTP 500 with a 5-byte body, the request already processed. The message names Tomcat's legacy
 * {@code maxHttpHeaderSize}; raising only the request limit (the obvious reading) makes every request between the two
 * limits fail that way instead of a clean 400 (measured 2026-11-14, see {@code docs/http-header-size.md}).
 */
@Component
@Slf4j
public class HttpHeaderSizeCheck {

    /** The response's own headers on top of the echo: ~200 B measured, a downstream's headers can add more. */
    static final DataSize HEADROOM = DataSize.ofKilobytes(2);

    public HttpHeaderSizeCheck(@Value("${server.max-http-request-header-size:8KB}") DataSize request,
                               @Value("${server.tomcat.max-http-response-header-size:8KB}") DataSize response) {
        problem(request, response).ifPresentOrElse(log::warn,
                () -> log.info("HTTP header limits: request {}, response {}", request, response));
    }

    static Optional<String> problem(DataSize request, DataSize response) {
        if (response.toBytes() >= request.toBytes() + HEADROOM.toBytes()) {
            return Optional.empty();
        }
        return Optional.of("server.tomcat.max-http-response-header-size (" + response + ") leaves less than " + HEADROOM
                + " above server.max-http-request-header-size (" + request + "): the Camel REST routes echo request "
                + "headers into the response, so an accepted request can fail with HeadersTooLargeException (HTTP 500, "
                + "'no room available in the buffer') - raise the response limit with the request limit (2x)");
    }
}
