package com.creed.report.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Row-by-row writer for one sheet, so the {@link ExcelReportExporter} strategies describe their
 * report (title, header, rows) instead of juggling POI row/cell indexes.
 *
 * <p>Layout decisions that need the whole sheet — merging the title across the used columns,
 * freezing the header, the autofilter range and column widths — are deferred to {@link #finish()},
 * which every strategy must call once per sheet.
 */
final class ExcelSheetBuilder {

    /** Excel's hard limit on the character count of a single cell. */
    private static final int MAX_CELL_LENGTH = 32_767;

    /** Autosize cap, in characters, so one long value cannot produce an unusable column. */
    private static final int MAX_WIDTH_CHARS = 60;

    private final Sheet sheet;
    private final ExcelStyles styles;
    private final List<Integer> titleRows = new ArrayList<>();

    private int nextRow;
    private int columns;
    private int headerRow = -1;

    private ExcelSheetBuilder(Sheet sheet, ExcelStyles styles) {
        this.sheet = sheet;
        this.styles = styles;
    }

    /**
     * Creates a sheet named after {@code name}, sanitized through
     * {@link WorkbookUtil#createSafeSheetName(String)} — sheet names are capped at 31 characters
     * and reject {@code / \ * ? [ ]}, which localized titles do run into.
     */
    static ExcelSheetBuilder create(Workbook workbook, ExcelStyles styles, String name) {
        return new ExcelSheetBuilder(workbook.createSheet(WorkbookUtil.createSafeSheetName(name)), styles);
    }

    /** A bold heading line, merged across the sheet's columns by {@link #finish()}. */
    ExcelSheetBuilder title(String text) {
        titleRows.add(nextRow);
        write(sheet.createRow(nextRow++), 0, text, styles.title());
        return this;
    }

    /** A muted line under the title — generation timestamp, parameters, counts. */
    ExcelSheetBuilder caption(String text) {
        titleRows.add(nextRow);
        write(sheet.createRow(nextRow++), 0, text, styles.caption());
        return this;
    }

    /** An empty spacer row. */
    ExcelSheetBuilder blank() {
        nextRow++;
        return this;
    }

    /** The column header band. The last one written is the row frozen and filtered on. */
    ExcelSheetBuilder header(String... labels) {
        headerRow = nextRow;
        Row row = sheet.createRow(nextRow++);
        for (int i = 0; i < labels.length; i++) {
            write(row, i, labels[i], styles.header());
        }
        columns = Math.max(columns, labels.length);
        return this;
    }

    /**
     * A data row. {@link Number} values are written as numeric cells (so Excel can sum and sort
     * them); everything else is stringified, {@code null} as an empty cell.
     */
    ExcelSheetBuilder row(Object... values) {
        Row row = sheet.createRow(nextRow++);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Number number) {
                Cell cell = row.createCell(i);
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(styles.number());
            }
            else {
                write(row, i, value == null ? "" : String.valueOf(value), styles.text());
            }
        }
        columns = Math.max(columns, values.length);
        return this;
    }

    /** A bold key / plain value row, for the key-value summary blocks. */
    ExcelSheetBuilder labelled(String label, Object value) {
        Row row = sheet.createRow(nextRow++);
        write(row, 0, label, styles.label());
        write(row, 1, value == null ? "" : String.valueOf(value), styles.text());
        columns = Math.max(columns, 2);
        return this;
    }

    /** Applies the whole-sheet layout. Call once, after the last row. */
    void finish() {
        int lastColumn = Math.max(columns, 1) - 1;
        for (int titleRow : titleRows) {
            if (lastColumn > 0) {
                sheet.addMergedRegion(new CellRangeAddress(titleRow, titleRow, 0, lastColumn));
            }
        }
        if (headerRow >= 0) {
            sheet.createFreezePane(0, headerRow + 1);
            if (nextRow > headerRow + 1) {
                sheet.setAutoFilter(new CellRangeAddress(headerRow, nextRow - 1, 0, lastColumn));
            }
        }
        for (int column = 0; column <= lastColumn; column++) {
            sheet.autoSizeColumn(column);
            sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column), MAX_WIDTH_CHARS * 256));
        }
    }

    private void write(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH) : value);
        cell.setCellStyle(style);
    }
}
