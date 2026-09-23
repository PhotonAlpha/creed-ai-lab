package com.creed.jasper.dynamic;

import java.awt.Color;
import java.util.Objects;

/**
 * The look of one generated table cell — face, size, weight, colours, padding and rules — as a
 * <b>value in Java</b> rather than as the name of a {@code <style>} in the jrxml.
 *
 * <p><b>What this replaces.</b> A generated cell used to carry a {@code String} and nothing else:
 * {@code setStyleNameReference("TableHeader")}, with the actual font and colour declared in
 * {@code approval-status.jrxml}. That kept the look in the template, at the price of a name that
 * the compiler never checked — a typo, a renamed style or a template that simply never declared it
 * is not a compile error and not a fill error either. JasperReports resolves an unknown style
 * reference to <i>nothing</i> and prints the cell in the default face, so the failure is a PDF that
 * is quietly the wrong colour and the wrong size. Now the cell carries this record, the style names
 * are gone from both sides, and a style that does not exist does not compile.
 *
 * <p><b>What it costs, so the next reader knows.</b> The table's look is no longer editable by
 * changing a file and reloading — {@code creed.jasper.cache-templates=false} makes an edited jrxml
 * take effect on the next request, and that no longer covers the table. The chrome (title band,
 * criteria block, footnote, page counter) still lives in the template and still works that way.
 *
 * <p><b>Identity-H and embedding are not options here.</b> Every cell is written with them (see
 * {@link TableDesigner}): without them the CJK and Thai glyphs do not reach the PDF at all, which
 * is not a style decision but what embedding a TTF requires.
 *
 * <p>Built by {@link #of} and narrowed with the wither methods, like {@link TableColumn}:
 *
 * <pre>{@code
 * CellStyle.of("Creed Sans", 8f).bold(true)
 *          .forecolor(Color.decode("#1F3864")).backcolor(Color.decode("#DBE5F1"))
 *          .padding(6, 3, 3, 3).rules(GRID, GRID);
 * }</pre>
 *
 * @param fontName    the family the engine resolves per {@code REPORT_LOCALE} (here "Creed Sans")
 * @param fontSize    points
 * @param bold        whether the bold face is asked for — JasperReports does not synthesize it, so
 *                    the family has to declare one or the cell renders blank
 * @param forecolor   text colour, or {@code null} to inherit the report's default style
 * @param backcolor   fill, or {@code null} for a transparent cell; a non-null value also turns the
 *                    element opaque, because a backcolor on a transparent element paints nothing
 * @param padding     the cell's inner padding
 * @param topRule     the colour of the rule above the cell, or {@code null} for none
 * @param bottomRule  the colour of the rule under the cell, or {@code null} for none
 * @param vAlign      where the text sits when the cell is taller than the text
 * @param lineSpacing proportional line spacing for a multi-line cell, or {@code null} for single
 */
public record CellStyle(String fontName, float fontSize, boolean bold, Color forecolor, Color backcolor,
                        Padding padding, Color topRule, Color bottomRule, VAlign vAlign, Float lineSpacing) {

    /** Where the text sits in a cell that is taller than it is. */
    public enum VAlign {
        TOP, MIDDLE
    }

    /** A cell's inner padding, in points. */
    public record Padding(int left, int right, int top, int bottom) {

        public static final Padding NONE = new Padding(0, 0, 0, 0);
    }

    /** The width every rule is drawn at — the sample page's hairline, and the jrxml's old 0.5. */
    public static final float RULE_WIDTH = 0.5f;

    public CellStyle {
        Objects.requireNonNull(fontName, "fontName");
        if (fontName.isBlank()) {
            throw new IllegalArgumentException("A cell style needs a font family");
        }
        if (fontSize <= 0) {
            throw new IllegalArgumentException("A cell style needs a positive font size");
        }
        padding = padding == null ? Padding.NONE : padding;
        vAlign = vAlign == null ? VAlign.MIDDLE : vAlign;
    }

    /** A plain cell in the given face and size: no colours, no padding, no rules. */
    public static CellStyle of(String fontName, float fontSize) {
        return new CellStyle(fontName, fontSize, false, null, null, Padding.NONE, null, null,
                VAlign.MIDDLE, null);
    }

    /**
     * Takes an argument because {@code bold()} is already this record's accessor — the wither and
     * the getter differ by arity, which is legal and reads no worse at a call site.
     */
    public CellStyle bold(boolean on) {
        return new CellStyle(fontName, fontSize, on, forecolor, backcolor, padding, topRule, bottomRule,
                vAlign, lineSpacing);
    }

    public CellStyle forecolor(Color color) {
        return new CellStyle(fontName, fontSize, bold, color, backcolor, padding, topRule, bottomRule,
                vAlign, lineSpacing);
    }

    public CellStyle backcolor(Color color) {
        return new CellStyle(fontName, fontSize, bold, forecolor, color, padding, topRule, bottomRule,
                vAlign, lineSpacing);
    }

    public CellStyle padding(int left, int right, int top, int bottom) {
        return new CellStyle(fontName, fontSize, bold, forecolor, backcolor,
                new Padding(left, right, top, bottom), topRule, bottomRule, vAlign, lineSpacing);
    }

    /** The rules above and under the cell; either may be {@code null}. */
    public CellStyle rules(Color top, Color bottom) {
        return new CellStyle(fontName, fontSize, bold, forecolor, backcolor, padding, top, bottom,
                vAlign, lineSpacing);
    }

    public CellStyle vAlign(VAlign alignment) {
        return new CellStyle(fontName, fontSize, bold, forecolor, backcolor, padding, topRule, bottomRule,
                alignment, lineSpacing);
    }

    /** Proportional line spacing — 1.25 means a quarter of a line of extra leading. */
    public CellStyle lineSpacing(float size) {
        return new CellStyle(fontName, fontSize, bold, forecolor, backcolor, padding, topRule, bottomRule,
                vAlign, size);
    }

    /** The same style at another size, for a cell that has to hold more in the same width. */
    public CellStyle fontSize(float size) {
        return new CellStyle(fontName, size, bold, forecolor, backcolor, padding, topRule, bottomRule,
                vAlign, lineSpacing);
    }
}
