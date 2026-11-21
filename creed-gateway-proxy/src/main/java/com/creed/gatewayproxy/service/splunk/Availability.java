package com.creed.gatewayproxy.service.splunk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When one target issues sessions. With {@code allowed} windows: inside one of them and nowhere else —
 * the {@code block} windows (inherited broker-wide ones included) do NOT apply, or a broker-wide
 * 22:00-09:00 would swallow an allowed 2026-11-17 20:00 - 2026-11-20 00:00 every night. Empty
 * {@code allowed}: never inside a {@code block} window. Both are read on the same zone (the target's block zone).
 */
public record Availability(BlockWindows block, BlockWindows allowed) {

    /** Why a session is refused right now. */
    public enum Reason {
        /** Inside a block window; the window may be shown. */
        BLOCKED,
        /** Outside every allowed window; the allowed windows are not shown (they are set by the admin API). */
        NOT_ALLOWED
    }

    /** @param window the block window in force, for {@link Reason#BLOCKED}; null otherwise */
    public record Refusal(Reason reason, BlockWindows.Window window) {
    }

    /** Whether the allowed windows decide alone (and the block windows are out of play). */
    public boolean restricted() {
        return !allowed.windows().isEmpty();
    }

    /** Null when a session may be issued at {@code now}. */
    public Refusal refusalAt(Instant now) {
        if (restricted()) return allowed.blockedAt(now) == null ? new Refusal(Reason.NOT_ALLOWED, null) : null;
        BlockWindows.Window window = block.blockedAt(now);
        return window == null ? null : new Refusal(Reason.BLOCKED, window);
    }

    /** When refused/issued flips next; null when it never does. */
    public Instant nextChange(Instant now) {
        return BlockWindows.nextChange(now, t -> refusalAt(t) != null, restricted() ? List.of(allowed) : List.of(block));
    }

    /** Seconds until {@code nextChange}, for a Retry-After header; at least 1 (an hour when it never changes). */
    public long secondsUntilChange(Instant now) {
        Instant next = nextChange(now);
        return next == null ? 3600 : Math.max(1, Duration.between(now, next).toSeconds());
    }
}
