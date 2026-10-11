package com.creed.gatewayproxy.service.splunk;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.config.SplunkProperties;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditRow;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditStore;

/**
 * The Splunk session broker (ported from the Node BFF's broker.js): verify a TOTP, log the chosen
 * target's shared account into Splunk, hand the session cookie back as a {@code document.cookie}
 * script.
 *
 * <p>Every step is audited, the failures included, each row saved as its step finishes so a Splunk
 * failure cannot lose the OTP row before it. The audit is mandatory: a row that cannot be written
 * fails the request.
 *
 * <p><b>Order matters:</b> everything that can refuse without the code — the block window, an unknown
 * target, a bad cookie name, a missing tunnel, an unconfigured target — is checked <i>before</i> the
 * code is verified. Verifying consumes it (replay protection), and a code burned on a request that
 * could never succeed sends the user's retry into a "replayed" 401.
 */
@Slf4j
public class SplunkBroker {

    private final Totp totp;
    private final SplunkLoginClient loginClient;
    private final SplunkProperties splunk;
    private final SplunkSecrets secrets;
    private final SplunkAuditStore store;
    private final BlockWindows block;
    private final Clock clock;
    /** client ip → times of recent failed verifications. In-process: per instance, lost on restart. */
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    /**
     * correlation id → what a revoke needs: the cookies the login handed out. Memory only, on purpose —
     * persisting live Splunk sessions would put credentials in the audit database. A restart, the
     * {@link #HELD_MAX} cap or {@link #HELD_TTL} make older sessions unrevocable (they still expire
     * on Splunk's own timeout). Insertion-ordered: the oldest is evicted first.
     */
    private final LinkedHashMap<String, Held> held = new LinkedHashMap<>();

    static final int HELD_MAX = 500;
    static final java.time.Duration HELD_TTL = java.time.Duration.ofHours(24);

    private record Held(String targetId, boolean viaTunnel, Map<String, String> cookies, String sessionCookie,
                        String scriptCookieName, String scriptCookiePath, boolean secure, String fingerprint, Instant issuedAt) {
    }

    public SplunkBroker(Totp totp, SplunkLoginClient loginClient, SplunkProperties splunk, SplunkSecrets secrets,
                        SplunkAuditStore store, BlockWindows block, Clock clock) {
        this.totp = totp;
        this.loginClient = loginClient;
        this.splunk = splunk;
        this.secrets = secrets;
        this.store = store;
        this.block = block;
        this.clock = clock;
    }

    /** Who asked — recorded, never trusted. */
    public record Client(String ip, String forwardedFor, String userAgent) {
    }

    public SplunkDtos.TotpInfo info() {
        long now = clock.millis();
        return new SplunkDtos.TotpInfo(totp.configured(), totp.config().periodSeconds(), totp.config().digits(),
                totp.config().allowedDriftSteps(), now, totp.config().exposeCurrentCode() && totp.configured(),
                loginClient.mode(), splunk.targets().stream().anyMatch(this::configured),
                splunk.targets().stream().map(this::publicTarget).toList(), splunk.defaultTarget(), splunk.scriptCookieName(),
                blockInfo(Instant.ofEpochMilli(now)));
    }

    public SplunkDtos.TotpCode currentCode() {
        if (!totp.configured()) throw new BrokerException(503, "not_configured", "the TOTP secret is not set");
        if (!totp.config().exposeCurrentCode()) {
            throw new BrokerException(404, "code_hidden", "the current code is not exposed (creed.totp.expose-current-code=false)");
        }
        return new SplunkDtos.TotpCode(totp.currentCode(), totp.currentStep(), totp.secondsRemaining(),
                totp.config().periodSeconds(), clock.millis());
    }

    public List<SplunkAuditRow> audit(int limit) {
        List<SplunkAuditRow> rows = store.list(Math.min(Math.max(limit, 1), 500));
        synchronized (held) {
            evictExpired(clock.instant());
            return rows.stream().map(r -> "SPLUNK_LOGIN".equals(r.eventType()) && "SUCCESS".equals(r.outcome())
                    ? r.toBuilder().revocable(held.containsKey(r.correlationId())).build() : r).toList();
        }
    }

