package com.creed.gatewayproxy.service.splunk.audit;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * One audit row, in the shape the page reads (camelCase) and the columns of {@code splunk_audit}.
 * The cookie itself is never stored — only {@code cookieFingerprint}, 16 hex of its SHA-256.
 *
 * @param eventType OTP_VERIFY | SPLUNK_LOGIN | SPLUNK_LOGOUT (a revoke)
 * @param outcome   SUCCESS | FAILURE
 * @param clientHost the forward-confirmed reverse lookup of {@code clientIp} at the time; null when none
 * @param revocable not a column: set by the broker on a successful SPLUNK_LOGIN row whose session it
 *                  still holds (in memory), so the page knows where a revoke button works
 */
@Builder(toBuilder = true)
public record SplunkAuditRow(Long id, String correlationId, String eventType, String outcome, String reason, String detail,
                             String clientIp, String clientHost, String forwardedFor, String userAgent, Long serverStep, Long matchedStep,
                             String splunkMode, Integer httpStatus, String cookieFingerprint, Long durationMs, Instant createdAt,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Boolean revocable) {

    /** Column → value, in {@link #COLUMNS} order, for the JDBC stores. */
    Object[] values() {
        return new Object[]{correlationId, eventType, outcome, reason, detail, clientIp, forwardedFor, userAgent,
                serverStep, matchedStep, splunkMode, httpStatus, cookieFingerprint, durationMs, clientHost};
    }

    /** The Node BFF's columns: a table it created, and {@code public.splunk_audit}, have exactly these. */
    static final String[] LEGACY_COLUMNS = {"correlation_id", "event_type", "outcome", "reason", "detail", "client_ip",
            "forwarded_for", "user_agent", "server_step", "matched_step", "splunk_mode", "http_status",
            "cookie_fingerprint", "duration_ms"};

    /** Added by this broker ({@code alter table … add column}); the Node BFF does not write it. */
    static final String[] COLUMNS = {"correlation_id", "event_type", "outcome", "reason", "detail", "client_ip",
            "forwarded_for", "user_agent", "server_step", "matched_step", "splunk_mode", "http_status",
            "cookie_fingerprint", "duration_ms", "client_host"};
}
