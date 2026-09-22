package com.creed.jasper.dynamic;

import java.util.List;

/**
 * Everything that decides what a compiled report <b>is</b>: the template it starts from, the table
 * written into it, whether the document's chrome is kept, and where its rows come from.
 *
 * <p>It is also the compile cache's key, which is why it is a record: two shapes of one template
 * are two different {@code JasperReport}s, and a cache keyed on the file alone would serve one
 * caller's columns to the next. A record of records gets the right {@code equals} for free.
 *
 * @param templateLocation the classpath {@code .jrxml} the design starts from
 * @param layout           the table's geometry
 * @param columns          the table's columns; {@code null} leaves the template's own bands alone
 *                         (the criteria subreport is compiled that way)
 * @param chrome           keep the template's title, page header and page footer. False strips the
 *                         document down to its table — what a CSV or a spreadsheet is
 */
public record ReportShape(String templateLocation, TableDesign layout, List<TableColumn> columns,
                          boolean chrome) {

    public ReportShape {
        if (templateLocation == null || templateLocation.isBlank()) {
            throw new IllegalArgumentException("A report shape needs a template");
        }
        columns = columns == null ? null : List.copyOf(columns);
    }

    /** A template compiled exactly as written — no columns written in, no stripping. */
    public static ReportShape of(String templateLocation) {
        return new ReportShape(templateLocation, null, null, true);
    }

    /** The full document: the template's chrome, and its table given columns. */
    public static ReportShape document(String templateLocation, TableDesign layout,
                                       List<TableColumn> columns) {
        return new ReportShape(templateLocation, layout, columns, true);
    }

    /** The table alone, for an extract format — same template, chrome stripped. */
    public static ReportShape tableOnly(String templateLocation, TableDesign layout,
                                        List<TableColumn> columns) {
        return new ReportShape(templateLocation, layout, columns, false);
    }
}
