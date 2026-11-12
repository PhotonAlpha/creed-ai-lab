package com.creed.resource.veracode.template;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The uploaded workbook, both ways: read into what the page's grid draws, and written back with JXLS markup from a
 * {@link TemplateMapping}. {@code .xlsx} and {@code .xlsm} (asked for, 2026-11-13: POI keeps the macros through the
 * markup and the fill) - JXLS's POI transformer and the comments it reads are OOXML, so no {@code .xls}.
 *
 * <p>The markup, per sheet with bindings:
 * <ul>
 *   <li>{@code jx:area(lastCell="..")} in a comment on {@code A1}, covering the sheet's content ({@link #extent}) and
 *       the bound cells - cells outside an area would not be filled, and the whole sheet keeps its layout;</li>
 *   <li>per data row, {@code jx:each(items="sca" var="r" lastCell="..")} on its column {@code A}, spanning the row:
 *       the whole row repeats, unbound cells included, and what is below it moves down;</li>
 *   <li>bound cells hold {@code ${r.<field>}} (data rows) or {@code ${summary.<field>}}.</li>
 * </ul>
 * A comment the cell already had is kept, the commands appended (JXLS reads only the {@code jx:} lines).
 */
public final class TemplateWorkbook {

    /** What the page is sent of a sheet; a bigger one is cut, and says so. */
    static final int MAX_ROWS = 500;
    static final int MAX_COLS = 60;

    /** @param fill {@code #RRGGBB} of a solid fill, {@code null} without one */
    public record CellView(int col, String text, boolean bold, String fill) {
    }

    public record RowView(int row, List<CellView> cells) {
    }

    public record Merge(int firstRow, int lastRow, int firstCol, int lastCol) {
    }

    /**
     * @param rows      rows sent (the used range, at most {@link #MAX_ROWS})
     * @param cols      columns sent (at most {@link #MAX_COLS})
     * @param truncated the sheet is bigger than what is sent
     * @param widths    column widths in pixels, {@code cols} of them
     */
    public record SheetView(String name, int rows, int cols, boolean truncated, List<RowView> data, List<Merge> merges,
                            List<Integer> widths) {
    }

    private TemplateWorkbook() {
    }

