package com.creed.jasper.dynamic;

/**
 * One generated table column — the unit {@link TableDesigner} turns into a header cell, a detail
 * cell and a report field.
 *
 * <p>This is the whole column model: what a column <b>is</b> (a field, a caption, a share of the
 * width) and, for a column that does not take the table's default, what it <b>looks like</b> — a
 * {@link CellStyle}, not the name of a style in the jrxml. The look used to stay in the template
 * and arrive here as a {@code String}; {@link CellStyle} says what moved and why.
 *
 * @param property   the field name; the detail cell's expression is {@code $F{property}} and the
 *                   data source is asked for exactly this name
 * @param header     the caption, already resolved to display text — a caller that wants it
 *                   localized resolves it before building the column, because this module's
 *                   captions are a mix of message keys and payload strings
 * @param weight     this column's share of the available width. Relative, not absolute: the
 *                   designer normalises the weights over {@code columnWidth} and gives the
 *                   rounding remainder to the last column, so the columns always fill the page
 *                   exactly instead of leaving a one-point gap at the right edge
 * @param align      horizontal alignment of both the caption and the cell
 * @param stretches  whether the cell holds several lines that belong together (an address, an
 *                   account block). A multiline column stretches the row and every other cell
 *                   stretches with it; a single-line one does not
 * @param cellStyle  the style this column's detail cell takes, or {@code null} for the layout's
 *                   default. The caption always takes the layout's header style
 * @param valueClass the field's declared type; {@code String} unless a column needs a pattern or
 *                   a spreadsheet export to keep it numeric
 */
public record TableColumn(String property, String header, int weight, Align align,
                          boolean stretches, CellStyle cellStyle, Class<?> valueClass) {

    /** Horizontal alignment, kept as our own enum so callers never import a JasperReports type. */
    public enum Align {
        LEFT, CENTER, RIGHT
    }

    public TableColumn {
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("A table column needs a field name");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("Column '" + property + "' needs a positive weight");
        }
        header = header == null ? property : header;
        align = align == null ? Align.LEFT : align;
        valueClass = valueClass == null ? String.class : valueClass;
    }

    /** A plain left-aligned string column. The other properties are added with the withers below. */
    public static TableColumn of(String property, String header, int weight) {
        return new TableColumn(property, header, weight, Align.LEFT, false, null, String.class);
    }

    /** This column's cell holds several lines and stretches the row. */
    public TableColumn multiline() {
        return new TableColumn(property, header, weight, align, true, cellStyle, valueClass);
    }

    /** This column's cell takes its own style instead of the layout's default. */
    public TableColumn styled(CellStyle style) {
        return new TableColumn(property, header, weight, align, stretches, style, valueClass);
    }

    /** This column is aligned other than left. */
    public TableColumn aligned(Align alignment) {
        return new TableColumn(property, header, weight, alignment, stretches, cellStyle, valueClass);
    }

    /** This column's field is not a String. */
    public TableColumn typed(Class<?> type) {
        return new TableColumn(property, header, weight, align, stretches, cellStyle, type);
    }
}
