package com.creed.jasper.dynamic;

import com.creed.jasper.report.FieldDataSource;
import net.sf.jasperreports.engine.JRDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The data source for a generated table — built from <b>the same column list</b> that generated the
 * bands.
 *
 * <p>That is the whole point of it. A generated table has three things that must agree: the fields
 * the design declares, the cells that reference them, and the source that answers for them. The
 * first two come from {@link TableDesigner}; if the third came from anywhere else, a renamed column
 * would produce a report that fills perfectly and prints an empty column. Here one
 * {@code List<TableColumn>} produces all three, so they cannot disagree.
 *
 * <p>Rows are maps because a caller-defined table has no class to bind to. A key a row does not
 * carry is an <b>empty cell</b>, not an error: for a table whose shape the caller chose, a sparse
 * row is normal. (That is the opposite of {@link FieldDataSource}'s treatment of an unmapped
 * <i>field</i>, which is a template and a data source that have drifted apart and does throw.)
 */
public final class TableRows {

    private TableRows() {
    }

    /** A data source over {@code rows}, answering exactly the fields {@code columns} declares. */
    public static JRDataSource of(List<Map<String, Object>> rows, List<TableColumn> columns) {
        Map<String, Function<Map<String, Object>, Object>> fields = new LinkedHashMap<>();
        for (TableColumn column : columns) {
            String property = column.property();
            fields.put(property, row -> row.get(property));
        }
        return FieldDataSource.of(rows, fields);
    }
}
