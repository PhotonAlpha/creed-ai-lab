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
    SERVER_INVENTORY("server", "report.type.server", "creed-server-report"),

    /** Environment inspector — profiles, effective properties and property sources. */
    ENVIRONMENT("environment", "report.type.environment", "creed-environment-report");

    private final String code;
    private final String titleKey;
    private final String filePrefix;

    ReportType(String code, String titleKey, String filePrefix) {
        this.code = code;
        this.titleKey = titleKey;
        this.filePrefix = filePrefix;
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