    /**
     * Ends a session this broker issued: Splunk Web's logout with the login's cookies, through the same
     * route the login took, then forgets it. Needs no code — ending a session only takes access away —
     * and is not subject to the block window. Audited as {@code SPLUNK_LOGOUT} under the login's
     * correlation id.
     *
     * @throws BrokerException 404 when the session is not held (restart, too old, already revoked),
     *                         502 when Splunk fails — the session is then kept, so a retry can work
     */
    public SplunkDtos.Revocation revoke(String correlationId, Client client) {
        Held session;
        synchronized (held) {
            evictExpired(clock.instant());
            // Removed up front: two concurrent revokes must not both reach Splunk.
            session = held.remove(correlationId);
        }
        SplunkAuditRow.SplunkAuditRowBuilder row = row(correlationId, "SPLUNK_LOGOUT", client).splunkMode(loginClient.mode());
        if (session == null) {
            throw new BrokerException(404, "not_revocable", "this server no longer holds that session (restarted, older than "
                    + HELD_TTL.toHours() + "h, or already revoked) — it ends on Splunk's own timeout");
        }
        SplunkProperties.Target target = splunk.targets().stream().filter(t -> t.id().equals(session.targetId())).findFirst()
                .orElse(null);
        row.cookieFingerprint(session.fingerprint());
        if (target == null || (splunk.enabled() && target.logoutUrl() == null)) {
            String why = target == null ? "target " + session.targetId() + " is no longer configured"
                    : "target " + target.id() + ": login-url does not end in /account/login, so there is no logout URL";
            store.save(fail(row, "no_logout_url", why));
            throw new BrokerException(400, "no_logout_url", why);
        }
        String route = session.viaTunnel() ? " via tunnel " + target.tunnelAddress() : "";
        long started = System.nanoTime();
        SplunkLoginClient.LogoutResult result;
        try {
            result = loginClient.logout(target, session.cookies(), session.sessionCookie(), session.viaTunnel());
        } catch (SplunkLoginClient.SplunkLoginException e) {
            synchronized (held) {
                held.putIfAbsent(correlationId, session);
            }
            store.save(fail(row.durationMs(elapsedMs(started)).httpStatus(e.httpStatus() == 0 ? null : e.httpStatus()),
                    e.reason(), "[" + target.id() + route + "] " + e.getMessage()));
            log.warn("Splunk logout failed ({}) [{}]: {}", e.reason(), correlationId, e.getMessage());
            throw new BrokerException(502, "splunk_" + e.reason(), e.getMessage());
        }
        store.save(row.outcome("SUCCESS").durationMs(elapsedMs(started))
                .httpStatus(result.httpStatus() == 0 ? null : result.httpStatus())
                .detail("on " + target.id() + route + ", " + session.sessionCookie()
                        + (result.cleared() ? " expired by Splunk" : " not expired in Splunk's answer"))
                .build());
        log.info("session revoked by {} target={}{} fingerprint={} cleared={} [{}]", client.ip(), target.id(), route,
                session.fingerprint(), result.cleared(), correlationId);
        return new SplunkDtos.Revocation(correlationId, target.id(), result.httpStatus() == 0 ? null : result.httpStatus(),
                result.cleared(), clearScript(session.scriptCookieName(), session.scriptCookiePath(), session.secure()),
                loginClient.mode(), clock.instant().toString());
    }

