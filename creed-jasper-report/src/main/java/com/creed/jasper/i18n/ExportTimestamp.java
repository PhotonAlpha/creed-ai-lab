package com.creed.jasper.i18n;

import java.time.LocalDateTime;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;

/**
 * The two halves of the footer's export stamp, formatted the way {@code creed-report}'s
 * {@code CountryFormatter.date/time} formats them — same {@link FormatStyle#MEDIUM} localized
 * patterns, same pinned {@link DecimalStyle}, same Thai era — so the two modules' footers can be
 * compared without the timestamps being a difference of their own.
 *
 * <p>Date and time are formatted <b>separately</b> rather than split out of one pattern, because
 * the footer prints them either side of a divider and splitting a pattern string is a guess.
 * MEDIUM rather than SHORT: {@code 9/1/26} reads differently on either side of the Atlantic, which
 * is not what a document dates itself with.
 */
public final class ExportTimestamp {

    private ExportTimestamp() {
    }

    /** {@code Sep 11, 2026} / {@code 11 ก.ย. 2569} — the left half of the footer's first line. */
    public static String date(LocalDateTime when, ReportLanguage language) {
        return format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM), when, language);
    }

    /** {@code 5:52:27 PM} — the right half, to the second. */
    public static String time(LocalDateTime when, ReportLanguage language) {
        return format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM), when, language);
    }

    private static String format(DateTimeFormatter formatter, LocalDateTime when, ReportLanguage language) {
        DateTimeFormatter localized = formatter
                .withLocale(language.locale())
                // Pinned: a locale with its own digits would otherwise turn a timestamp into
                // numerals the embedded face may not even carry.
                .withDecimalStyle(DecimalStyle.STANDARD);
        if (language.isBuddhistCalendar()) {
            localized = localized.withChronology(ThaiBuddhistChronology.INSTANCE);
        }
        return localized.format(when);
    }
}
