package com.creed.jasper.dynamic;

import net.sf.jasperreports.components.table.DesignCell;
import net.sf.jasperreports.components.table.StandardColumn;
import net.sf.jasperreports.components.table.StandardTable;
import net.sf.jasperreports.engine.JRDataset;
import net.sf.jasperreports.engine.JRLineBox;
import net.sf.jasperreports.engine.JRElement;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.design.JRDesignComponentElement;
import net.sf.jasperreports.engine.design.JRDesignDataset;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignField;
import net.sf.jasperreports.engine.design.JRDesignStaticText;
import net.sf.jasperreports.engine.design.JRDesignTextElement;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.type.HorizontalTextAlignEnum;
import net.sf.jasperreports.engine.type.LineSpacingEnum;
import net.sf.jasperreports.engine.type.ModeEnum;
import net.sf.jasperreports.engine.type.StretchTypeEnum;
import net.sf.jasperreports.engine.type.TextAdjustEnum;
import net.sf.jasperreports.engine.type.VerticalTextAlignEnum;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds the columns to the {@code <jr:table>} in a template's {@code <detail>} band, at compile
 * time, from a list of {@link TableColumn}s — <b>the columns are data, the rest of the report is
 * not</b>.
 *
 * <h2>What is dynamic, and what deliberately is not</h2>
 *
 * The jrxml lays out the whole document — page box, page header, title, criteria block, page
 * footer and the styles those bands use — and declares the table as an empty skeleton: a
 * {@code <componentElement>} holding a {@code <jr:table>} with a {@code datasetRun} and no
 * columns. (A table with no columns is schema-valid; that is what makes the skeleton legal.) This
 * class fills in the one hole:
 *
 * <pre>
 *   the jrxml        the page, the chrome, the chrome's styles, the subDataset, the empty table
 *   the column list  which columns the table has: field, caption, width share, cell style
 *   the TableDesign  what the table LOOKS like: two CellStyles, the two heights, the outer rule
 * </pre>
 *
 * <p><b>The table's look is written here, not resolved from the template.</b> A generated cell used
 * to carry {@code setStyleNameReference("TableHeader")} and let the engine find that style in the
 * jrxml at fill time; it now carries a {@link CellStyle} and {@link #apply} writes the face, the
 * size, the weight, the colours, the padding and the rules straight onto the element. What that
 * buys and what it costs is on {@link CellStyle} — the short version is that a style name is a
 * string nothing checks, and an unresolvable one prints a cell in the default face rather than
 * failing. The table's outer left/right rule stays out of the style, because it belongs to
 * <i>being</i> the first or last column rather than to any cell.
 *
 * <h2>Why the table component rather than generated bands</h2>
 *
 * Because the component already is a table: it paginates itself and repeats its own caption row on
 * every page. Generating a {@code columnHeader} band and a {@code detail} band instead means two
 * bands to keep in step, a report whose detail band is the row, and a template you cannot read the
 * layout out of. Here the report keeps one ordinary detail band that runs <b>once</b> — the main
 * dataset is a single empty record — and everything about rows is the table's business.
 *
 * <h2>The two invariants</h2>
 *
 * <ul>
 *   <li><b>The subDataset's fields are exactly the columns.</b> JasperReports asks a data source
 *       for every field the dataset declares, not only the ones an expression references, so a
 *       field left over from another shape is requested at fill time and answered by a source
 *       built from the current columns. The contract in the other direction: the template declares
 *       no fields of its own.</li>
 *   <li><b>The widths fill the table exactly.</b> The weights are normalised over the component
 *       element's width and the rounding remainder goes to the last column, so the row ends where
 *       the table does instead of leaving a gap the eye finds immediately.</li>
 * </ul>
 *
 * <p>Stateless; the design it is handed is mutated in place and then compiled by the caller.
 */
public final class TableDesigner {

    /** The {@code key} on the {@code <componentElement>} the columns are written into. */
    public static final String TABLE_KEY = "listing";

    /** The {@code <subDataset>} that table runs over, and whose fields are the columns. */
    public static final String SUBDATASET = "listing";

    /**
     * Written onto every generated cell. Identity-H plus an embedded face is what puts the CJK and
     * Thai glyphs in the PDF at all — the alternative is not a fallback face, it is nothing.
     */
    private static final String PDF_ENCODING = "Identity-H";

    private TableDesigner() {
    }

    /**
     * Writes {@code columns} into the template's table, replacing whatever was there.
     *
     * @throws IllegalArgumentException if the columns are empty or two share a field name — a
     *                                  duplicate would silently make one column shadow the other,
     *                                  which is the kind of thing a generated table has to refuse
     *                                  rather than render
     * @throws IllegalStateException    if the template has no table skeleton to fill in
     */
    public static void write(JasperDesign design, TableDesign layout, List<TableColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("A generated table needs at least one column");
        }
        Set<String> names = new LinkedHashSet<>();
        for (TableColumn column : columns) {
            if (!names.add(column.property())) {
                throw new IllegalArgumentException("Duplicate column field '" + column.property()
                        + "'; the columns are " + names);
            }
        }

        JRDesignComponentElement element = tableElement(design);
        StandardTable table = (StandardTable) element.getComponent();

        // The table fills the content area, which the chrome-stripped design widens by both
        // margins -- so this is read now rather than trusted from the jrxml. The width the template
        // declares on the <componentElement> is a skeleton value that never survives this line:
        // editing it there changes nothing, and the number that decides how wide the table is is
        // the page box's columnWidth (or, for a stripped design, the whole page).
        element.setWidth(design.getColumnWidth());

        declareFields(design, columns);
        int[] widths = widths(element.getWidth(), columns);

        table.setColumns(new ArrayList<>());
        for (int i = 0; i < columns.size(); i++) {
            table.addColumn(column(layout, columns.get(i), widths[i], i, columns.size()));
        }
    }

    /** One column: a caption cell and a detail cell, each holding one element that fills it. */
    private static StandardColumn column(TableDesign layout, TableColumn column, int width,
                                         int index, int count) {
        StandardColumn standard = new StandardColumn();
        standard.setWidth(width);

        JRDesignStaticText caption = new JRDesignStaticText();
        caption.setText(column.header());
        standard.setColumnHeader(cell(layout.headerHeight(),
                place(caption, width, layout.headerHeight(), layout.headerStyle(), column, layout,
                        index, count)));

        JRDesignTextField value = new JRDesignTextField();
        JRDesignExpression expression = new JRDesignExpression("$F{" + column.property() + "}");
        expression.setValueClass(column.valueClass());
        value.setExpression(expression);
        // A null field prints as an empty cell rather than as the string "null" -- for a
        // caller-shaped table a missing value is normal, not an error.
        value.setBlankWhenNull(true);
        if (column.stretches()) {
            value.setTextAdjust(TextAdjustEnum.STRETCH_HEIGHT);
        }
        else {
            // Or this cell's rules stop at the designed height while the tallest cell in the row
            // grows past it, and the table looks torn across the row.
            value.setStretchType(StretchTypeEnum.CONTAINER_HEIGHT);
        }
        standard.setDetailCell(cell(layout.rowHeight(),
                place(value, width, layout.rowHeight(), layout.cellStyle(column), column, layout,
                        index, count)));
        return standard;
    }

    /**
     * A cell wrapping one element.
     *
     * <p>The cell carries only a height; the element inside carries the style, and with it the
     * box, the padding and the row's rule. Putting the style on both would draw the border twice,
     * once around the cell and once around the element filling it.
     */
    private static DesignCell cell(int height, JRDesignTextElement element) {
        DesignCell cell = new DesignCell();
        cell.setHeight(height);
        cell.addElement(element);
        return cell;
    }

    /**
     * Fills the cell, writes the style onto it, aligns, and draws the table's outer rule.
     *
     * <p><b>x=0 and the cell's full width: a column has no margin, and neither has this.</b> The
     * column widths already sum to the table's width, so a cell inset here would show as a gap the
     * table does not account for. Everything that looks like spacing between two columns is the
     * style's left/right <i>padding</i> (see {@link #apply}) or the outer rule below — there is no
     * third source, which is worth knowing before anyone goes looking for a gutter to adjust.
     */
    private static JRDesignTextElement place(JRDesignTextElement element, int width, int height,
                                             CellStyle style, TableColumn column, TableDesign layout,
                                             int index, int count) {
        element.setX(0);
        element.setY(0);
        element.setWidth(width);
        element.setHeight(height);
        apply(element, style);
        element.setHorizontalTextAlign(switch (column.align()) {
            case LEFT -> HorizontalTextAlignEnum.LEFT;
            case CENTER -> HorizontalTextAlignEnum.CENTER;
            case RIGHT -> HorizontalTextAlignEnum.RIGHT;
        });
        // Not part of the CellStyle: the same style lands in every column, and "am I the first or
        // the last one" is not something it can know.
        if (index == 0) {
            element.getLineBox().getLeftPen().setLineWidth(CellStyle.RULE_WIDTH);
            element.getLineBox().getLeftPen().setLineColor(layout.edgeColor());
        }
        if (index == count - 1) {
            element.getLineBox().getRightPen().setLineWidth(CellStyle.RULE_WIDTH);
            element.getLineBox().getRightPen().setLineColor(layout.edgeColor());
        }
        return element;
    }

    /**
     * Writes a {@link CellStyle} onto a generated element — the replacement for
     * {@code setStyleNameReference("TableHeader")} and the jrxml {@code <style>} behind it.
     *
     * <p><b>Identity-H, embedded, on every cell.</b> Not a style choice: without them the CJK and
     * Thai glyphs never reach the PDF, and the symptom is a table of blanks in three languages
     * while the English proof reads perfectly. The report's own default style declares the same
     * two for the chrome; a generated cell no longer inherits from it, so it says so itself.
     *
     * <p><b>A backcolor also turns the element opaque.</b> An element is transparent by default and
     * paints no background at all, so a backcolor on its own is silently nothing — the caption
     * row's light blue is exactly that case.
     *
     * <p>Only what the style actually carries is written. A {@code null} colour is left alone
     * rather than set to null, so the report's default style still answers for it.
     */
    private static void apply(JRDesignTextElement element, CellStyle style) {
        element.setFontName(style.fontName());
        element.setFontSize(style.fontSize());
        element.setBold(style.bold());
        element.setPdfEncoding(PDF_ENCODING);
        element.setPdfEmbedded(Boolean.TRUE);

        if (style.forecolor() != null) {
            element.setForecolor(style.forecolor());
        }
        if (style.backcolor() != null) {
            element.setBackcolor(style.backcolor());
            element.setMode(ModeEnum.OPAQUE);
        }
        element.setVerticalTextAlign(switch (style.vAlign()) {
            case TOP -> VerticalTextAlignEnum.TOP;
            case MIDDLE -> VerticalTextAlignEnum.MIDDLE;
        });

        JRLineBox box = element.getLineBox();
        CellStyle.Padding padding = style.padding();
        box.setLeftPadding(padding.left());
        box.setRightPadding(padding.right());
        box.setTopPadding(padding.top());
        box.setBottomPadding(padding.bottom());
        if (style.topRule() != null) {
            box.getTopPen().setLineWidth(CellStyle.RULE_WIDTH);
            box.getTopPen().setLineColor(style.topRule());
        }
        if (style.bottomRule() != null) {
            box.getBottomPen().setLineWidth(CellStyle.RULE_WIDTH);
            box.getBottomPen().setLineColor(style.bottomRule());
        }

        if (style.lineSpacing() != null) {
            element.getParagraph().setLineSpacing(LineSpacingEnum.PROPORTIONAL);
            element.getParagraph().setLineSpacingSize(style.lineSpacing());
        }
    }

    /**
     * Makes the table's subDataset declare <b>exactly</b> the column list.
     *
     * <p>Replacing rather than merging, because JasperReports asks the data source for every field
     * the dataset declares, not only the ones an expression references. A field left over from a
     * previous shape would be requested at fill time and answered by a data source built from the
     * <i>current</i> columns — a fill that fails on a column nobody can see.
     */
    private static void declareFields(JasperDesign design, List<TableColumn> columns) {
        JRDataset dataset = design.getDatasetMap().get(SUBDATASET);
        if (!(dataset instanceof JRDesignDataset listing)) {
            throw new IllegalStateException("The template declares no <subDataset name=\"" + SUBDATASET
                    + "\"> for its table to run over");
        }
        for (JRField existing : listing.getFields()) {
            listing.removeField(existing);
        }
        for (TableColumn column : columns) {
            JRDesignField field = new JRDesignField();
            field.setName(column.property());
            field.setValueClass(column.valueClass());
            try {
                listing.addField(field);
            }
            catch (JRException ex) {
                throw new IllegalStateException("Field '" + column.property() + "' could not be declared", ex);
            }
        }
    }

    /**
     * The weights, normalised over the table's width.
     *
     * <p>Relative weights rather than absolute widths because the content width is the template's
     * business, not the caller's: a column list built for one page box then lays out on any other,
     * and on the chrome-stripped design an extract widens by both margins.
     */
    private static int[] widths(int available, List<TableColumn> columns) {
        int total = columns.stream().mapToInt(TableColumn::weight).sum();
        int[] widths = new int[columns.size()];
        int used = 0;
        for (int i = 0; i < columns.size() - 1; i++) {
            widths[i] = (int) Math.round((double) available * columns.get(i).weight() / total);
            used += widths[i];
        }
        widths[widths.length - 1] = available - used;
        if (widths[widths.length - 1] <= 0) {
            throw new IllegalArgumentException("The columns do not fit in " + available
                    + "pt; the last one would be " + widths[widths.length - 1] + "pt wide");
        }
        return widths;
    }

    /**
     * The template's table, for anything that needs to look at the columns it was given — a test,
     * or a caller checking what a shape compiled to.
     */
    public static StandardTable tableIn(JasperDesign design) {
        return (StandardTable) tableElement(design).getComponent();
    }

    /** The {@code <componentElement key="listing">} holding the table, found in the detail band. */
    private static JRDesignComponentElement tableElement(JasperDesign design) {
        for (var band : design.getDetailSection().getBands()) {
            for (JRElement element : band.getElements()) {
                if (element instanceof JRDesignComponentElement component
                        && TABLE_KEY.equals(component.getKey())
                        && component.getComponent() instanceof StandardTable) {
                    return component;
                }
            }
        }
        throw new IllegalStateException("The template's detail band has no <componentElement key=\""
                + TABLE_KEY + "\"> holding a <jr:table> to write columns into");
    }

    /**
     * Strips the document down to its table: no title band, no page header, no page footer, no
     * background, and no margins.
     *
     * <p>This is what a <b>data extract</b> is. A CSV or a spreadsheet of this listing is the rows
     * and the captions; a logo, a filter-criteria block and a two-storey footnote with a seal are
     * a printed document's furniture and would land in the middle of the data as stray cells.
     *
     * <p>Starting from the template and removing the chrome, rather than building a second report
     * from nothing, keeps one file as the source of truth for both paths: the <b>styles survive</b>,
     * so a spreadsheet's caption row is still the document's caption row, and the default style
     * survives with them, which is what keeps the locale's embedded face on the cells.
     */
    public static void stripChrome(JasperDesign design) {
        design.setTitle(null);
        design.setPageHeader(null);
        design.setPageFooter(null);
        design.setLastPageFooter(null);
        design.setSummary(null);
        design.setBackground(null);
        design.setColumnHeader(null);
        design.setColumnFooter(null);
        design.setTopMargin(0);
        design.setBottomMargin(0);
        design.setLeftMargin(0);
        design.setRightMargin(0);
        // The content width was the page minus the margins that are now gone; without this the
        // table would keep its old width and leave the difference blank at the right.
        design.setColumnWidth(design.getPageWidth());
    }
}
