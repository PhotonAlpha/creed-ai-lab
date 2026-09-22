package com.creed.jasper.dynamic;

import java.awt.Color;

/**
 * The geometry of a generated table — everything {@link TableDesigner} needs that is not a column.
 *
 * <p>Kept to three values on purpose. Padding, background, rules between rows, fonts and the
 * per-locale weights all stay in the jrxml styles named below, where they can be read next to the
 * rest of the document's look; only what the design API cannot take from a style at design time
 * ends up here.
 *
 * @param headerHeight the caption row's height
 * @param rowHeight    a detail row's <b>base</b> height. A multiline column grows it; the other
 *                     cells stretch to match. Size it for the tallest script, not for Latin — a
 *                     text element shorter than its face's line height prints nothing at all (the
 *                     main template's header comment has the numbers)
 * @param edgeColor    the table's outer left and right rules. The only colour here, and only
 *                     because it belongs to the table's first and last column rather than to any
 *                     one style: a style is resolved per element, "am I the last column" is not
 *                     something a style can know
 * @param headerStyle  jrxml style for every caption cell
 * @param cellStyle    jrxml style for a detail cell that names none of its own
 */
public record TableDesign(int headerHeight, int rowHeight, Color edgeColor,
                          String headerStyle, String cellStyle) {

    public TableDesign {
        if (headerHeight <= 0 || rowHeight <= 0) {
            throw new IllegalArgumentException("A table needs positive header and row heights");
        }
        if (headerStyle == null || cellStyle == null) {
            throw new IllegalArgumentException("A table needs a header style and a cell style");
        }
    }
}