    /** Refuses anything POI cannot open as {@code .xlsx}: an {@link IllegalArgumentException}, so a 400. */
    static XSSFWorkbook open(byte[] xlsx) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("not an Excel workbook (.xlsx / .xlsm): " + e.getMessage(), e);
        }
    }

    public static List<SheetView> read(byte[] xlsx) {
        try (XSSFWorkbook wb = open(xlsx)) {
            DataFormatter formatter = new DataFormatter();
            // what Excel last showed, not a re-evaluation (external links, functions POI lacks)
            formatter.setUseCachedValuesForFormulaCells(true);
            List<SheetView> sheets = new ArrayList<>();
            for (Sheet sheet : wb) {
                sheets.add(read(wb, sheet, formatter));
            }
            return sheets;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SheetView read(XSSFWorkbook wb, Sheet sheet, DataFormatter formatter) {
        CellAddress extent = extent(sheet, true);
        int usedRows = extent.getRow() + 1;
        int usedCols = extent.getColumn() + 1;
        int rows = Math.min(usedRows, MAX_ROWS);
        int cols = Math.min(Math.max(usedCols, 1), MAX_COLS);
        List<RowView> data = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<CellView> cells = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                String text = formatter.formatCellValue(cell);
                XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
                boolean bold = wb.getFontAt(style.getFontIndex()).getBold();
                String fill = fill(style);
                if (!text.isEmpty() || bold || fill != null) {
                    cells.add(new CellView(c, text, bold, fill));
                }
            }
            if (!cells.isEmpty()) {
                data.add(new RowView(r, cells));
            }
        }
        List<Merge> merges = sheet.getMergedRegions().stream()
                .filter(m -> m.getFirstRow() < rows && m.getFirstColumn() < cols)
                .map(m -> new Merge(m.getFirstRow(), Math.min(m.getLastRow(), rows - 1), m.getFirstColumn(),
                        Math.min(m.getLastColumn(), cols - 1)))
                .toList();
        List<Integer> widths = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            widths.add(Math.round(sheet.getColumnWidthInPixels(c)));
        }
        return new SheetView(sheet.getSheetName(), rows, cols, usedRows > rows || usedCols > cols, data, merges,
                widths);
    }

    /**
     * The last row and column that show something: a value or formula, a merge, with {@code fills} a solid fill too
     * ({@code -1} each on an empty sheet). Not POI's used range: that counts every cell the file has, and a company
     * template formatted by whole rows has cells out to column XFD - a JXLS area that wide copies 16384 cells per data
     * row, which is how an export once took longer than any request timeout (2026-11-13).
     */
    static CellAddress extent(Sheet sheet, boolean fills) {
        int lastRow = -1;
        int lastCol = -1;
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (shows(cell, fills)) {
                    lastRow = Math.max(lastRow, cell.getRowIndex());
                    lastCol = Math.max(lastCol, cell.getColumnIndex());
                }
            }
        }
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            lastRow = Math.max(lastRow, m.getLastRow());
            lastCol = Math.max(lastCol, m.getLastColumn());
        }
        return new CellAddress(lastRow, lastCol);
    }

    private static boolean shows(Cell cell, boolean fills) {
        return switch (cell.getCellType()) {
            case BLANK -> fills && fill((XSSFCellStyle) cell.getCellStyle()) != null;
            case STRING -> !cell.getStringCellValue().isEmpty() || fills && fill((XSSFCellStyle) cell.getCellStyle()) != null;
            default -> true;
        };
    }

    private static String fill(XSSFCellStyle style) {
        if (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) {
            return null;
        }
        XSSFColor color = style.getFillForegroundXSSFColor();
        String argb = color == null ? null : color.getARGBHex();
        return argb == null || argb.length() != 8 ? null : "#" + argb.substring(2);
    }

    /** The workbook with the mapping's JXLS markup; the uploaded bytes are left as they were. */
    public static byte[] withJxlsMarkup(byte[] xlsx, TemplateMapping mapping) {
        try (XSSFWorkbook wb = open(xlsx); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (TemplateMapping.SheetMapping sm : mapping.sheets()) {
                if (!sm.isEmpty()) {
                    mark(wb, wb.getSheet(sm.sheet()), sm);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void mark(XSSFWorkbook wb, Sheet sheet, TemplateMapping.SheetMapping sm) {
        // what shows, not the used range: an area out to every formatted blank cell is what made exports hang
        CellAddress extent = extent(sheet, false);
        int lastRow = Math.max(extent.getRow(), 0);
        int lastCol = Math.max(extent.getColumn(), 0);
        for (TemplateMapping.ListBinding list : sm.lists()) {
            lastRow = Math.max(lastRow, list.row());
            for (TemplateMapping.FieldCell cell : list.cells()) {
                lastCol = Math.max(lastCol, cell.col());
            }
        }
        for (TemplateMapping.CellBinding value : sm.values()) {
            lastRow = Math.max(lastRow, value.row());
        }
        for (TemplateMapping.CellBinding value : sm.values()) {
            lastCol = Math.max(lastCol, value.col());
        }

        // commands by cell, in the order JXLS needs them: the area first
        Map<CellAddress, List<String>> commands = new LinkedHashMap<>();
        commands.computeIfAbsent(new CellAddress(0, 0), a -> new ArrayList<>())
                .add("jx:area(lastCell=\"" + ref(lastRow, lastCol) + "\")");
        for (TemplateMapping.ListBinding list : sm.lists()) {
            commands.computeIfAbsent(new CellAddress(list.row(), 0), a -> new ArrayList<>())
                    .add("jx:each(items=\"" + list.dataset() + "\" var=\"r\" lastCell=\"" + ref(list.row(), lastCol)
                            + "\")");
            for (TemplateMapping.FieldCell cell : list.cells()) {
                cell(sheet, list.row(), cell.col()).setCellValue("${r." + cell.field() + "}");
            }
        }
        for (TemplateMapping.CellBinding value : sm.values()) {
            cell(sheet, value.row(), value.col()).setCellValue("${" + TemplateFields.SUMMARY + "." + value.field() + "}");
        }

        CreationHelper factory = wb.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        commands.forEach((address, lines) -> {
            Cell cell = cell(sheet, address.getRow(), address.getColumn());
            Comment existing = cell.getCellComment();
            String text = (existing == null ? "" : existing.getString().getString() + "\n") + String.join("\n", lines);
            if (existing != null) {
                cell.removeCellComment();
            }
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(address.getColumn());
            anchor.setCol2(address.getColumn() + 3);
            anchor.setRow1(address.getRow());
            anchor.setRow2(address.getRow() + 3);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString(text));
            cell.setCellComment(comment);
        });
    }

    private static Cell cell(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row) != null ? sheet.getRow(row) : sheet.createRow(row);
        return r.getCell(col) != null ? r.getCell(col) : r.createCell(col);
    }

    /** {@code B6} for row 5, column 1. */
    static String ref(int row, int col) {
        return new CellReference(row, col).formatAsString(false);
    }

    /** Whether a merged region covers more than one row there: a data row inside it would be torn. */
    static boolean mergedAcrossRows(Sheet sheet, int row) {
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.getFirstRow() <= row && row <= m.getLastRow() && m.getFirstRow() != m.getLastRow()) {
                return true;
            }
        }
        return false;
    }
}
