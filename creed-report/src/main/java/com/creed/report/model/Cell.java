package com.creed.report.model;

/**
 * One side (left = old, right = new) of a split-diff row.
 *
 * @param type    one of: context, add, del, empty
 * @param num     the line number to show in the gutter ("" when empty)
 * @param content the source line content ("" when empty)
 */
public record Cell(String type, String num, String content) {

    public static Cell empty() {
        return new Cell("empty", "", "");
    }

    public boolean isAdd() {
        return "add".equals(type);
    }

    public boolean isDel() {
        return "del".equals(type);
    }

    public boolean isEmpty() {
        return "empty".equals(type);
    }
}
