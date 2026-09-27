package com.creed.resource.envmatrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One step of a Splunk session request — an OTP verification or a Splunk login call. See
 * {@code V6__create_splunk_audit.sql}. Append-only: nothing updates or deletes these rows.
 */
@Entity
@Table(name = "splunk_audit")
@Getter
@Setter
@NoArgsConstructor
public class SplunkAuditEvent {

    public static final String OTP_VERIFY = "OTP_VERIFY";
    public static final String SPLUNK_LOGIN = "SPLUNK_LOGIN";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "correlation_id", nullable = false, length = 36)
    private String correlationId;

    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    @Column(name = "outcome", nullable = false, length = 8)
    private String outcome;

    @Column(name = "reason", length = 32)
    private String reason;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "forwarded_for", length = 256)
    private String forwardedFor;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "server_step")
    private Long serverStep;

    @Column(name = "matched_step")
    private Long matchedStep;

    @Column(name = "splunk_mode", length = 8)
    private String splunkMode;

    @Column(name = "http_status")
    private Integer httpStatus;

    /** First 16 hex chars of the cookie's SHA-256 — never the cookie. */
    @Column(name = "cookie_fingerprint", length = 16)
    private String cookieFingerprint;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
