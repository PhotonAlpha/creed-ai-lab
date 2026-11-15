package com.creed.gatewayproxy.api.dto;

import java.util.List;

/** The Splunk page's contract — the same JSON the Node BFF answered, plus {@code block} and {@code secure}. */
public final class SplunkDtos {

    private SplunkDtos() {
    }

    /** {@code GET /splunk/totp}. */
    public record TotpInfo(boolean configured, int periodSeconds, int digits, int allowedDriftSteps, long serverTimeMillis,
                           boolean codeVisible, String splunkMode, boolean splunkConfigured, List<PublicTarget> targets,
                           String defaultTarget, String scriptCookieName, BlockInfo block, ClientInfo client) {
    }

    /**
     * The caller as this server sees it: the TCP peer (a proxy in front shows as the proxy) and its
     * forward-confirmed reverse-DNS name, null when there is none.
     */
    public record ClientInfo(String ip, String host) {
    }

    /** What the page may know about a target — never the password, only whether one is set. */
    public record PublicTarget(String id, String label, String loginUrl, String username, boolean passwordSet,
                               boolean configured, String sessionCookie, String scriptCookieName, String scriptCookiePath,
                               String tunnel, boolean tunnelDefault, BlockInfo block) {
    }

    /**
     * The block windows and whether one is in force — broker-wide in {@code TotpInfo.block}, and the
     * ones that actually apply in each {@code PublicTarget.block}. A target's {@code blocked} also covers
     * being outside its allowed windows ({@code reason = not_available}); those windows are not listed.
     *
     * @param changesAtMillis server epoch millis at which {@code blocked} flips; null when it never does
     */
    public record BlockInfo(List<String> windows, String zone, boolean blocked, Long changesAtMillis, String reason) {
    }

    /**
     * Admin API: a target's allowed windows ({@code /admin/targets/{id}/availability}). Never sent to the page.
     *
     * @param windows   the spec as stored ({@code ""}: unrestricted)
     * @param source    {@code config} or {@code api}
     * @param available whether the target issues a session right now (allowed and not blocked)
     */
    public record Availability(String target, String windows, String zone, String source, String updatedAt,
                               String updatedBy, boolean available, String reason, Long changesAtMillis) {
    }

    /** Admin API: {@code PUT …/availability} body. */
    public record AvailabilityUpdate(String windows) {
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

    /**
     * {@code POST /splunk/session/{correlationId}/revoke} answer.
     *
     * @param cleared     whether Splunk's logout answer expired the session cookie
     * @param clearScript a {@code document.cookie} line that deletes the script's cookie in the browser
     *                    it was pasted into — the server cannot reach that browser
     */
    public record Revocation(String correlationId, String target, Integer httpStatus, boolean cleared, String clearScript,
                             String mode, String revokedAt) {
    }
}
