package com.creed.simple.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PoolLeasedOnlyTurboFilter}: only the HC5 pool logger is considered, the
 * {@code format == null} probe must stay {@code NEUTRAL} (so {@code isDebugEnabled()} keeps returning
 * true), the "endpoint leased" line is allowed and every other pool line is denied.
 */
class PoolLeasedOnlyTurboFilterTest {

    private static final String POOL_LOGGER =
            "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager";

    private final PoolLeasedOnlyTurboFilter filter = new PoolLeasedOnlyTurboFilter();
    private final LoggerContext loggerContext = new LoggerContext();

    private Logger logger(String name) {
        return loggerContext.getLogger(name);
    }

    private FilterReply decide(Logger logger, String format) {
        return filter.decide(null, logger, Level.DEBUG, format, null, null);
    }

    @Test
    void nullLoggerIsNeutral() {
        assertThat(decide(null, "anything")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void otherLoggersAreNeutral() {
        assertThat(decide(logger("com.creed.simple.Whatever"), "endpoint leased")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void nullFormatProbeStaysNeutralSoIsDebugEnabledKeepsWorking() {
        assertThat(decide(logger(POOL_LOGGER), null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void endpointLeasedLineIsAllowed() {
        assertThat(decide(logger(POOL_LOGGER), "{} endpoint leased {}")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void everyOtherPoolLineIsDenied() {
        assertThat(decide(logger(POOL_LOGGER), "{} endpoint released {}")).isEqualTo(FilterReply.DENY);
        assertThat(decide(logger(POOL_LOGGER), "{} connection acquired {}")).isEqualTo(FilterReply.DENY);
    }
}
