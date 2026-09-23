package com.creed.jasper.dynamic;

import java.awt.Color;

/**
 * The geometry <b>and the look</b> of a generated table — everything {@link TableDesigner} needs
 * that is not a column.
 *
 * <p>The two styles used to be the <i>names</i> of {@code <style>} elements in the jrxml, resolved
 * by the engine at fill time. They are {@link CellStyle} values now: the face, the size, the
 * colours, the padding and the rules are written onto the generated elements by the designer, and
 * a style that does not exist is a compile error rather than a cell that silently prints in the
 * default face. {@link CellStyle} has the rest of that trade.
 *
 * @param headerHeight the caption row's height
 * @param rowHeight    a detail row's <b>base</b> height. A multiline column grows it; the other
 *                     cells stretch to match. Size it for the tallest script, not for Latin — a
 *                     text element shorter than its face's line height prints nothing at all (the
 *                     main template's header comment has the numbers)
 * @param edgeColor    the table's outer left and right rules. Separate from the styles, and only
 *                     because it belongs to the table's first and last column rather than to any
 *                     one cell: "am I the last column" is not something a style can know
 * @param headerStyle  every caption cell
 * @param cellStyle    a detail cell that carries none of its own
 */
public record TableDesign(int headerHeight, int rowHeight, Color edgeColor,
                          CellStyle headerStyle, CellStyle cellStyle) {

    /** The style a column's detail cell takes: its own if it carries one, else this layout's. */
    public CellStyle cellStyle(TableColumn column) {
        return column.cellStyle() == null ? cellStyle : column.cellStyle();
    }

    public TableDesign {
        if (headerHeight <= 0 || rowHeight <= 0) {
            throw new IllegalArgumentException("A table needs positive header and row heights");
        }
        if (headerStyle == null || cellStyle == null) {
            throw new IllegalArgumentException("A table needs a header style and a cell style");
        }
    }
}
