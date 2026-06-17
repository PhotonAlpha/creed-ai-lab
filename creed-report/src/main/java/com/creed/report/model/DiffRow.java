package com.creed.report.model;

/**
 * A single visual row in the split (side-by-side) diff view.
 *
 * <p>{@code hunkHeader} rows span the full width and render the {@code @@ ... @@}
 * header. {@code expandDown} rows render the "expand more lines" stub at the
 * bottom of a hunk. Otherwise the row carries a {@link Cell} for each side.
 */
public class DiffRow {

    private final Cell left;
    private final Cell right;
    private final boolean hunkHeader;
    private final String hunkText;
    private final boolean expandDown;

    private DiffRow(Cell left, Cell right, boolean hunkHeader, String hunkText, boolean expandDown) {
        this.left = left;
        this.right = right;
        this.hunkHeader = hunkHeader;
        this.hunkText = hunkText;
        this.expandDown = expandDown;
    }

    /** Unchanged line present on both sides. */
    public static DiffRow context(String leftNum, String rightNum, String content) {
        return new DiffRow(
                new Cell("context", leftNum, content),
                new Cell("context", rightNum, content),
                false, null, false);
    }

    /** Added line: empty on the left, green on the right. */
    public static DiffRow add(String rightNum, String content) {
        return new DiffRow(Cell.empty(), new Cell("add", rightNum, content), false, null, false);
    }

    /** Removed line: red on the left, empty on the right. */
    public static DiffRow del(String leftNum, String content) {
        return new DiffRow(new Cell("del", leftNum, content), Cell.empty(), false, null, false);
    }

    /** A replacement: removed line on the left aligned with an added line on the right. */
    public static DiffRow replace(String leftNum, String leftContent, String rightNum, String rightContent) {
        return new DiffRow(
                new Cell("del", leftNum, leftContent),
                new Cell("add", rightNum, rightContent),
                false, null, false);
    }

    /** A {@code @@ -a,b +c,d @@ context} hunk header row. */
    public static DiffRow hunk(String text) {
        return new DiffRow(null, null, true, text, false);
    }

    /** The "expand more lines" stub shown at the bottom of a file's last hunk. */
    public static DiffRow expandDown() {
        return new DiffRow(null, null, false, null, true);
    }

    public Cell getLeft() {
        return left;
    }

    public Cell getRight() {
        return right;
    }

    public boolean isHunkHeader() {
        return hunkHeader;
    }

    public String getHunkText() {
        return hunkText;
    }

    public boolean isExpandDown() {
        return expandDown;
    }
}
