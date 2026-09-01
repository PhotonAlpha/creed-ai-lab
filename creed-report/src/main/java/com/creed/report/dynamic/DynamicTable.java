package com.creed.report.dynamic;

import java.util.List;

/**
 * A table whose shape came from the request rather than from code: the columns are whatever
 * {@code headers} named, the rows whatever the {@code data} JSON held.
 *
 * <p>Every cell carries both its {@link Cell#value() raw} value and its {@link Cell#text()
 * country-formatted} rendering, because the two consumers want different things — the Thymeleaf
 * templates print the text, while the Excel export wants real numbers in numeric cells so a
 * spreadsheet can still sum and sort them.
 *
 * @param title   heading shown above the table
 * @param columns the resolved columns, in the order the request listed them
 * @param rows    one entry per data row, each as long as {@link #columns()}
 */
public record DynamicTable(String title, List<Column> columns, List<List<Cell>> rows) {

    public DynamicTable {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** Number of data rows — what the page's total badge shows. */
    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * One column.
     *
     * @param key   the JSON field this column reads, and the {@code report.col.<key>} message code
     * @param label the header text actually rendered, already localized
     */
    public record Column(String key, String label) {
    }

    /**
     * One cell.
     *
     * @param value the raw JSON value ({@code null}, a number, a boolean or a string)
     * @param text  {@code value} rendered for the country — grouped numbers, localized booleans
     */
    public record Cell(Object value, String text) {

        /**
         * What the Excel export writes: numbers stay numbers so the sheet keeps its arithmetic,
         * everything else goes in as the same text the page shows.
         */
        public Object excelValue() {
            return (value instanceof Number number) ? number : text;
        }
    }
}
