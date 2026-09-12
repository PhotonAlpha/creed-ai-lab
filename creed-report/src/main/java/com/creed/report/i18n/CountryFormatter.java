package com.creed.report.i18n;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;

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

    /**
     * The date half of a timestamp, for layouts that print the two apart — the statement chrome's
     * footnote writes "Date of Export: … | Time of Export: …" the way the sample document does.
     *
     * <p>Localized rather than pattern-driven: {@link CountryProfile#datePattern()} is one pattern
     * covering both halves, and splitting a pattern string is a guess. A localized style gives each
     * locale its own field order without one, and the country's calendar still applies, so Thailand
     * dates in the Buddhist era here too. {@link FormatStyle#MEDIUM} rather than {@code SHORT}
     * because {@code SHORT} is {@code 7/24/26} in English — a two-digit year and an order that
     * reads differently on either side of the Atlantic is not what a document dates itself with.
     */
    public static String date(LocalDateTime when, CountryProfile profile) {
        return format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM), when, profile);
    }

    /** The time half of the same timestamp, to the second ({@code 17:52:27}). */
    public static String time(LocalDateTime when, CountryProfile profile) {
        return format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM), when, profile);
    }

    private static String format(DateTimeFormatter formatter, LocalDateTime when, CountryProfile profile) {
        DateTimeFormatter localized = formatter
                .withLocale(profile.locale())
                .withDecimalStyle(DecimalStyle.STANDARD);
        if (profile.calendar() == ReportCountry.Calendar.BUDDHIST) {
            localized = localized.withChronology(ThaiBuddhistChronology.INSTANCE);
        }
        return localized.format(when);
    }

    /** Formats a count with the country's grouping separators ({@code 1.234} in vi-VN). */
    public static String number(long value, CountryProfile profile) {
        return NumberFormat.getIntegerInstance(profile.numberLocale()).format(value);
    }
}
