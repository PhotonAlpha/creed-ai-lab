package com.creed.jasper.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Column widths measured from the content — {@code table-layout: auto}, for a report engine that
 * has no such thing.
 *
 * <p><b>The problem this solves.</b> A {@link TableColumn} declares a {@code weight}: a share of
 * the page, hand-tuned against the longest string the column happens to carry. That works until the
 * shape changes. Three columns and the same weights leave two of them absurdly wide; eight and the
 * references break <i>inside</i> the token. Every column count needs its own set of magic numbers,
 * each of which is a measurement someone took once and nobody re-takes when the payload changes.
 * The HTML twin has never had this problem: a {@code <table>} without fixed widths measures its own
 * content, and its {@code <colgroup>} only nudges the result.
 *
 * <p><b>The algorithm is CSS's</b>, because it is the one whose output people already recognise:
 *
 * <ol>
 *   <li>Measure every column's <b>min</b> (its widest unbreakable token — see
 *       {@link TextMetrics#minTokenWidth}) and <b>max</b> (its widest full line), caption included
 *       and padding added, in the styles the cells will actually print in.</li>
 *   <li><b>Everything fits</b> (Σmax ≤ available): give each column its max and share the surplus
 *       out in proportion to {@link TableColumn#weight()} — which is what the weights are for now:
 *       not a width, a claim on the <i>slack</i>. A wide column stays wide, a date column does not
 *       stretch to twice the date.</li>
 *   <li><b>Some wrapping is needed</b> (Σmin ≤ available &lt; Σmax): every column gets its min, and
 *       what is left is shared in proportion to how much each still wants (max − min). Columns that
 *       cannot wrap ask for nothing extra and keep their content intact; the account block absorbs
 *       the squeeze, which is exactly where the wrapping belongs.</li>
 *   <li><b>Nothing fits</b> (available &lt; Σmin): scale the mins down proportionally. Something
 *       will break inside a token — the table is wider than the paper — and this at least spreads
 *       the damage instead of destroying the last column.</li>
 * </ol>
 *
 * <p><b>The result is a column list, not a layout.</b> {@link #columns()} hands back the same
 * columns with their weights <i>replaced by the fitted point widths</i>, so everything downstream
 * is untouched: {@link TableDesigner} normalises weights over the real content width exactly as
 * before, {@link ReportShape} still keys the compile cache on the columns, and a chrome-stripped
 * spreadsheet — whose content area is wider by both margins — gets the same proportions rather than
 * a second fit. Fitting is a <b>pre-pass</b>, and nothing below it knows it happened.
 *
 * <p><b>What it costs.</b> The fit measures every cell, so it wants the rows as text before the
 * report is compiled, and two payloads of different lengths are two different compiled reports
 * (different fitted widths → different columns → a different cache key). For this module's fixed
 * sample that is one entry; for a report served over changing data it is a cache that grows with
 * the data, which is the reason {@code fixed} stays available and is not deprecated.
 *
 * @param available    the content width the fit was made against, in points
 * @param measurements one entry per column, in order, carrying what was measured and what it got
 */
public record ColumnFit(int available, List<Measured> measurements) {

    /**
     * One column's measurements and its fitted width — the whole reason this type is public rather
     * than an {@code int[]} returned from a static method. A layout that cannot be inspected is a
     * layout nobody can argue with, and "why is that column 36pt?" is the first question anyone
     * asks of an automatic one.
     *
     * @param column the column as declared, weight and all
     * @param min    the width below which its content breaks inside a token
     * @param max    the width at which nothing in it wraps
     * @param width  what it was actually given
     */
    public record Measured(TableColumn column, int min, int max, int width) {

        /** Whether this column got everything it wanted. */
        public boolean fits() {
            return width >= max;
        }

        /** Whether this column is at the width below which its content starts to break. */
        public boolean squeezed() {
            return width <= min;
        }
    }

    public ColumnFit {
        measurements = List.copyOf(measurements);
    }

    /**
     * Fits {@code columns} to {@code available} points against the text they will carry.
     *
     * @param columns the declared columns; their weights become a claim on the surplus
     * @param rows    the cell text, row-major, in the same order as {@code columns}. A short row is
     *                padded with blanks rather than refused: measuring is a layout hint, and a
     *                report that renders a missing cell as empty must not fail to lay it out
     * @param layout  the styles the cells print in — the measurement is only as good as the face
     *                and size it is taken in
     * @param locale  the fill's locale, which is what selects the face inside the family
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
        int count = columns.size();
        int[] min = new int[count];
        int[] max = new int[count];

        for (int i = 0; i < count; i++) {
            TableColumn column = columns.get(i);
            CellStyle headerStyle = layout.headerStyle();
            CellStyle cellStyle = layout.cellStyle(column);

            // The caption is measured in the HEADER style and the cells in the cell style: they are
            // different sizes and weights, and a caption measured at the cell's weight is a column
            // that fits its data and clips its own heading.
            float columnMin = TextMetrics.minTokenWidth(column.header(), headerStyle, fillLocale)
                    + padding(headerStyle);
            float columnMax = TextMetrics.maxLineWidth(column.header(), headerStyle, fillLocale)
                    + padding(headerStyle);

            for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
                // A row shorter than the column list is a blank cell, not an error -- the same rule
                // the data source follows for a key a row does not carry.
                String text = row != null && i < row.size() ? row.get(i) : null;
                columnMin = Math.max(columnMin,
                        TextMetrics.minTokenWidth(text, cellStyle, fillLocale) + padding(cellStyle));
                columnMax = Math.max(columnMax,
                        TextMetrics.maxLineWidth(text, cellStyle, fillLocale) + padding(cellStyle));
            }
            // Ceil, not round: half a point short of the content is still short of it.
            min[i] = (int) Math.ceil(columnMin);
            max[i] = (int) Math.ceil(columnMax);
            if (max[i] < min[i]) {
                max[i] = min[i];
            }
        }

        return new ColumnFit(available, distribute(columns, min, max, available));
    }

    /**
     * The same measurements, but with the columns' <b>declared weights</b> normalised over
     * {@code available} instead of the fitted widths — what {@link ColumnWidths#FIXED} prints.
     *
     * <p>Built from a fit rather than instead of one, so the two modes can be compared on the same
     * numbers: the mins and maxes are the measurement, and the widths are what each mode does with
     * it. The normalisation is {@code TableDesigner}'s own, remainder to the last column included,
     * so what this reports is what will actually be drawn.
     */
    public static ColumnFit declared(ColumnFit measured, List<TableColumn> columns, int available) {
        int count = columns.size();
        int totalWeight = 0;
        for (TableColumn column : columns) {
            totalWeight += column.weight();
        }
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

    /** The fitted columns: the same list, with each weight replaced by its fitted width. */
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
        int totalMin = sum(min);
        int totalMax = sum(max);
        int[] width = new int[count];

        if (totalMax <= available) {
            // Room to spare: everyone gets their content, and the WEIGHTS decide the slack. This is
            // the step that keeps three columns from each being a third of the page.
            System.arraycopy(max, 0, width, 0, count);
            share(width, available - totalMax, weights(columns));
        }
        else if (totalMin <= available) {
            // The interesting case, and CSS's: mins first, then the shortfall shared in proportion
            // to what each column still wants -- WEIGHTED, which is the one place this departs from
            // the browser. CSS has no notion of a column mattering more than another, so it gives
            // the account block and the value date the same claim per point wanted. Multiplying by
            // the declared weight says which column should be the last to give way, and without it
            // this document's account block loses 11pt to the columns around it, wraps to a sixth
            // line and takes the whole listing onto another page. A caller who does not care leaves
            // the weights equal and gets the browser's answer exactly.
            System.arraycopy(min, 0, width, 0, count);
            int[] claims = new int[count];
            int[] weights = weights(columns);
            for (int i = 0; i < count; i++) {
                // A column already at its max wants nothing, whatever its weight.
                claims[i] = (max[i] - min[i]) * weights[i];
            }
            share(width, available - totalMin, claims);
        }
        else {
            // Wider than the paper. Scale the mins so the overflow is spread rather than dumped on
            // the last column, which is where the designer's rounding remainder would put it.
            for (int i = 0; i < count; i++) {
                width[i] = Math.max(1, (int) Math.floor((double) available * min[i] / totalMin));
            }
            settle(width, available);
        }

        List<Measured> measured = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            measured.add(new Measured(columns.get(i), min[i], max[i], width[i]));
        }
        return measured;
    }

    /**
     * Hands {@code surplus} out in proportion to {@code shares}, and puts the rounding remainder on
     * the column with the largest share rather than on the last one — the last column is wherever
     * the caller happened to put it, and a stray point there is the one place the eye lands.
     */
    private static void share(int[] width, int surplus, int[] shares) {
        if (surplus <= 0) {
            return;
        }
        int total = sum(shares);
        if (total <= 0) {
            // Nothing wants the space (every column already at its max and no weights to go by):
            // spread it evenly rather than leave the table short of the right margin.
            int each = surplus / width.length;
            for (int i = 0; i < width.length; i++) {
                width[i] += each;
            }
            width[widest(width)] += surplus - each * width.length;
            return;
        }
        int handed = 0;
        for (int i = 0; i < width.length; i++) {
            int give = (int) Math.floor((double) surplus * shares[i] / total);
            width[i] += give;
            handed += give;
        }
        width[widest(shares)] += surplus - handed;
    }

    /** Corrects a rounded distribution so the widths sum to exactly {@code available}. */
    private static void settle(int[] width, int available) {
        int difference = available - sum(width);
        if (difference != 0) {
            width[widest(width)] += difference;
        }
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

    private static float padding(CellStyle style) {
        return style.padding().left() + style.padding().right();
    }
}
