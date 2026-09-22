package com.creed.jasper.dynamic;

import net.sf.jasperreports.engine.JRBand;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignField;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JRDesignStaticText;
import net.sf.jasperreports.engine.design.JRDesignTextElement;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.type.HorizontalTextAlignEnum;
import net.sf.jasperreports.engine.type.SplitTypeEnum;
import net.sf.jasperreports.engine.type.StretchTypeEnum;
import net.sf.jasperreports.engine.type.TextAdjustEnum;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a table's {@code columnHeader} and {@code detail} bands into a {@link JasperDesign} at
 * runtime, from a list of {@link TableColumn}s — <b>the columns are data, not markup</b>.
 *
 * <h2>Why this exists rather than DynamicJasper</h2>
 *
 * DynamicJasper is the library that normally does this, and it is the shape the reference photos
 * in {@code docs/} show: a jrxml supplies the page, the chrome and the styles, and the table is
 * built in Java and concatenated in. It has not had a release in years, it carries its own layout
 * managers and style model on top of JasperReports' own, and it pulls a second API surface into
 * the build for what is, underneath, a few dozen calls to JasperReports' <b>design API</b>
 * ({@code JRDesignBand}, {@code JRDesignTextField}, {@code JRDesignField}). This class is those
 * calls. It has no dependency of its own, it fails at compile time with JasperReports' own
 * messages, and there is nothing between the column model and the engine to go stale.
 *
 * <h2>The division of labour</h2>
 *
 * <pre>
 *   the jrxml   page box, bands, chrome, and every STYLE -- fonts, palette, padding, row rules,
 *               the locale-conditional weights. Read the look there, as before.
 *   the columns what fields exist, what they are called, how wide they are, which style a cell
 *               takes. Supplied by the caller, at runtime.
 * </pre>
 *
 * A generated element therefore carries no colour and no font of its own: it names a style
 * ({@link TableDesign#headerStyle()}, {@link TableDesign#cellStyle()} or
 * {@link TableColumn#cellStyle()}) and the template answers. The one exception is the table's
 * outer left/right rule, which is a property of being the first or last column and so cannot live
 * in a style at all; it is {@link TableDesign#edgeColor()}.
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li>declares one {@code JRDesignField} per column, replacing any field of the same name, so
 *       the design can be re-tabled and a template that declares its own fields is not fought
 *       over;</li>
 *   <li>normalises the weights over the design's {@code columnWidth}, giving the rounding
 *       remainder to the last column — the columns fill the page exactly rather than leaving a
 *       one-point gap the eye finds immediately;</li>
 *   <li>builds the caption row as {@code JRDesignStaticText} and the detail row as
 *       {@code JRDesignTextField}, with the first/last outer rules and the stretch rules below;</li>
 *   <li>replaces the design's {@code columnHeader} band and its whole detail section.</li>
 * </ol>
 *
 * <h2>Stretch, and why every cell needs it</h2>
 *
 * A multiline column's cell gets {@code textAdjust="StretchHeight"} so the row grows to its
 * content. Every <b>other</b> cell then gets {@code stretchType="RelativeToTallestObject"}, or its
 * bottom rule stops at the designed height and the table looks torn across the row. That is the
 * design-API spelling of what a CSS table cell does for free.
 *
 * <p>Stateless; the design it is handed is mutated in place and then compiled by the caller.
 */
public final class TableDesigner {

    private TableDesigner() {
    }

    /**
     * Writes {@code columns} into {@code design}, replacing whatever table was there.
     *
     * @throws IllegalArgumentException if the columns are empty or two share a field name — a
     *                                  duplicate would silently make one column shadow the other,
     *                                  which is the kind of thing a generated table has to refuse
     *                                  rather than render
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

        declareFields(design, columns);
        int[] widths = widths(design.getColumnWidth(), columns);
        boolean stretching = columns.stream().anyMatch(TableColumn::stretches);

        design.setColumnHeader(headerBand(layout, columns, widths));
        setDetail(design, detailBand(layout, columns, widths, stretching));
    }

    /**
     * Makes the dataset's fields <b>exactly</b> the column list: every existing field is dropped
     * first, then one is declared per column.
     *
     * <p>Replacing rather than merging, because JasperReports asks the data source for every field
     * the dataset declares, not only the ones an expression references. A field left over from a
     * previous shape would therefore be requested at fill time and answered by a data source built
     * from the <i>current</i> columns — which is a fill that fails on a column nobody can see, or
     * worse, a silent null.
     *
     * <p>The contract that follows: <b>a template handed to this designer declares no fields of
     * its own.</b> The columns are the fields. {@code approval-status.jrxml} honours it and
     * {@code TableDesignerTest} asserts that it does.
     */
    private static void declareFields(JasperDesign design, List<TableColumn> columns) {
        for (JRField existing : design.getFields()) {
            design.removeField(existing);
        }
        for (TableColumn column : columns) {
            JRDesignField field = new JRDesignField();
            field.setName(column.property());
            field.setValueClass(column.valueClass());
            try {
                design.addField(field);
            }
            catch (JRException ex) {
                throw new IllegalStateException("Field '" + column.property() + "' could not be declared", ex);
            }
        }
    }

    /**
     * The weights, normalised over the available width.
     *
     * <p>Relative weights rather than absolute widths because the page's content width is the
     * template's business, not the caller's: a column list built for one page box then lays out on
     * any other. The remainder goes to the last column so the row always ends exactly on the right
     * margin.
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

    private static JRDesignBand headerBand(TableDesign layout, List<TableColumn> columns, int[] widths) {
        JRDesignBand band = new JRDesignBand();
        band.setHeight(layout.headerHeight());
        int x = 0;
        for (int i = 0; i < columns.size(); i++) {
            JRDesignStaticText caption = new JRDesignStaticText();
            caption.setText(columns.get(i).header());
            place(caption, x, widths[i], layout.headerHeight(), layout.headerStyle(), columns.get(i));
            edges(caption, layout, i, columns.size());
            band.addElement(caption);
            x += widths[i];
        }
        return band;
    }

    private static JRDesignBand detailBand(TableDesign layout, List<TableColumn> columns,
                                           int[] widths, boolean stretching) {
        JRDesignBand band = new JRDesignBand();
        band.setHeight(layout.rowHeight());
        // A row's lines belong together: it moves to the next page whole or not at all. The design
        // API's spelling of `page-break-inside: avoid`.
        band.setSplitType(SplitTypeEnum.PREVENT);
        int x = 0;
        for (int i = 0; i < columns.size(); i++) {
            TableColumn column = columns.get(i);
            JRDesignTextField cell = new JRDesignTextField();
            JRDesignExpression expression = new JRDesignExpression("$F{" + column.property() + "}");
            expression.setValueClass(column.valueClass());
            cell.setExpression(expression);
            // A null field prints as an empty cell rather than as the string "null" -- for a
            // caller-supplied table a missing value is normal, not an error.
            cell.setBlankWhenNull(true);
            if (column.stretches()) {
                cell.setTextAdjust(TextAdjustEnum.STRETCH_HEIGHT);
            }
            else if (stretching) {
                // Only needed when something else in the row can grow; harmless otherwise, but
                // left off so a plain table's XML says what it means.
                cell.setStretchType(StretchTypeEnum.RELATIVE_TO_TALLEST_OBJECT);
            }
            place(cell, x, widths[i], layout.rowHeight(),
                    column.cellStyle() == null ? layout.cellStyle() : column.cellStyle(), column);
            edges(cell, layout, i, columns.size());
            band.addElement(cell);
            x += widths[i];
        }
        return band;
    }

    /** Position, size, style reference and alignment — everything both bands' cells share. */
    private static void place(JRDesignTextElement element, int x, int width, int height,
                              String style, TableColumn column) {
        element.setX(x);
        element.setY(0);
        element.setWidth(width);
        element.setHeight(height);
        // By NAME, not by JRStyle: the style is looked up when the report is filled, so this works
        // whether the template declares it inline or pulls it from a shared style template.
        element.setStyleNameReference(style);
        element.setHorizontalTextAlign(switch (column.align()) {
            case LEFT -> HorizontalTextAlignEnum.LEFT;
            case CENTER -> HorizontalTextAlignEnum.CENTER;
            case RIGHT -> HorizontalTextAlignEnum.RIGHT;
        });
    }

    /**
     * The table's outer rule, on the first and last column only.
     *
     * <p>The one thing that cannot be delegated to a style: a style is resolved per element and has
     * no way to know which column it landed in. Everything else about the box — padding, the rule
     * under each row, the header band's own rules — is in the jrxml styles.
     */
    private static void edges(JRDesignTextElement element, TableDesign layout, int index, int count) {
        if (index == 0) {
            element.getLineBox().getLeftPen().setLineWidth(0.5f);
            element.getLineBox().getLeftPen().setLineColor(layout.edgeColor());
        }
        if (index == count - 1) {
            element.getLineBox().getRightPen().setLineWidth(0.5f);
            element.getLineBox().getRightPen().setLineColor(layout.edgeColor());
        }
    }

    /**
     * Strips the document down to its table: no title band, no page header, no page footer, no
     * background, and no margins.
     *
     * <p>This is what a <b>data extract</b> is. A CSV or a spreadsheet of this listing is the rows
     * and the captions; a logo, a filter-criteria block and a two-storey footnote with a seal are
     * a printed document's furniture and would land in the middle of the data as stray cells.
     *
     * <p>The reference implementation reaches the same place from the other end — it builds the
     * table report from nothing and only attaches the jrxml <i>for PDF</i>. Starting from the
     * template and removing the chrome keeps one file as the source of truth for both paths: the
     * <b>styles survive</b>, so a spreadsheet's caption row is still the document's caption row,
     * and there is no second design to keep in step.
     *
     * <p>The default style survives too, which is what keeps the locale's embedded face on the
     * cells — a stripped design is still this document, just without its covers.
     */
    public static void stripChrome(JasperDesign design) {
        design.setTitle(null);
        design.setPageHeader(null);
        design.setPageFooter(null);
        design.setLastPageFooter(null);
        design.setSummary(null);
        design.setBackground(null);
        design.setColumnFooter(null);
        design.setTopMargin(0);
        design.setBottomMargin(0);
        design.setLeftMargin(0);
        design.setRightMargin(0);
        // The content width was the page minus the margins that are now gone; without this the
        // table would keep its old width and leave the difference blank at the right.
        design.setColumnWidth(design.getPageWidth());
    }

    /** Replaces the whole detail section with one band. */
    private static void setDetail(JasperDesign design, JRDesignBand band) {
        JRDesignSection detail = (JRDesignSection) design.getDetailSection();
        List<JRBand> bands = new ArrayList<>(detail.getBandsList());
        bands.forEach(detail::removeBand);
        detail.addBand(band);
    }
}
