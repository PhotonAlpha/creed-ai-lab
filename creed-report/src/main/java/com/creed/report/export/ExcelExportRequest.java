package com.creed.report.export;

import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.ReportCountry;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * One export invocation: what to export, for which country and in which locale, with the caller's
 * raw request parameters passed through.
 *
 * <p>The {@code parameters} map is what keeps the strategy interface stable across report types
 * that need very different inputs — the server inventory ignores it entirely, while the
 * environment report reads {@code spring.profiles.active} / {@code spring.config.location} /
 * {@code spring.config.additional-location} out of it, exactly as the {@code /environment} page
 * takes them from the query string.
 *
 * @param type        the report to export
 * @param locale      locale for message lookups (never {@code null} after construction)
 * @param country     the country edition being exported: its scope and its date/number formats
 * @param parameters  the request's query parameters, defensively copied
 * @param generatedAt the timestamp stamped on the sheet, so every sheet of one export agrees
 */
public record ExcelExportRequest(ReportType type,
                                 Locale locale,
                                 CountryProfile country,
                                 Map<String, String> parameters,
                                 LocalDateTime generatedAt) {

    public ExcelExportRequest {
        locale = (locale != null) ? locale : Locale.ENGLISH;
        // The locale already carries the country in its region subtag, so a caller that has no
        // profile at hand (tests, direct API use) still gets the right one rather than a mismatch.
        country = (country != null) ? country
                : CountryProfile.of(ReportCountry.byRegion(locale.getCountry()).orElse(ReportCountry.GLOBAL), locale);
        parameters = (parameters != null) ? Map.copyOf(parameters) : Map.of();
        generatedAt = (generatedAt != null) ? generatedAt : LocalDateTime.now();
    }

    public ExcelExportRequest(ReportType type, Locale locale, Map<String, String> parameters) {
        this(type, locale, null, parameters, LocalDateTime.now());
    }

    public ExcelExportRequest(ReportType type, Locale locale, Map<String, String> parameters,
                              LocalDateTime generatedAt) {
        this(type, locale, null, parameters, generatedAt);
    }

    /** The request parameter, or {@code defaultValue} when absent or blank. */
    public String parameter(String name, String defaultValue) {
        String value = parameters.get(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /** {@link #generatedAt()} formatted the way the exported country writes dates. */
    public String generatedAtText() {
        return CountryFormatter.timestamp(generatedAt, country);
    }
}
