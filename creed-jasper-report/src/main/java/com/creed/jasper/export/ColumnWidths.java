package com.creed.jasper.export;

import java.util.Locale;
import java.util.Optional;

/**
 * How a table's column widths are decided — the one rendering choice that changes the table's
 * shape without changing a single string in it.
 *
 * <p>A query-string value like {@code format}, and for the same reason: the endpoint pins a
 * document down and what a caller picks is how it is <b>laid out</b>, never what it says.
 */
public enum ColumnWidths {

    /**
     * Measured from the content, like {@code table-layout: auto} — every column gets at least its
     * longest unbreakable token and the slack is shared out. Works at three columns and at eight
     * without anyone re-tuning a number. The default.
     */
    AUTO("auto"),

    /**
     * The weights the report declares, normalised over the page. Hand-tuned, reproducible to the
     * point, and the only mode that does not need the rows before the report is compiled — which
     * is what makes it the right answer for a report whose data changes on every call.
     */
    FIXED("fixed");

    private final String code;

    ColumnWidths(String code) {
        this.code = code;
    }

    /** The query-string spelling. */
    public String code() {
        return code;
    }

    /** The mode a caller named, or empty — the caller turns that into a 400, never a 500. */
    public static Optional<ColumnWidths> of(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(AUTO);
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (ColumnWidths mode : values()) {
            if (mode.code.equals(wanted)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
