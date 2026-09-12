package com.creed.report.model;

import java.util.List;

/**
 * One approval-status listing: the shape of the document reproduced in
 * {@code creed-report/docs/template.jpg} — a printed bank transaction listing with a filter-criteria
 * block above a four-column table.
 *
 * <p>Deliberately <b>not</b> the dynamic report's shape. {@code DynamicTable} is a rectangle of
 * strings the caller describes; this document is a fixed form: criteria come in labelled pairs laid
 * out four to a row, and a row's account cell is several lines that must stay together in one cell.
 * Modelling it as a dynamic table would have flattened both back into plain cells.
 *
 * <p>A record of records, filled by Jackson from the JSON literal in
 * {@link com.creed.report.controller.ApprovalStatusReportController} — so the template reads fields,
 * not map lookups, and a typo in that JSON fails at parse time rather than rendering blank.
 *
 * @param title    document name; printed in the title band and repeated in the running footnote
 * @param criteria the filter block above the table, in the order it is laid out
 * @param note     the line above the table, e.g. "13 Record(s) (Note: This is a filtered table.)"
 * @param rows     the listing itself
 */
public record ApprovalStatusReport(String title, List<Criterion> criteria, String note, List<Row> rows) {

    public ApprovalStatusReport {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One labelled filter value. The sample prints an unset filter as {@code --} rather than
     * omitting it — the reader has to be able to see that a filter was not applied.
     *
     * @param label the caption, e.g. {@code Bank Reference}
     * @param value the value, or {@code --}
     */
    public record Criterion(String label, String value) {
    }

    /**
     * One listed transaction.
     *
     * @param type          transaction / deposit type
     * @param bankReference the bank's reference
     * @param account       the account block, one entry per printed line (name, number, currency) —
     *                      a list, not a joined string, because the cell wraps on <b>its</b> line
     *                      breaks and not on the renderer's guess about where they belong
     * @param status        the approval status; printed in the accent colour like the sample's
     */
    public record Row(String type, String bankReference, List<String> account, String status) {

        public Row {
            account = account == null ? List.of() : List.copyOf(account);
        }
    }
}