    /**
     * @param targetId a target's id; the default target when null
     * @param request  the code and this login's overrides (cookie names, tunnel)
     */
    public SplunkDtos.Session issue(SplunkDtos.SessionRequest request, Client client) {
        String correlationId = UUID.randomUUID().toString();
        Instant now = clock.instant();

        // --- 0. Everything that can refuse without spending the code ---------------------------
        BlockWindows.Window window = block.blockedAt(now);
        if (window != null) {
            store.save(fail(row(correlationId, "SPLUNK_LOGIN", client).splunkMode(loginClient.mode()), "blocked",
                    "inside block window " + window + " (" + block.zone() + ")"));
            Instant next = block.nextChange(now);
            String until = next == null ? "" : " — allowed again at " + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    .format(next.atZone(block.zone()));
            throw new BrokerException(403, "blocked", "Splunk sessions are not issued during " + window + " (" + block.zone()
                    + ")" + until + " — the code was not used",
                    Map.of("Retry-After", String.valueOf(block.secondsUntilChange(now))));
        }
        SplunkProperties.Target target = resolveTarget(request.target());
        validateCookie("sessionCookie", request.sessionCookie());
        validateCookie("scriptCookieName", request.scriptCookieName());
        boolean viaTunnel = request.viaTunnel() != null ? request.viaTunnel() : target.tunnelDefault();
        if (viaTunnel && target.tunnel() == null) {
            throw new BrokerException(400, "no_tunnel", "Splunk login target '" + target.label()
                    + "' has no tunnel configured — the code was not used");
        }
        String sessionCookie = notBlank(request.sessionCookie(), splunk.sessionCookieOf(target));
        String scriptCookieName = notBlank(request.scriptCookieName(), splunk.scriptCookieNameOf(target));
        String scriptCookiePath = splunk.scriptCookiePathOf(target);
        String route = viaTunnel ? " via tunnel " + target.tunnelAddress() : "";
        if (!configured(target)) {
            store.save(fail(row(correlationId, "SPLUNK_LOGIN", client).splunkMode(loginClient.mode()), "not_configured",
                    "target " + target.id() + ": login-url / username / " + target.passwordVariable() + " missing"));
            throw new BrokerException(503, "not_configured", "Splunk login target '" + target.label()
                    + "' is not configured on the server — the code was not used");
        }

        // --- 1. OTP ---------------------------------------------------------------------------
        SplunkAuditRow.SplunkAuditRowBuilder otpRow = row(correlationId, "OTP_VERIFY", client);
        if (!totp.configured()) {
            store.save(fail(otpRow, "not_configured", "the TOTP secret is not set"));
            throw new BrokerException(503, "not_configured", "TOTP is not configured on the server");
        }
        long windowMs = totp.config().failureWindow().toMillis();
        Deque<Long> recent = recentFailures(client.ip(), now.toEpochMilli(), windowMs);
        if (recent.size() >= totp.config().maxFailures()) {
            store.save(fail(otpRow, "locked_out", "more than " + totp.config().maxFailures() + " failures within " + windowMs / 1000 + "s"));
            throw new BrokerException(429, "too_many_attempts", "too many failed codes — try again in " + windowMs / 1000 + "s",
                    Map.of("Retry-After", String.valueOf(windowMs / 1000)));
        }
        Totp.Verification v = totp.verify(request.code());
        otpRow.serverStep(v.serverStep()).matchedStep(v.matchedStep());
        if (!v.valid()) {
            synchronized (recent) {
                recent.addLast(now.toEpochMilli());
            }
            store.save(fail(otpRow, v.reason(), null));
            log.warn("OTP rejected ({}) from {} [{}]", v.reason(), client.ip(), correlationId);
            throw "replayed".equals(v.reason())
                    ? new BrokerException(401, "otp_replayed", "this code has already been used — wait for the next one")
                    : new BrokerException(401, "otp_invalid", "the code is invalid or expired");
        }
        failures.remove(key(client.ip()));
        store.save(otpRow.outcome("SUCCESS").detail("drift=" + v.drift()).build());

        // --- 2. Splunk ------------------------------------------------------------------------
        SplunkAuditRow.SplunkAuditRowBuilder loginRow = row(correlationId, "SPLUNK_LOGIN", client).splunkMode(loginClient.mode());
        long started = System.nanoTime();
        SplunkLoginClient.LoginResult result;
        try {
            result = loginClient.login(target, secrets.password(target), sessionCookie, viaTunnel);
        } catch (SplunkLoginClient.SplunkLoginException e) {
            store.save(fail(loginRow.durationMs(elapsedMs(started)).httpStatus(e.httpStatus() == 0 ? null : e.httpStatus()),
                    e.reason(), "[" + target.id() + route + "] " + e.getMessage()));
            log.warn("Splunk login failed ({}) [{}]: {}", e.reason(), correlationId, e.getMessage());
            // Splunk, not this service, is what failed — hence 502, the audit reason as the error code.
            throw new BrokerException(502, "splunk_" + e.reason(), e.getMessage());
        }

        String fingerprint = fingerprint(result.cookieValue());
        store.save(loginRow.outcome("SUCCESS").durationMs(elapsedMs(started))
                .httpStatus(result.httpStatus() == 0 ? null : result.httpStatus()).cookieFingerprint(fingerprint)
                .detail("as " + target.username() + " on " + target.id() + route + ", " + sessionCookie + " -> " + scriptCookieName)
                .build());
        log.info("session issued to {} target={}{} fingerprint={} [{}]", client.ip(), target.id(), route, fingerprint, correlationId);

        boolean secure = isHttps(target.loginUrl());
        synchronized (held) {
            held.put(correlationId, new Held(target.id(), viaTunnel, result.cookies(), sessionCookie, scriptCookieName,
                    scriptCookiePath, secure, fingerprint, now));
            evictExpired(now);
        }
        return new SplunkDtos.Session(target.id(), sessionCookie, scriptCookieName, result.cookieValue(),
                script(scriptCookieName, result.cookieValue(), scriptCookiePath, secure), secure,
                viaTunnel ? target.tunnelAddress().toString() : null, loginClient.mode(), correlationId, fingerprint,
                Instant.ofEpochMilli(clock.millis()).toString());
    }

