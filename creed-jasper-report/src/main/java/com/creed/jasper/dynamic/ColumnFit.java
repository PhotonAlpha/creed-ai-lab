package com.creed.jasper.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Column widths measured from the content — {@code table-layout: auto}, for an engine that has no
 * such thing.
 *
 * <p>A {@link TableColumn}'s {@code weight} is a share of the page, hand-tuned against the longest
 * string the column happened to carry when someone last looked. It does not survive a change of
 * shape: weights tuned for eight columns make three of them a third of the page each, and a payload
 * whose references grew two characters breaks <i>inside</i> a token with nobody the wiser.
 *
 * <p>Per column: <b>min</b> = its widest unbreakable token ({@link TextMetrics#minTokenWidth}),
 * <b>max</b> = its widest full line, caption measured in the header style and cells in theirs,
 * padding included. Then CSS's algorithm, with one departure:
 *
 * <ul>
 *   <li>Σmax ≤ available — everyone gets their content, the slack is shared <b>by weight</b>.</li>
 *   <li>Σmin ≤ available &lt; Σmax — everyone gets their min, the rest is shared by
 *       {@code (max − min) × weight}. <b>The × weight is the departure.</b> A browser has no notion
 *       of one column mattering more, so plain CSS gives this document's account block 11pt less, a
 *       sixth line and an extra page. Weights stopped being widths and became priorities; equal
 *       weights give the browser's answer exactly.</li>
 *   <li>available &lt; Σmin — mins scaled down. The table is wider than the paper and something
 *       will break; this spreads the damage instead of destroying the last column.</li>
 * </ul>
 *
 * <p><b>Fitting is a pre-pass.</b> {@link #columns()} returns the same columns with their weights
 * <i>replaced by the fitted widths</i>, so nothing below knows it happened: {@link TableDesigner}
 * normalises weights over the real content width as before, {@link ReportShape} still keys the
 * compile cache on the columns, and a chrome-stripped export gets the same proportions over its
 * wider content area rather than a second fit.
 *
 * <p><b>What it costs:</b> the rows must be measured before the report is compiled, and the fitted
 * widths are part of the cache key — a report over changing data compiles per distinct layout.
 * That is why {@code fixed} stays.
 *
 * @param available    the content width fitted against, in points
 * @param measurements one entry per column, in order
 */
public record ColumnFit(int available, List<Measured> measurements) {

    /**
     * One column's measurements and its fitted width. Public because "why is that column 36pt?" is
     * the first question anyone asks of an automatic layout, and {@code /approval-status/layout}
     * answers it out of this.
     *
     * @param min the width below which the content breaks inside a token
     * @param max the width at which nothing in it wraps
     */
    public record Measured(TableColumn column, int min, int max, int width) {

        /** Whether this column got everything it wanted. */
        public boolean fits() {
            return width >= max;
        }
    }

    public ColumnFit {
        measurements = List.copyOf(measurements);
    }

    /**
     * Fits {@code columns} to {@code available} points against the text they will carry.
     *
     * @param rows   the cell text, row-major, in the column list's order. A short row is a blank
     *               cell, not an error — the same rule the data source follows
     * @param layout the styles the cells print in; a measurement is only as good as its face
     * @param locale the fill's locale, which selects the face inside the family
     */
    public static ColumnFit measure(List<TableColumn> columns, List<List<String>> rows,
                                    TableDesign layout, int available, Locale locale) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("A fit needs at least one column");
        }
        if (available <= 0) {
            throw new IllegalArgumentException("A fit needs a positive width, not " + available);
        }
        Locale fillLocale = locale == null ? Locale.ENGLISH : locale;
        List<List<String>> content = rows == null ? List.of() : rows;

        int count = columns.size();
        int[] min = new int[count];
        int[] max = new int[count];

        for (int i = 0; i < count; i++) {
            TableColumn column = columns.get(i);
            // The caption in the HEADER style and the cells in the cell style: different sizes and
            // weights, and a caption measured at the cell's weight is a column that fits its data
            // and clips its own heading.
            CellStyle header = layout.headerStyle();
            CellStyle cell = layout.cellStyle(column);
            float columnMin = TextMetrics.minTokenWidth(column.header(), header, fillLocale) + pad(header);
            float columnMax = TextMetrics.maxLineWidth(column.header(), header, fillLocale) + pad(header);

            for (List<String> row : content) {
                String text = row != null && i < row.size() ? row.get(i) : null;
                columnMin = Math.max(columnMin, TextMetrics.minTokenWidth(text, cell, fillLocale) + pad(cell));
                columnMax = Math.max(columnMax, TextMetrics.maxLineWidth(text, cell, fillLocale) + pad(cell));
            }
            // Ceil, not round: half a point short of the content is still short of it.
            min[i] = (int) Math.ceil(columnMin);
            max[i] = Math.max(min[i], (int) Math.ceil(columnMax));
        }
        return new ColumnFit(available, distribute(columns, min, max, available));
    }

    /**
     * The same measurements with the columns' <b>declared weights</b> normalised over
     * {@code available} — what {@link com.creed.jasper.export.ColumnWidths#FIXED} prints.
     *
     * <p>Built from a fit rather than instead of one, so both modes can be compared on the same
     * mins and maxes. The normalisation is {@link TableDesigner}'s own, remainder to the last column
     * included, so this reports what will actually be drawn.
     */
    public static ColumnFit declared(ColumnFit measured, List<TableColumn> columns, int available) {
        int count = columns.size();
        int totalWeight = sum(weights(columns));
        List<Measured> fixed = new ArrayList<>(count);
        int used = 0;
        for (int i = 0; i < count; i++) {
            Measured from = measured.measurements().get(i);
            int width = i < count - 1
                    ? (int) Math.round((double) available * columns.get(i).weight() / totalWeight)
                    : available - used;
            used += width;
            fixed.add(new Measured(columns.get(i), from.min(), from.max(), width));
        }
        return new ColumnFit(available, fixed);
    }

    /** The fitted columns: the same list, each weight replaced by its fitted width. */
    public List<TableColumn> columns() {
        List<TableColumn> fitted = new ArrayList<>(measurements.size());
        for (Measured measured : measurements) {
            TableColumn column = measured.column();
            fitted.add(new TableColumn(column.property(), column.header(), measured.width(),
                    column.align(), column.stretches(), column.cellStyle(), column.valueClass()));
        }
        return List.copyOf(fitted);
    }

    /** Whether every column got its full content width — i.e. nothing in the table wraps. */
    public boolean everythingFits() {
        return measurements.stream().allMatch(Measured::fits);
    }

    /** The fitted widths alone, in column order. */
    public List<Integer> widths() {
        return measurements.stream().map(Measured::width).toList();
    }

    private static List<Measured> distribute(List<TableColumn> columns, int[] min, int[] max, int available) {
        int count = columns.size();
        int[] weights = weights(columns);
        int[] width = new int[count];
        int[] claims = new int[count];

        if (sum(max) <= available) {
            // Room to spare: everyone gets their content and the WEIGHTS decide the slack -- the
            // step that keeps three columns from being a third of the page each.
            System.arraycopy(max, 0, width, 0, count);
            claims = weights;
        }
        else if (sum(min) <= available) {
            System.arraycopy(min, 0, width, 0, count);
            for (int i = 0; i < count; i++) {
                // A column already at its max wants nothing, whatever its weight.
                claims[i] = (max[i] - min[i]) * weights[i];
            }
        }
        else {
            // Wider than the paper: scale the mins, and let the rounding fall to the widest column
            // rather than to the last one.
            for (int i = 0; i < count; i++) {
                width[i] = Math.max(1, (int) Math.floor((double) available * min[i] / sum(min)));
            }
        }
        share(width, available - sum(width), claims);

        List<Measured> measured = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            measured.add(new Measured(columns.get(i), min[i], max[i], width[i]));
        }
        return measured;
    }

    /**
     * Hands {@code surplus} out in proportion to {@code claims}, then puts the rounding remainder on
     * the largest claim — never on the last column, which is wherever the caller happened to put it
     * and the one place a stray point is noticed. With no claims (the overflow case) the whole
     * remainder goes to the widest column.
     */
    private static void share(int[] width, int surplus, int[] claims) {
        if (surplus <= 0) {
            return;
        }
        int total = sum(claims);
        int handed = 0;
        for (int i = 0; total > 0 && i < width.length; i++) {
            int give = (int) Math.floor((double) surplus * claims[i] / total);
            width[i] += give;
            handed += give;
        }
        width[widest(total > 0 ? claims : width)] += surplus - handed;
    }

    private static int[] weights(List<TableColumn> columns) {
        int[] weights = new int[columns.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = columns.get(i).weight();
        }
        return weights;
    }

    private static int widest(int[] values) {
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[index]) {
                index = i;
            }
        }
        return index;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    /** A cell style's horizontal padding — width the content cannot use. */
    private static float pad(CellStyle style) {
        return style.padding().left() + style.padding().right();
    }
}
