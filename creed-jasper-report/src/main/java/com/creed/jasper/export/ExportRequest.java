package com.creed.jasper.export;

import com.creed.jasper.i18n.ReportLanguage;

import java.util.List;

/**
 * What a caller asks for: a format, a language, and optionally which columns to print.
 *
 * <p>The migrated form of the reference implementation's {@code ExportRequest} — the object its
 * whole renderer branches on ({@code getReportFormat()}, {@code getCustomViewColumns()}).
 *
 * @param format            the output format
 * @param language          the language the fill runs in; already normalised, see
 *                          {@link ReportLanguage}
 * @param customViewColumns the columns to print, by field name, <b>in the caller's order</b> —
 *                          empty means the report's own full column list. Honoured only for a
 *                          format that {@linkplain ExportFormat#carriesChrome() carries chrome},
 *                          exactly as in the reference implementation: a spreadsheet or a CSV is a
 *                          data extract and is expected to carry every column, whatever a screen
 *                          happens to be showing
 */
public record ExportRequest(ExportFormat format, ReportLanguage language, List<String> customViewColumns) {

    public ExportRequest {
        if (format == null) {
            throw new IllegalArgumentException("An export needs a format");
        }
        language = language == null ? ReportLanguage.EN : language;
        customViewColumns = customViewColumns == null ? List.of() : List.copyOf(customViewColumns);
    }

    /** The whole report, in this format and language. */
    public static ExportRequest of(ExportFormat format, ReportLanguage language) {
        return new ExportRequest(format, language, List.of());
    }

    /** Whether the caller chose a column subset that this format will actually honour. */
    public boolean hasCustomView() {
        return format.carriesChrome() && !customViewColumns.isEmpty();
    }
}
