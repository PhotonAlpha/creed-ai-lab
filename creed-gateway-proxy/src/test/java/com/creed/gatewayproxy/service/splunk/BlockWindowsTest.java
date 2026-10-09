package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class BlockWindowsTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, SHANGHAI).toInstant();
    }

    @Test
    void overnightWindowBlocksFromTenPmUntilNineAm() {
        BlockWindows block = new BlockWindows("22:00-09:00", SHANGHAI);
        assertThat(block.blockedAt(at(21, 59))).isNull();
        assertThat(block.blockedAt(at(22, 0))).isNotNull();   // start inclusive
        assertThat(block.blockedAt(at(3, 0))).isNotNull();
        assertThat(block.blockedAt(at(8, 59))).isNotNull();
        assertThat(block.blockedAt(at(9, 0))).isNull();       // end exclusive
        assertThat(block.nextChange(at(21, 30))).isEqualTo(at(22, 0));
        assertThat(block.nextChange(at(23, 15))).isEqualTo(at(9, 0).plusSeconds(86400));
        assertThat(block.secondsUntilChange(at(8, 59))).isEqualTo(60);
    }

    @Test
    void zoneDecidesTheInstant() {
        // 22:00 in Shanghai is 14:00 UTC.
        BlockWindows block = new BlockWindows("22:00-09:00", SHANGHAI);
        assertThat(block.blockedAt(Instant.parse("2026-10-09T14:00:00Z"))).isNotNull();
        assertThat(block.blockedAt(Instant.parse("2026-10-09T13:59:00Z"))).isNull();
    }

    @Test
    void severalSameDayWindowsAndNone() {
        BlockWindows block = new BlockWindows("12:00-13:00, 18:30-19:00", SHANGHAI);
        assertThat(block.blockedAt(at(12, 30))).hasToString("12:00-13:00");
        assertThat(block.blockedAt(at(18, 45))).hasToString("18:30-19:00");
        assertThat(block.blockedAt(at(14, 0))).isNull();
        BlockWindows none = new BlockWindows("", SHANGHAI);
        assertThat(none.blockedAt(at(23, 0))).isNull();
        assertThat(none.nextChange(at(23, 0))).isNull();
        // start == end covers the whole day and never flips
        BlockWindows always = new BlockWindows("00:00-00:00", SHANGHAI);
        assertThat(always.blockedAt(at(15, 0))).isNotNull();
        assertThat(always.nextChange(at(15, 0))).isNull();
    }

    @Test
    void typosFail() {
        assertThatThrownBy(() -> BlockWindows.parse("22-09")).hasMessageContaining("HH:mm-HH:mm");
        assertThatThrownBy(() -> BlockWindows.parse("25:00-09:00")).hasMessageContaining("not a valid time range");
        assertThat(BlockWindows.parse("9:00-9:30")).hasSize(1);
    }
}
