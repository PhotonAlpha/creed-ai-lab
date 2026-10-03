package com.creed.resource.envmatrix.api.dto;

/**
 * What the Splunk page needs to draw its countdown without a round trip per second.
 *
 * @param serverTimeMillis the server's clock when this was built — the page offsets its own clock by
 *                         the difference, since the countdown must follow the verifier, not the browser
 * @param configured       false ⇒ no secret is set and every submission will answer 503
 * @param codeVisible      whether {@code GET /splunk/totp/current} serves the code
 * @param splunkMode       {@code real} or {@code mock}
 * @param splunkConfigured false ⇒ login URL / username / password missing
 * @param scriptCookieName the cookie name the copyable script sets
 */
public record TotpInfoDto(
        boolean configured,
        int periodSeconds,
        int digits,
        int allowedDriftSteps,
        long serverTimeMillis,
        boolean codeVisible,
        String splunkMode,
        boolean splunkConfigured,
        String loginUrl,
        String scriptCookieName) {
}
