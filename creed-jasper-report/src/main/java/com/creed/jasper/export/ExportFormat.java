package com.creed.jasper.export;

import java.util.Locale;
import java.util.Optional;

/**
 * The output formats a report can be exported in — the migrated form of the reference
 * implementation's {@code ExportConstants.FORMAT_*} strings.
 *
 * <p>An enum rather than strings because two decisions hang off the format and both were
 * {@code equalsIgnoreCase} checks scattered through the renderer there:
 *
 * <ul>
 *   <li><b>{@link #paginated()}</b> — a PDF is pages; a spreadsheet and a CSV are not. The
 *       unpaginated ones are filled with {@code JRParameter.IS_IGNORE_PAGINATION}, or the export
 *       carries a page break, a repeated caption row and a page footer into the middle of the
 *       data.</li>
 *   <li><b>{@link #carriesChrome()}</b> — only the PDF is a <i>document</i>. The others are the
 *       table and nothing else: no logo, no title band, no criteria block, no footer. That is why
 *       the reference implementation loads its jrxml template only for PDF, and it is why
 *       {@code TableDesigner.stripChrome} exists here.</li>
 * </ul>
 *
 * @param code      the wire value on {@code ?format=}
 * @param mediaType what the response is served as
 * @param extension the download's file extension
 */
public enum ExportFormat {

    /** The document: chrome, pagination, embedded fonts. */
    PDF("pdf", "application/pdf", "pdf", true, true),

    /** The table as a spreadsheet. JasperReports writes the OOXML itself — POI is not needed. */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", false, false),

    /** The table as text. Exported with a BOM, so Excel opens UTF-8 without mangling it. */
    CSV("csv", "text/csv; charset=UTF-8", "csv", false, false),

    /** The table as HTML — the cheapest way to look at a fill without a PDF viewer. */
    HTML("html", "text/html; charset=UTF-8", "html", false, false);

    private final String code;
    private final String mediaType;
    private final String extension;
    private final boolean paginated;
    private final boolean chrome;

    ExportFormat(String code, String mediaType, String extension, boolean paginated, boolean chrome) {
        this.code = code;
        this.mediaType = mediaType;
        this.extension = extension;
        this.paginated = paginated;
        this.chrome = chrome;
    }

    public String code() {
        return code;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /** Whether the fill lays the report out in pages; false sets {@code IS_IGNORE_PAGINATION}. */
    public boolean paginated() {
        return paginated;
    }

    /** Whether the export keeps the template's chrome, or is the bare table. */
    public boolean carriesChrome() {
        return chrome;
    }

    /**
     * Resolves a wire value. {@link Optional#empty()} rather than a default, so a caller that sent
     * {@code ?format=pdff} is told rather than quietly handed a PDF — the repo's rule that bad
     * input is a 400 and never a surprise.
     */
    public static Optional<ExportFormat> of(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat format : values()) {
            if (format.code.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
