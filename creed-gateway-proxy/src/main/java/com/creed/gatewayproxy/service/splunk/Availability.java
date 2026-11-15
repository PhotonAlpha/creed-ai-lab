package com.creed.gatewayproxy.service.splunk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When one target issues sessions: never inside a {@code block} window and, when {@code allowed} has
 * windows, only inside one of them. Empty {@code allowed} = no restriction beyond the block windows.
 * Both are read on the same zone (the target's block zone).
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

    /** Null when a session may be issued at {@code now}. */
    public Refusal refusalAt(Instant now) {
        BlockWindows.Window window = block.blockedAt(now);
        if (window != null) return new Refusal(Reason.BLOCKED, window);
        if (!allowed.windows().isEmpty() && allowed.blockedAt(now) == null) return new Refusal(Reason.NOT_ALLOWED, null);
        return null;
    }

    /** When refused/issued flips next; null when it never does. */
    public Instant nextChange(Instant now) {
        return BlockWindows.nextChange(now, t -> refusalAt(t) != null, List.of(block, allowed));
    }

    /** Seconds until {@code nextChange}, for a Retry-After header; at least 1 (an hour when it never changes). */
    public long secondsUntilChange(Instant now) {
        Instant next = nextChange(now);
        return next == null ? 3600 : Math.max(1, Duration.between(now, next).toSeconds());
    }
}
