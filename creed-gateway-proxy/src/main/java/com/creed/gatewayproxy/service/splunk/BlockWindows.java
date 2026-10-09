package com.creed.gatewayproxy.service.splunk;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The times of day at which the broker issues no session ({@code creed.splunk.block}), e.g.
 * {@code 22:00-09:00}. Start inclusive, end exclusive; an end at or before its start crosses
 * midnight. Read on one fixed zone, so "22:00" means the same instant for every browser.
 */
public final class BlockWindows {

    private static final Pattern WINDOW = Pattern.compile("^(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})$");

    public record Window(LocalTime start, LocalTime end) {

        boolean contains(LocalTime t) {
            return start.isBefore(end)
                    ? !t.isBefore(start) && t.isBefore(end)
                    : !t.isBefore(start) || t.isBefore(end); // crosses midnight (or covers the whole day)
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }

    private final List<Window> windows;
    private final ZoneId zone;

    public BlockWindows(String spec, ZoneId zone) {
        this.windows = parse(spec);
        this.zone = zone;
    }

    /** {@code HH:mm-HH:mm[,…]}; blank is no window. Throws on anything else, so a typo fails startup. */
    public static List<Window> parse(String spec) {
        List<Window> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            Matcher m = WINDOW.matcher(part.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("creed.splunk.block.windows: '" + part.strip() + "' is not HH:mm-HH:mm");
            }
            try {
                out.add(new Window(time(m.group(1)), time(m.group(2))));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("creed.splunk.block.windows: '" + part.strip() + "' is not a valid time range", e);
            }
        }
        return List.copyOf(out);
    }

    private static LocalTime time(String hhmm) {
        return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
    }

    public List<Window> windows() {
        return windows;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The window {@code now} falls in, or null. */
    public Window blockedAt(Instant now) {
        LocalTime t = now.atZone(zone).toLocalTime();
        return windows.stream().filter(w -> w.contains(t)).findFirst().orElse(null);
    }

    /**
     * The next instant after {@code now} at which blocked/allowed flips — when the page should ask
     * again. Null when it never does (no windows, or one that covers the whole day).
     */
    public Instant nextChange(Instant now) {
        if (windows.isEmpty()) return null;
        boolean blocked = blockedAt(now) != null;
        // Every boundary is on a whole minute, so stepping minute by minute over a day finds the flip.
        ZonedDateTime t = now.atZone(zone).withSecond(0).withNano(0);
        for (int i = 1; i <= 24 * 60 + 1; i++) {
            ZonedDateTime candidate = t.plusMinutes(i);
            if ((blockedAt(candidate.toInstant()) != null) != blocked) return candidate.toInstant();
        }
        return null;
    }

    /** Seconds until {@code nextChange}, for a Retry-After header; at least 1. */
    public long secondsUntilChange(Instant now) {
        Instant next = nextChange(now);
        return next == null ? 3600 : Math.max(1, Duration.between(now, next).toSeconds());
    }

    @Override
    public String toString() {
        return windows.stream().map(Window::toString).reduce((a, b) -> a + "," + b).orElse("");
    }
}
