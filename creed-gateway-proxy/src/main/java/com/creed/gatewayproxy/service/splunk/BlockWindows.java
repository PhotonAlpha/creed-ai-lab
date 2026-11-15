package com.creed.gatewayproxy.service.splunk;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The times at which the broker issues no session ({@code creed.splunk.block.windows}, and a target's
 * own {@code targets[].block.windows}). A comma-separated list of either kind, freely mixed:
 * <ul>
 *   <li>a daily window, {@code 22:00-09:00} — every day; an end at or before its start crosses midnight;</li>
 *   <li>a date range, {@code 2026-11-16 20:00:00 - 2026-11-20 00:00:00} — once (seconds optional; a
 *   {@code T} may replace the space); the end must be after the start.</li>
 * </ul>
 * Start inclusive, end exclusive. Read on one fixed zone, so "22:00" means the same instant for every browser.
 * The same syntax describes when a target is <i>allowed</i> ({@code targets[].allow.windows}, see Availability).
 */
public final class BlockWindows {

    private static final Pattern DAILY = Pattern.compile("^(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})$");
    private static final String DATE_TIME = "\\d{4}-\\d{2}-\\d{2}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?";
    private static final Pattern RANGE = Pattern.compile("^(" + DATE_TIME + ")\\s*-\\s*(" + DATE_TIME + ")$");
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    public sealed interface Window permits Daily, Range {

        boolean contains(LocalDateTime t);
    }

    /** Every day from {@code start} to {@code end}. */
    public record Daily(LocalTime start, LocalTime end) implements Window {

        @Override
        public boolean contains(LocalDateTime at) {
            LocalTime t = at.toLocalTime();
            return start.isBefore(end)
                    ? !t.isBefore(start) && t.isBefore(end)
                    : !t.isBefore(start) || t.isBefore(end); // crosses midnight (or covers the whole day)
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }

    /** Once, from {@code start} to {@code end}. */
    public record Range(LocalDateTime start, LocalDateTime end) implements Window {

        @Override
        public boolean contains(LocalDateTime t) {
            return !t.isBefore(start) && t.isBefore(end);
        }

        @Override
        public String toString() {
            return format(start) + " - " + format(end);
        }

        private static String format(LocalDateTime t) {
            return (t.getSecond() == 0 ? MINUTES : SECONDS).format(t);
        }
    }

    private final List<Window> windows;
    private final ZoneId zone;

    public BlockWindows(String spec, ZoneId zone) {
        this.windows = parse(spec);
        this.zone = zone;
    }

    /** See the class comment; blank is no window. Throws on anything else, so a typo fails startup. */
    public static List<Window> parse(String spec) {
        List<Window> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            String item = part.strip();
            try {
                Matcher daily = DAILY.matcher(item);
                Matcher range = RANGE.matcher(item);
                if (daily.matches()) {
                    out.add(new Daily(time(daily.group(1)), time(daily.group(2))));
                } else if (range.matches()) {
                    LocalDateTime start = dateTime(range.group(1));
                    LocalDateTime end = dateTime(range.group(2));
                    if (!end.isAfter(start)) {
                        throw new IllegalArgumentException("creed.splunk.block.windows: '" + item + "' ends before it starts");
                    }
                    out.add(new Range(start, end));
                } else {
                    throw new IllegalArgumentException("creed.splunk.block.windows: '" + item
                            + "' is neither HH:mm-HH:mm nor yyyy-MM-dd HH:mm[:ss] - yyyy-MM-dd HH:mm[:ss]");
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("creed.splunk.block.windows: '" + item + "' is not a valid time range", e);
            }
        }
        return List.copyOf(out);
    }

    private static LocalTime time(String hhmm) {
        return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
    }

    /** {@code 2026-11-16 8:00} → {@code 2026-11-16T08:00}; seconds optional. */
    private static LocalDateTime dateTime(String raw) {
        String date = raw.substring(0, 10);
        String time = raw.substring(11);
        if (time.indexOf(':') == 1) time = "0" + time;
        return LocalDateTime.parse(date + "T" + time);
    }

    public List<Window> windows() {
        return windows;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The window {@code now} falls in, or null. */
    public Window blockedAt(Instant now) {
        LocalDateTime t = now.atZone(zone).toLocalDateTime();
        return windows.stream().filter(w -> w.contains(t)).findFirst().orElse(null);
    }

    /**
     * The next instant after {@code now} at which blocked/allowed flips — when the page should ask
     * again. Null when it never does (no windows, one that covers the whole day, or only date ranges
     * already over).
     */
    public Instant nextChange(Instant now) {
        if (windows.isEmpty()) return null;
        return nextChange(now, t -> blockedAt(t) != null, List.of(this));
    }

    /**
     * The next instant after {@code now} at which {@code state} changes, where {@code state} depends only on
     * whether instants fall in the windows of {@code sources} — so it can only change on one of their edges,
     * and trying those edges in order finds the flip. Daily edges are needed up to a day past the last
     * date range (a range can cover days of daily windows). Null when it never changes.
     */
    static Instant nextChange(Instant now, Predicate<Instant> state, List<BlockWindows> sources) {
        Instant last = now;
        for (BlockWindows source : sources) {
            for (Window w : source.windows) {
                if (w instanceof Range r) {
                    Instant end = r.end().atZone(source.zone).toInstant();
                    if (end.isAfter(last)) last = end;
                }
            }
        }
        TreeSet<Instant> edges = new TreeSet<>();
        for (BlockWindows source : sources) {
            LocalDate first = now.atZone(source.zone).toLocalDate();
            LocalDate lastDay = last.atZone(source.zone).toLocalDate().plusDays(1);
            for (Window w : source.windows) {
                switch (w) {
                    case Daily d -> {
                        for (LocalDate day = first; !day.isAfter(lastDay); day = day.plusDays(1)) {
                            edges.add(day.atTime(d.start()).atZone(source.zone).toInstant());
                            edges.add(day.atTime(d.end()).atZone(source.zone).toInstant());
                        }
                    }
                    case Range r -> {
                        edges.add(r.start().atZone(source.zone).toInstant());
                        edges.add(r.end().atZone(source.zone).toInstant());
                    }
                }
            }
        }
        boolean current = state.test(now);
        for (Instant edge : edges.tailSet(now, false)) {
            if (state.test(edge) != current) return edge;
        }
        return null;
    }

    /** Seconds until {@code nextChange}, for a Retry-After header; at least 1. */
    public long secondsUntilChange(Instant now) {
        Instant next = nextChange(now);
        return next == null ? 3600 : Math.max(1, Duration.between(now, next).toSeconds());
    }

    /** When {@code next} is: {@code HH:mm} on the same day as {@code now}, else {@code yyyy-MM-dd HH:mm}. */
    public String describeChange(Instant now, Instant next) {
        ZonedDateTime n = next.atZone(zone);
        boolean sameDay = n.toLocalDate().equals(now.atZone(zone).toLocalDate());
        return DateTimeFormatter.ofPattern(sameDay ? "HH:mm" : "yyyy-MM-dd HH:mm", Locale.ROOT).format(n);
    }

    @Override
    public String toString() {
        return windows.stream().map(Window::toString).reduce((a, b) -> a + "," + b).orElse("");
    }
}
