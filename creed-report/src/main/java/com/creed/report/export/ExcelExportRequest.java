package com.creed.report.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * One export invocation: what to export, in which locale, with the caller's raw request
 * parameters passed through.
 *
 * <p>The {@code parameters} map is what keeps the strategy interface stable across report types
 * that need very different inputs — the server inventory ignores it entirely, while the
 * environment report reads {@code spring.profiles.active} / {@code spring.config.location} /
 * {@code spring.config.additional-location} out of it, exactly as the {@code /environment} page
 * takes them from the query string.
 *
 * @param type        the report to export
 * @param locale      locale for message lookups (never {@code null} after construction)
 * @param parameters  the request's query parameters, defensively copied
 * @param generatedAt the timestamp stamped on the sheet, so every sheet of one export agrees
 */
public record ExcelExportRequest(ReportType type,
                                 Locale locale,
                                 Map<String, String> parameters,
                                 LocalDateTime generatedAt) {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ExcelExportRequest {
        locale = (locale != null) ? locale : Locale.ENGLISH;
        parameters = (parameters != null) ? Map.copyOf(parameters) : Map.of();
        generatedAt = (generatedAt != null) ? generatedAt : LocalDateTime.now();
    }

    public ExcelExportRequest(ReportType type, Locale locale, Map<String, String> parameters) {
        this(type, locale, parameters, LocalDateTime.now());
    }

    /** The request parameter, or {@code defaultValue} when absent or blank. */
    public String parameter(String name, String defaultValue) {
        String value = parameters.get(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /** {@link #generatedAt()} formatted for display in a sheet. */
    public String generatedAtText() {
        return generatedAt.format(TS);
    }
}
