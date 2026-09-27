package com.creed.resource.envmatrix.service.splunk;

import com.creed.resource.envmatrix.api.dto.SplunkAuditDto;
import com.creed.resource.envmatrix.api.dto.SplunkSessionDto;
import com.creed.resource.envmatrix.api.dto.TotpCodeDto;
import com.creed.resource.envmatrix.api.dto.TotpInfoDto;
import com.creed.resource.envmatrix.config.SplunkProperties;
import com.creed.resource.envmatrix.domain.SplunkAuditEvent;
import com.creed.resource.envmatrix.domain.SplunkAuditRepository;
import com.creed.resource.envmatrix.service.TotpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The broker: verify a TOTP, then log the shared account into Splunk and hand back the session cookie
 * as a {@code document.cookie} script.
 *
 * <p><b>Every step is audited, including the ones that fail</b> — a rejected code is exactly what an
 * audit trail is for. Each row is saved on its own (this class is deliberately not
 * {@code @Transactional}), so a Splunk failure cannot roll back the OTP row that preceded it.
 */
@Service
@Slf4j
public class SplunkSessionService {

    private final TotpService totp;
    private final SplunkLoginClient splunk;
    private final SplunkProperties splunkProperties;
    private final SplunkAuditRepository auditRepository;
    private final Clock clock;
    /** client ip → timestamps of recent failed verifications. */
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public SplunkSessionService(TotpService totp, SplunkLoginClient splunk, SplunkProperties splunkProperties,
                                SplunkAuditRepository auditRepository, Clock clock) {
        this.totp = totp;
        this.splunk = splunk;
        this.splunkProperties = splunkProperties;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    public TotpInfoDto info() {
        var p = totp.properties();
        return new TotpInfoDto(
                totp.isConfigured(), p.periodSeconds(), p.digits(), p.allowedDriftSteps(), clock.millis(),
                p.exposeCurrentCode() && totp.isConfigured(), splunk.mode(), splunkProperties.configured(),
                splunkProperties.enabled() ? splunkProperties.loginUrl() : null,
                splunkProperties.scriptCookieName());
    }

    public TotpCodeDto currentCode() {
        if (!totp.isConfigured()) {
            throw new NotConfiguredException("env-matrix.totp.secret is not set");
        }
        if (!totp.properties().exposeCurrentCode()) {
            throw new CodeHiddenException();
        }
        return new TotpCodeDto(totp.currentCode(), totp.currentStep(), totp.secondsRemaining(),
                totp.properties().periodSeconds(), clock.millis());
    }

    public List<SplunkAuditDto> audit(int limit) {
        int size = Math.clamp(limit, 1, 500);
        return auditRepository.findAllByOrderByIdDesc(PageRequest.of(0, size)).stream()
                .map(SplunkAuditDto::of)
                .toList();
    }

    public SplunkSessionDto issue(String code, ClientInfo client) {
        String correlationId = UUID.randomUUID().toString();

        // --- 0. Splunk configured? -------------------------------------------------------------
        // Checked before the code is verified: verifying consumes it (replay protection), and a code
        // burned on a request that could never succeed sends the user's retry into a "replayed" 401.
        if (!splunkProperties.configured()) {
            SplunkAuditEvent loginRow = row(correlationId, SplunkAuditEvent.SPLUNK_LOGIN, client);
            loginRow.setSplunkMode(splunk.mode());
            save(fail(loginRow, "not_configured", "env-matrix.splunk login-url/username/password missing"));
            throw new NotConfiguredException("Splunk login is not configured on the server — the code was not used");
        }

        // --- 1. OTP ---------------------------------------------------------------------------
        SplunkAuditEvent otpRow = row(correlationId, SplunkAuditEvent.OTP_VERIFY, client);
        if (!totp.isConfigured()) {
            save(fail(otpRow, "not_configured", "env-matrix.totp.secret is not set"));
            throw new NotConfiguredException("TOTP is not configured on the server");
        }
        if (lockedOut(client.ip())) {
            save(fail(otpRow, "locked_out", "more than " + totp.properties().maxFailures()
                    + " failures within " + totp.properties().failureWindow()));
            throw new TooManyAttemptsException(totp.properties().failureWindow().toSeconds());
        }

        TotpService.Verification verification = totp.verify(code);
        otpRow.setServerStep(verification.serverStep());
        otpRow.setMatchedStep(verification.matchedStep());
        if (!verification.valid()) {
            recordFailure(client.ip());
            save(fail(otpRow, verification.reason(), null));
            log.warn("splunk broker: OTP rejected ({}) from {} [{}]", verification.reason(), client.ip(), correlationId);
            throw new InvalidOtpException(verification.reason());
        }
        failures.remove(client.ip());
        otpRow.setOutcome(SplunkAuditEvent.SUCCESS);
        otpRow.setDetail("drift=" + verification.drift());
        save(otpRow);

        // --- 2. Splunk ------------------------------------------------------------------------
        SplunkAuditEvent loginRow = row(correlationId, SplunkAuditEvent.SPLUNK_LOGIN, client);
        loginRow.setSplunkMode(splunk.mode());

        long started = System.nanoTime();
        SplunkLoginClient.Result result;
        try {
            result = splunk.login();
        } catch (SplunkLoginClient.SplunkLoginException e) {
            loginRow.setDurationMs(elapsedMs(started));
            loginRow.setHttpStatus(e.httpStatus() == 0 ? null : e.httpStatus());
            save(fail(loginRow, e.reason(), e.getMessage()));
            log.warn("splunk broker: login failed ({}) [{}]: {}", e.reason(), correlationId, e.getMessage());
            throw e;
        }

        String fingerprint = fingerprint(result.cookieValue());
        loginRow.setDurationMs(elapsedMs(started));
        loginRow.setHttpStatus(result.httpStatus() == 0 ? null : result.httpStatus());
        loginRow.setCookieFingerprint(fingerprint);
        loginRow.setOutcome(SplunkAuditEvent.SUCCESS);
        loginRow.setDetail("as " + splunkProperties.username());
        save(loginRow);
        log.info("splunk broker: session issued to {} fingerprint={} [{}]", client.ip(), fingerprint, correlationId);

        String name = splunkProperties.scriptCookieName();
        return new SplunkSessionDto(
                splunkProperties.sessionCookie(), name, result.cookieValue(),
                script(name, result.cookieValue(), splunkProperties.scriptCookiePath()),
                splunk.mode(), correlationId, fingerprint, clock.instant());
    }

    /**
     * {@code document.cookie = "splunkd_8089=<value>; path=/; Secure; SameSite=Lax";}
     * The value is escaped for a JS string literal; a real Splunk value is hex, but a quote in it
     * would otherwise turn a pasted script into something else.
     */
    static String script(String name, String value, String path) {
        String escaped = (name + "=" + value + "; path=" + path + "; Secure; SameSite=Lax")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "")
                .replace("\r", "");
        return "document.cookie = \"" + escaped + "\";";
    }

