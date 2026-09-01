package com.creed.report.i18n;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;

/**
 * The country half of "same content, slightly different format": timestamps and counts rendered
 * the way the selected country writes them.
 *
 * <p>Static because it is pure formatting over a {@link CountryProfile} — the configurable part
 * (pattern, calendar, grouping locale) already lives in the profile, so there is nothing to inject
 * and {@link com.creed.report.export.ExcelExportRequest} can call it from a record accessor.
 */
public final class CountryFormatter {

    private CountryFormatter() {
    }

    /**
     * Formats a timestamp for a country.
     *
     * <p>{@link ReportCountry.Calendar#BUDDHIST} converts the date before formatting, so Thailand
     * gets 2569 where the ISO calendar gets 2026 — the era is a property of the country, not of the
     * language, so it applies to {@code ?country=th&lang=en} too. {@link DecimalStyle#STANDARD} is
     * pinned so a locale that has its own digits cannot turn a timestamp into non-ASCII numerals.
     */
    public static String timestamp(LocalDateTime when, CountryProfile profile) {
        DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern(profile.datePattern(), profile.locale())
                .withDecimalStyle(DecimalStyle.STANDARD);
        if (profile.calendar() == ReportCountry.Calendar.BUDDHIST) {
            formatter = formatter.withChronology(ThaiBuddhistChronology.INSTANCE);
        }
        return formatter.format(when);
    }

    /** Formats a count with the country's grouping separators ({@code 1.234} in vi-VN). */
    public static String number(long value, CountryProfile profile) {
        return NumberFormat.getIntegerInstance(profile.numberLocale()).format(value);
    }
}
