package com.creed.gatewayproxy.service.splunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * The admin API behind {@code /api/env-matrix/splunk/admin/**}: read and replace a target's allowed
 * windows at runtime. Nothing on the page calls it or shows it.
 *
 * <p>Every call carries {@code Authorization: Bearer <SPLUNK_ADMIN_TOKEN>}. Without the token configured
 * the API answers 404, as if it did not exist. Only a hash of the token is kept, compared in constant
 * time; {@link #MAX_FAILURES} wrong tokens from one IP within {@link #FAILURE_WINDOW} get 429 until the
 * window passes. Refusals and changes are audited as {@code ADMIN_ALLOW} rows, which the page's audit
 * list leaves out.
 */
@Slf4j
public class SplunkAdmin {

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    private final SplunkBroker broker;
    private final SplunkProperties splunk;
    private final Clock clock;
    /** SHA-256 of the token; null = the API is off. */
    private final byte[] tokenHash;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public SplunkAdmin(SplunkBroker broker, SplunkProperties splunk, String token, Clock clock) {
        this.broker = broker;
        this.splunk = splunk;
        this.clock = clock;
        this.tokenHash = token == null ? null : sha256(token);
    }

    public boolean enabled() {
        return tokenHash != null;
    }

    public List<SplunkDtos.Availability> list(String authorization, SplunkBroker.Client client) {
        authorize(authorization, client);
        return splunk.targets().stream().map(t -> view(t.id())).toList();
    }

    public SplunkDtos.Availability get(String targetId, String authorization, SplunkBroker.Client client) {
        authorize(authorization, client);
        return view(targetId);
    }

    public SplunkDtos.Availability set(String targetId, String windows, String authorization, SplunkBroker.Client client) {
        authorize(authorization, client);
        if (windows == null) throw new BrokerException(400, "validation_failed", "windows is required (\"\" = unrestricted)");
        broker.setAllowed(targetId, windows, client);
        return view(targetId);
    }

    public SplunkDtos.Availability reset(String targetId, String authorization, SplunkBroker.Client client) {
        authorize(authorization, client);
        broker.resetAllowed(targetId, client);
        return view(targetId);
    }

    private SplunkDtos.Availability view(String targetId) {
        SplunkBroker.Allowed allowed = broker.allowedOf(targetId);
        SplunkProperties.Target t = splunk.targets().stream().filter(x -> x.id().equals(targetId)).findFirst().orElseThrow();
        Availability availability = broker.availabilityOf(t);
        Instant now = clock.instant();
        Availability.Refusal refusal = availability.refusalAt(now);
        Instant next = availability.nextChange(now);
        return new SplunkDtos.Availability(t.id(), allowed.windows().toString(), allowed.windows().zone().getId(),
                allowed.source(), allowed.updatedAt() == null ? null : allowed.updatedAt().toString(), allowed.updatedBy(),
                refusal == null, refusal == null ? null : refusal.reason() == Availability.Reason.BLOCKED ? "blocked" : "not_available",
                next == null ? null : next.toEpochMilli());
    }

    /**
     * @throws BrokerException 404 when the API is off, 429 after too many wrong tokens, 401 for a missing or
     *                         wrong token
     */
    void authorize(String authorization, SplunkBroker.Client client) {
        if (tokenHash == null) throw new BrokerException(404, "not_found", "no such endpoint");
        Instant now = clock.instant();
        String key = client.ip() == null ? "" : client.ip();
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().plus(FAILURE_WINDOW).isAfter(now)) recent.pollFirst();
            if (recent.size() >= MAX_FAILURES) {
                long retry = Math.max(1, Duration.between(now, recent.peekFirst().plus(FAILURE_WINDOW)).toSeconds());
                broker.auditAdminRefusal(client, "locked_out", "too many wrong admin tokens");
                throw new BrokerException(429, "too_many_attempts", "too many wrong tokens — try again in " + retry + "s",
                        Map.of("Retry-After", String.valueOf(retry)));
            }
        }
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).strip() : null;
        if (token == null || !MessageDigest.isEqual(tokenHash, sha256(token))) {
            synchronized (recent) {
                recent.addLast(now);
            }
            broker.auditAdminRefusal(client, token == null ? "no_token" : "bad_token", null);
            log.warn("admin API refused for {}: {}", client.ip(), token == null ? "no bearer token" : "wrong token");
            throw new BrokerException(401, "unauthorized", "a valid admin bearer token is required",
                    Map.of("WWW-Authenticate", "Bearer"));
        }
        failures.remove(key);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