    /**
     * {@code document.cookie = "splunkd_8089=<value>; path=/; Secure; SameSite=Lax";} — {@code Secure}
     * only when Splunk Web is https: the script runs on Splunk Web's own page, and a browser ignores a
     * Secure cookie set from an http one. Escaped for a JS string literal: a real value is hex, but a
     * quote in it would turn a pasted script into something else.
     */
    static String script(String name, String value, String path, boolean secure) {
        String escaped = (name + "=" + value + "; path=" + path + (secure ? "; Secure" : "") + "; SameSite=Lax")
                .replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\r\n]", "");
        return "document.cookie = \"" + escaped + "\";";
    }

    /** The script's counterpart: the same cookie, emptied and expired. */
    static String clearScript(String name, String path, boolean secure) {
        String escaped = (name + "=; path=" + path + "; expires=Thu, 01 Jan 1970 00:00:00 GMT" + (secure ? "; Secure" : "")
                + "; SameSite=Lax").replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\r\n]", "");
        return "document.cookie = \"" + escaped + "\";";
    }

    /** Caller holds the {@code held} lock. */
    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(HELD_TTL);
        Iterator<Held> it = held.values().iterator();
        while (it.hasNext()) {
            Held h = it.next();
            if (held.size() > HELD_MAX || h.issuedAt().isBefore(cutoff)) it.remove();
            else break; // insertion order: everything after is newer
        }
    }

    /** True for an https login URL; anything unparsable or plain http is not. */
    static boolean isHttps(String loginUrl) {
        try {
            return loginUrl != null && "https".equalsIgnoreCase(URI.create(loginUrl).getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a login can be attempted; the mock needs nothing. */
    boolean configured(SplunkProperties.Target t) {
        return !splunk.enabled() || (t.loginUrl() != null && t.username() != null && secrets.password(t) != null);
    }

    private SplunkDtos.PublicTarget publicTarget(SplunkProperties.Target t) {
        return new SplunkDtos.PublicTarget(t.id(), t.label(), t.loginUrl(), t.username(), secrets.password(t) != null,
                configured(t), splunk.sessionCookieOf(t), splunk.scriptCookieNameOf(t), splunk.scriptCookiePathOf(t),
                t.tunnel() == null ? null : t.tunnelAddress().toString(), t.tunnelDefault());
    }

    private SplunkDtos.BlockInfo blockInfo(Instant now) {
        Instant next = block.nextChange(now);
        return new SplunkDtos.BlockInfo(block.windows().stream().map(BlockWindows.Window::toString).toList(),
                block.zone().getId(), block.blockedAt(now) != null, next == null ? null : next.toEpochMilli());
    }

    private SplunkProperties.Target resolveTarget(String id) {
        String wanted = id != null ? id : splunk.defaultTarget();
        return splunk.targets().stream().filter(t -> t.id().equals(wanted)).findFirst()
                .orElseThrow(() -> new BrokerException(400, "unknown_target", "no Splunk login target '" + wanted + "' — the code was not used"));
    }

    private static void validateCookie(String field, String value) {
        if (value != null && !value.isEmpty() && !SplunkProperties.COOKIE_NAME.matcher(value).matches()) {
            throw new BrokerException(400, "validation_failed", field + " must match " + SplunkProperties.COOKIE_NAME + " — the code was not used");
        }
    }

    private Deque<Long> recentFailures(String ip, long now, long windowMs) {
        Deque<Long> recent = failures.computeIfAbsent(key(ip), k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() >= windowMs) recent.pollFirst();
        }
        return recent;
    }

    private static String key(String ip) {
        return ip == null ? "" : ip;
    }

    private static SplunkAuditRow.SplunkAuditRowBuilder row(String correlationId, String eventType, Client client) {
        return SplunkAuditRow.builder().correlationId(correlationId).eventType(eventType)
                .clientIp(truncate(client.ip(), 64)).forwardedFor(truncate(client.forwardedFor(), 256))
                .userAgent(truncate(client.userAgent(), 256));
    }

    private static SplunkAuditRow fail(SplunkAuditRow.SplunkAuditRowBuilder row, String reason, String detail) {
        return row.outcome("FAILURE").reason(truncate(reason, 32)).detail(truncate(detail, 512)).build();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String notBlank(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
