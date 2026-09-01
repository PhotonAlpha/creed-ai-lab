package com.creed.report.dynamic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard rails for the caller-supplied table. The payload is arbitrary, and both POI and
 * openpdf-html lay the whole thing out in memory, so a runaway {@code data} is refused as bad input
 * (400) rather than being allowed to exhaust the heap.
 */
@ConfigurationProperties(prefix = "creed.report.dynamic")
public class DynamicTableProperties {

    /** Most columns one report may declare. */
    private int maxColumns = 64;

    /** Most rows one report may render. */
    private int maxRows = 20_000;

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
