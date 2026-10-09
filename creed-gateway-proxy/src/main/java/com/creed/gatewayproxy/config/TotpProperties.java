package com.creed.gatewayproxy.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one-time code that gates a Splunk session (RFC 6238, HMAC-SHA1). The secret is not a property:
 * it comes from {@code ENV_MATRIX_TOTP_SECRET} / {@code _FILE} or the startup prompt (SplunkSecrets),
 * never from a configuration file.
 *
 * @param periodSeconds     60 by request (RFC 6238's usual 30 is what authenticator apps assume — an
 *                          app reading the same secret would show different codes)
 * @param allowedDriftSteps steps either side of now still accepted, so a code lives (1 + 2 × drift) periods
 * @param exposeCurrentCode serve the current code to the page (on by request: the OTP is then decorative)
 */
@ConfigurationProperties("creed.totp")
public record TotpProperties(Integer periodSeconds, Integer digits, Integer allowedDriftSteps, Boolean rejectReplay,
                             Boolean exposeCurrentCode, Integer maxFailures, Duration failureWindow) {

    public TotpProperties {
        periodSeconds = check("period-seconds", periodSeconds, 60, 1, 3600);
        digits = check("digits", digits, 6, 6, 8);
        allowedDriftSteps = check("allowed-drift-steps", allowedDriftSteps, 1, 0, 5);
        rejectReplay = rejectReplay == null || rejectReplay;
        exposeCurrentCode = exposeCurrentCode == null || exposeCurrentCode;
        maxFailures = check("max-failures", maxFailures, 5, 1, 1000);
        failureWindow = failureWindow == null ? Duration.ofSeconds(60) : failureWindow;
        if (failureWindow.isNegative() || failureWindow.isZero()) {
            throw new IllegalArgumentException("creed.totp.failure-window must be positive");
        }
    }

    static int check(String name, Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        if (v < min || v > max) {
            throw new IllegalArgumentException("creed.totp." + name + " must be in " + min + ".." + max + ", got " + v);
        }
        return v;
    }
}
