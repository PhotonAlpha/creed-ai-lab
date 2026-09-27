package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.SplunkAuditEvent;

import java.time.Instant;

/** Read model for one audit row. */
public record SplunkAuditDto(
        Long id,
        String correlationId,
        String eventType,
        String outcome,
        String reason,
        String detail,
        String clientIp,
        String forwardedFor,
        String userAgent,
        Long serverStep,
        Long matchedStep,
        String splunkMode,
        Integer httpStatus,
        String cookieFingerprint,
        Long durationMs,
        Instant createdAt) {

    public static SplunkAuditDto of(SplunkAuditEvent e) {
        return new SplunkAuditDto(
                e.getId(), e.getCorrelationId(), e.getEventType(), e.getOutcome(), e.getReason(),
                e.getDetail(), e.getClientIp(), e.getForwardedFor(), e.getUserAgent(),
                e.getServerStep(), e.getMatchedStep(), e.getSplunkMode(), e.getHttpStatus(),
                e.getCookieFingerprint(), e.getDurationMs(), e.getCreatedAt());
    }
}
