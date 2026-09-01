package com.creed.report.i18n;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The "same content, slightly different format" half of the country axis. */
class CountryFormatterTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 9, 1, 14, 5, 30);

    private final CountryCatalog catalog = new CountryCatalog(new CountryProperties());

    @Test
    void globalKeepsTheOriginalIsoTimestamp() {
        assertThat(timestamp(ReportCountry.GLOBAL, Locale.ENGLISH)).isEqualTo("2026-09-01 14:05:30");
    }

    @Test
    void thailandDatesInTheBuddhistEra() {
        // 2026 CE = 2569 BE. The point of the test is the +543, not the month name.
        assertThat(timestamp(ReportCountry.TH, Locale.ENGLISH)).isEqualTo("01 Sep 2569 14:05:30");
    }

    @Test
    void theBuddhistEraFollowsTheCountryNotTheLanguage() {
        // Viewing Thailand in English still yields Thai-dated documents; viewing Global in Thai
        // does not. This is the asymmetry that makes country a separate axis from language.
        assertThat(timestamp(ReportCountry.TH, Locale.forLanguageTag("th"))).contains("2569");
        assertThat(timestamp(ReportCountry.TH, Locale.ENGLISH)).contains("2569");
        assertThat(timestamp(ReportCountry.GLOBAL, Locale.ENGLISH)).contains("2026");
    }

    @Test
    void thaiTimestampsStayInAsciiDigits() {
        // DecimalStyle is pinned: a locale with its own numerals must not turn a timestamp into
        // glyphs the PDF font stack may not carry.
        assertThat(timestamp(ReportCountry.TH, Locale.forLanguageTag("th")))
                .containsPattern("\\d{2} .+ 2569 14:05:30");
    }

    @Test
    void malaysiaUsesADayFirstTwelveHourClock() {
        // Lower-case "pm" is not a slip: en-MY's CLDR day-period markers differ from en's, which is
        // exactly the kind of regional formatting the country axis exists to pick up.
        assertThat(timestamp(ReportCountry.MY, Locale.ENGLISH)).isEqualTo("01/09/2026 02:05:30 pm");
        // The marker itself comes from the language: Malay writes PTG for the afternoon.
        assertThat(timestamp(ReportCountry.MY, Locale.forLanguageTag("ms")))
                .isEqualTo("01/09/2026 02:05:30 PTG");
    }

    @Test
    void vietnamUsesDayFirstDatesAndDotGrouping() {
        assertThat(timestamp(ReportCountry.VN, Locale.forLanguageTag("vi"))).isEqualTo("01/09/2026 14:05:30");
        assertThat(number(ReportCountry.VN, 1234)).isEqualTo("1.234");
        assertThat(number(ReportCountry.GLOBAL, 1234)).isEqualTo("1,234");
    }

    private String timestamp(ReportCountry country, Locale language) {
        return CountryFormatter.timestamp(WHEN, catalog.profile(country, language));
    }

    private String number(ReportCountry country, long value) {
        return CountryFormatter.number(value, catalog.profile(country, Locale.ENGLISH));
    }
}
