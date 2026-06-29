package com.creed.simple.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Keeps only the "endpoint leased" line from {@code PoolingHttpClientConnectionManager}'s very chatty
 * DEBUG output (releasing / released / acquired / executing exchange / keep-alive are all dropped).
 *
 * <p>A {@link TurboFilter} runs globally <em>before</em> appender routing and sees the raw SLF4J
 * format string (pre-substitution). HC5 logs the leased event as {@code LOG.debug("{} endpoint leased {}", ...)},
 * so matching the literal {@code "endpoint leased"} in the format is stable across HC5 5.x and needs no
 * Janino/EvaluatorFilter and no dedicated appenders.
 *
 * <p><b>Gotcha:</b> HC5 guards every line with {@code if (LOG.isDebugEnabled())}, and logback evaluates
 * the turbo-filter chain inside that probe with {@code format == null}. If we {@code DENY} on the null
 * probe, {@code isDebugEnabled()} returns {@code false} and the real {@code debug(...)} call is never
 * made — we'd see nothing. So the null-format probe must return {@code NEUTRAL}; the content match only
 * applies to the real log call, where {@code format} is non-null.
 */
public class PoolLeasedOnlyTurboFilter extends TurboFilter {

    private static final String POOL_LOGGER =
            "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null || !POOL_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL; // not our target logger: let normal processing decide
        }
        // format == null means this is an isDebugEnabled()/isXxxEnabled() probe, NOT a real log call.
        // HC5 guards every line with `if (LOG.isDebugEnabled())`, and logback evaluates the turbo-filter
        // chain inside that probe with format=null. Returning DENY here makes isDebugEnabled() return
        // false, so HC5 never even reaches debug("{} endpoint leased {}", ...) and we'd see nothing.
        // Stay NEUTRAL on the probe; the content match runs only on the real call (format != null).
        if (format == null) {
            return FilterReply.NEUTRAL;
        }
        return format.contains("endpoint leased")
                ? FilterReply.NEUTRAL  // allow this one line through to the appenders
                : FilterReply.DENY;    // suppress every other PoolingHttpClientConnectionManager line
    }
}
