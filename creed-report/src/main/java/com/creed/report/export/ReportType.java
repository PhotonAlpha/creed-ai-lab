package com.creed.report.export;

import java.util.Locale;

/**
 * The kinds of report the module can export — the key the export strategies are selected by.
 *
 * <p>{@link #code()} is the stable wire value used on {@code /export/excel?type=...} and in the
 * report page's dropdown; the enum constant name is free to change without breaking links.
 * {@link #titleKey()} resolves through the {@code report.*} bundle so the picker and the sheet
 * titles are localized like the rest of the module.
 *
 * <p>Adding a report type means adding a constant here and one {@link ExcelReportExporter} bean —
 * {@link ExcelExportService} discovers it, and the report page lists it automatically.
 */
public enum ReportType {

    /** Server inventory — the table shown on {@code /report}. */
    SERVER_INVENTORY("server", "report.type.server", "creed-server-report", true),

    /** Environment inspector — profiles, effective properties and property sources. */
    ENVIRONMENT("environment", "report.type.environment", "creed-environment-report", true),

    /**
     * A table the caller describes: columns from {@code headers}, rows from {@code data} JSON.
     * Unlike the other two it has no data of its own, so it only makes sense with those parameters
     * attached — which is why {@code /dynamic}'s Excel button posts them rather than linking.
     */
    DYNAMIC("dynamic", "report.type.dynamic", "creed-dynamic-report", false);

    private final String code;
    private final String titleKey;
    private final String filePrefix;
    private final boolean linkable;

    ReportType(String code, String titleKey, String filePrefix, boolean linkable) {
        this.code = code;
        this.titleKey = titleKey;
        this.filePrefix = filePrefix;
        this.linkable = linkable;
    }

    /** Request/URL value identifying this type. */
    public String code() {
        return code;
    }

    /** Message key for the human-readable report title. */
    public String titleKey() {
        return titleKey;
    }

    /** Download filename prefix; the caller appends a timestamp and the extension. */
    public String filePrefix() {
        return filePrefix;
    }

    /**
     * Whether a bare {@code /export/excel?type=<code>} link produces a report.
     *
     * <p>False for a type that has no data of its own — {@link #DYNAMIC} needs the caller's
     * {@code headers}/{@code data} posted with it — so a menu built from
     * {@link ExcelExportService#linkableTypes()} cannot offer a link that would only answer 400.
     * ({@link #ENVIRONMENT} takes parameters too, but every one of them has a default.)
     */
    public boolean linkable() {
        return linkable;
    }

    /**
     * Resolves a request value (case-insensitive) to a type.
     *
     * @throws UnknownReportTypeException if no type carries that code — mapped to 400 rather than
     *                                    500, since it is bad input and not a server fault
     */
    public static ReportType of(String code) {
        if (code != null) {
            String normalized = code.trim().toLowerCase(Locale.ROOT);
            for (ReportType type : values()) {
                if (type.code.equals(normalized)) {
                    return type;
                }
            }
        }
        throw new UnknownReportTypeException(code);
    }
}