    static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean lockedOut(String ip) {
        Deque<Instant> recent = failures.get(ip);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= totp.properties().maxFailures();
        }
    }

    private void recordFailure(String ip) {
        Deque<Instant> recent = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(totp.properties().failureWindow());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.pollFirst();
        }
    }

    private static SplunkAuditEvent row(String correlationId, String type, ClientInfo client) {
        SplunkAuditEvent e = new SplunkAuditEvent();
        e.setCorrelationId(correlationId);
        e.setEventType(type);
        e.setClientIp(truncate(client.ip(), 64));
        e.setForwardedFor(truncate(client.forwardedFor(), 256));
        e.setUserAgent(truncate(client.userAgent(), 256));
        return e;
    }

    private static SplunkAuditEvent fail(SplunkAuditEvent e, String reason, String detail) {
        e.setOutcome(SplunkAuditEvent.FAILURE);
        e.setReason(truncate(reason, 32));
        e.setDetail(truncate(detail, 512));
        return e;
    }

    private void save(SplunkAuditEvent e) {
        auditRepository.save(e);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Who asked — recorded, never trusted. {@code forwardedFor} is whatever the header said. */
    public record ClientInfo(String ip, String forwardedFor, String userAgent) {
    }

    public static class InvalidOtpException extends RuntimeException {
        private final String reason;

        public InvalidOtpException(String reason) {
            super("replayed".equals(reason)
                    ? "this code has already been used — wait for the next one"
                    : "the code is invalid or expired");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public static class TooManyAttemptsException extends RuntimeException {
        private final long retryAfterSeconds;

        public TooManyAttemptsException(long retryAfterSeconds) {
            super("too many failed codes — try again in " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    public static class CodeHiddenException extends RuntimeException {
        public CodeHiddenException() {
            super("the current code is not exposed (env-matrix.totp.expose-current-code=false)");
        }
    }
}
