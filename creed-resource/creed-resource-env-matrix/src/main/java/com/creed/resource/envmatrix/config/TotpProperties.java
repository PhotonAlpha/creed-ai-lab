package com.creed.resource.envmatrix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code env-matrix.totp.*} — the one-time password that gates the Splunk session broker.
 *
 * @param secret             the shared key, Base32 (RFC 4648) — what an authenticator app is
 *                           enrolled with. Blank ⇒ the broker answers 503 instead of accepting any
 *                           code, so a missing secret can never degrade into "no check at all".
 * @param periodSeconds      RFC 6238 time step, X. 30 is what every authenticator app assumes.
 * @param digits             code length, 6–8
 * @param allowedDriftSteps  steps accepted either side of now — 1 means ±30 s of clock skew
 * @param rejectReplay       refuse a step at or before the last accepted one (RFC 6238 §5.2). Kept
 *                           in memory, so it holds per instance, not across primary/secondary.
 * @param exposeCurrentCode  serve the current code at {@code /splunk/totp/current} so the Splunk page
 *                           can display it on rotation. <b>On by default, by request</b> — but anyone
 *                           who can read that route can pass the check, so with it on the OTP proves
 *                           only "was looking at the page". Set {@code false} to make the code come
 *                           from an authenticator app instead.
 * @param maxFailures        failed verifications per client address within {@code failureWindow}
 *                           before further attempts answer 429. A 6-digit code has 10^6 values and
 *                           three are valid at any moment; unthrottled, that is guessable.
 * @param failureWindow      sliding window for {@code maxFailures}
 */
@ConfigurationProperties("env-matrix.totp")
public record TotpProperties(
        @DefaultValue("") String secret,
        @DefaultValue("30") int periodSeconds,
        @DefaultValue("6") int digits,
        @DefaultValue("1") int allowedDriftSteps,
        @DefaultValue("true") boolean rejectReplay,
        @DefaultValue("true") boolean exposeCurrentCode,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("60s") Duration failureWindow) {

    public TotpProperties {
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("env-matrix.totp.period-seconds must be positive");
        }
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("env-matrix.totp.digits must be 6..8");
        }
        if (allowedDriftSteps < 0 || allowedDriftSteps > 5) {
            throw new IllegalArgumentException("env-matrix.totp.allowed-drift-steps must be 0..5");
        }
    }

    public boolean configured() {
        return secret != null && !secret.isBlank();
    }

    /** The secret is a credential — never let it reach a log line via the record's default toString. */
    @Override
    public String toString() {
        return "TotpProperties[secret=" + (configured() ? "***" : "<unset>")
                + ", periodSeconds=" + periodSeconds + ", digits=" + digits
                + ", allowedDriftSteps=" + allowedDriftSteps + ", rejectReplay=" + rejectReplay
                + ", exposeCurrentCode=" + exposeCurrentCode + ", maxFailures=" + maxFailures
                + ", failureWindow=" + failureWindow + "]";
    }
}
