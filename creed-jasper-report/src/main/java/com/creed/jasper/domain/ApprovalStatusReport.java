package com.creed.jasper.domain;

import java.util.List;

/**
 * One approval-status listing — the same shape as {@code creed-report}'s
 * {@code com.creed.report.model.ApprovalStatusReport}, and deliberately so: the two modules render
 * the same document through different engines, so they must not be able to disagree about what the
 * document <i>is</i>.
 *
 * <p>A printed bank transaction listing: a filter-criteria block of labelled pairs laid out four to
 * a row, above a four-column table whose account cell is several lines that belong together.
 *
 * <p>Filled by Jackson from the JSON literal in {@link com.creed.jasper.service.ApprovalStatusSamples}
 * — a typo there fails at parse time rather than rendering a blank cell.
 *
 * <p><b>Records and JasperReports.</b> These accessors are {@code label()}, not {@code getLabel()},
 * so {@code JRBeanCollectionDataSource} — which reads beans through commons-beanutils — cannot see
 * them. That is why the fills go through {@link com.creed.jasper.report.FieldDataSource}, where the
 * jrxml's field names are mapped to component accessors explicitly.
 *
 * @param title    document name; printed in the title band and repeated in the page footer
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
     * One labelled filter value. An unset filter prints {@code --} rather than disappearing — the
     * reader has to be able to tell a filter that was not applied from one that was never offered.
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
     *                      a list, not a joined string, because the cell breaks on <b>its</b> lines
     *                      and not where the renderer would wrap it
     * @param status        the approval status; printed in the accent colour like the sample's
     */
    public record Row(String type, String bankReference, List<String> account, String status) {

        public Row {
            account = account == null ? List.of() : List.copyOf(account);
        }

        /**
         * The account block as one string, newline-separated — what the jrxml's stretching text
         * field takes. Joining happens <b>here</b> rather than in the template because the list is
         * the truth and the join is a rendering detail of this particular engine.
         */
        public String accountText() {
            return String.join("\n", account);
        }
    }
}
