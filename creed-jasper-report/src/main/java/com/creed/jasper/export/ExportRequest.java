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
 * @param widths            how the column widths are decided — measured from the content
 *                          ({@link ColumnWidths#AUTO}) or the report's declared weights
 *                          ({@link ColumnWidths#FIXED}). Applies to every format: a spreadsheet's
 *                          column widths come from the same fit
 * @param customViewColumns the columns to print, by field name, <b>in the caller's order</b> —
 *                          empty means the report's own full column list. Honoured only for a
 *                          format that {@linkplain ExportFormat#carriesChrome() carries chrome},
 *                          exactly as in the reference implementation: a spreadsheet or a CSV is a
 *                          data extract and is expected to carry every column, whatever a screen
 *                          happens to be showing
 */
public record ExportRequest(ExportFormat format, ReportLanguage language, ColumnWidths widths,
                            List<String> customViewColumns) {

    public ExportRequest {
        if (format == null) {
            throw new IllegalArgumentException("An export needs a format");
        }
        language = language == null ? ReportLanguage.EN : language;
        widths = widths == null ? ColumnWidths.AUTO : widths;
        customViewColumns = customViewColumns == null ? List.of() : List.copyOf(customViewColumns);
    }

    /** The whole report, in this format and language, with content-fitted columns. */
    public static ExportRequest of(ExportFormat format, ReportLanguage language) {
        return new ExportRequest(format, language, ColumnWidths.AUTO, List.of());
    }

    /** The whole report, with the columns laid out the given way. */
    public static ExportRequest of(ExportFormat format, ReportLanguage language, ColumnWidths widths) {
        return new ExportRequest(format, language, widths, List.of());
    }

    /** Whether the columns are to be measured from the content rather than taken as declared. */
    public boolean fitsColumns() {
        return widths == ColumnWidths.AUTO;
    }

    /** Whether the caller chose a column subset that this format will actually honour. */
    public boolean hasCustomView() {
        return format.carriesChrome() && !customViewColumns.isEmpty();
    }
}
