package com.creed.report.dynamic;

import java.util.Map;

/**
 * The raw inputs of a dynamic report, as they arrive on the wire.
 *
 * <p>Exists so the page, the HTML/PDF exports and the Excel strategy all read the <b>same
 * parameter names</b> from whatever carried them — a query string on a link, a form POST from the
 * page, or {@link com.creed.report.export.ExcelExportRequest#parameters()} for the Excel export.
 *
 * @param title    optional heading; the page falls back to a localized default
 * @param headers  comma-separated column tokens (see {@link DynamicTableService})
 * @param data     the rows, as a JSON array
 * @param template optional PDF layout code (see {@link DynamicReportTemplate}); blank = the default
 */
public record DynamicTableRequest(String title, String headers, String data, String template) {

    public static final String TITLE_PARAM = "title";
    public static final String HEADERS_PARAM = "headers";
    public static final String DATA_PARAM = "data";

    /** The table alone, printed on the default paper — what every caller wrote before layouts. */
    public DynamicTableRequest(String title, String headers, String data) {
        this(title, headers, data, null);
    }

    /** Reads the definition out of a request's raw parameter map. */
    public static DynamicTableRequest from(Map<String, String> parameters) {
        return new DynamicTableRequest(parameters.get(TITLE_PARAM), parameters.get(HEADERS_PARAM),
                parameters.get(DATA_PARAM), parameters.get(DynamicReportTemplate.TEMPLATE_PARAM));
    }

    /**
     * The layout this definition asks to be printed in. Resolved here rather than stored, so the
     * record keeps holding exactly what arrived and an unknown code is refused (400) wherever the
     * definition is actually used — including the page, which is where a caller sees the mistake.
     */
    public DynamicReportTemplate reportTemplate() {
        return DynamicReportTemplate.of(template);
    }

    /** Whether the caller supplied anything at all — a bare page visit has not. */
    public boolean isBlank() {
        return (headers == null || headers.isBlank()) && (data == null || data.isBlank());
    }
}
