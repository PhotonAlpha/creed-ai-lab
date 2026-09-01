package com.creed.report.dynamic;

import java.util.Map;

/**
 * The raw inputs of a dynamic report, as they arrive on the wire.
 *
 * <p>Exists so the page, the HTML/PDF exports and the Excel strategy all read the <b>same three
 * parameter names</b> from whatever carried them — a query string on a link, a form POST from the
 * page, or {@link com.creed.report.export.ExcelExportRequest#parameters()} for the Excel export.
 *
 * @param title   optional heading; the page falls back to a localized default
 * @param headers comma-separated column tokens (see {@link DynamicTableService})
 * @param data    the rows, as a JSON array
 */
public record DynamicTableRequest(String title, String headers, String data) {

    public static final String TITLE_PARAM = "title";
    public static final String HEADERS_PARAM = "headers";
    public static final String DATA_PARAM = "data";

    /** Reads the three parameters out of a request's raw parameter map. */
    public static DynamicTableRequest from(Map<String, String> parameters) {
        return new DynamicTableRequest(parameters.get(TITLE_PARAM),
                parameters.get(HEADERS_PARAM), parameters.get(DATA_PARAM));
    }

    /** Whether the caller supplied anything at all — a bare page visit has not. */
    public boolean isBlank() {
        return (headers == null || headers.isBlank()) && (data == null || data.isBlank());
    }
}
