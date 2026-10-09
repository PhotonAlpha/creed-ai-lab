package com.creed.gatewayproxy.api.dto;

import java.util.List;

/** The Splunk page's contract — the same JSON the Node BFF answered, plus {@code block} and {@code secure}. */
public final class SplunkDtos {

    private SplunkDtos() {
    }

    /** {@code GET /splunk/totp}. */
    public record TotpInfo(boolean configured, int periodSeconds, int digits, int allowedDriftSteps, long serverTimeMillis,
                           boolean codeVisible, String splunkMode, boolean splunkConfigured, List<PublicTarget> targets,
                           String defaultTarget, String scriptCookieName, BlockInfo block) {
    }

    /** What the page may know about a target — never the password, only whether one is set. */
    public record PublicTarget(String id, String label, String loginUrl, String username, boolean passwordSet,
                               boolean configured, String sessionCookie, String scriptCookieName, String scriptCookiePath,
                               String tunnel, boolean tunnelDefault) {
    }

    /**
     * The block windows and whether one is in force.
     *
     * @param changesAtMillis server epoch millis at which {@code blocked} flips; null when it never does
     */
    public record BlockInfo(List<String> windows, String zone, boolean blocked, Long changesAtMillis) {
    }

    /** {@code GET /splunk/totp/current}. */
    public record TotpCode(String code, long step, int secondsRemaining, int periodSeconds, long serverTimeMillis) {
    }

    /** {@code POST /splunk/session} body; anything omitted takes the target's setting. */
    public record SessionRequest(String code, String target, String sessionCookie, String scriptCookieName, Boolean viaTunnel) {
    }

    /**
     * {@code POST /splunk/session} answer.
     *
     * @param secure whether the script's cookie carries {@code Secure} — only for an https login URL:
     *               a browser drops a Secure cookie set from an http page
     */
    public record Session(String target, String sourceCookie, String cookieName, String cookieValue, String script,
                          boolean secure, String tunnel, String mode, String correlationId, String cookieFingerprint,
                          String issuedAt) {
    }
}
