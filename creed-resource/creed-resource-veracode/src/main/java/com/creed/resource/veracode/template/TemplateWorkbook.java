package com.creed.resource.veracode.template;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.FormulaShifter;
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
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.helpers.XSSFRowShifter;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTConditionalFormatting;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
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
@lombok.extern.slf4j.Slf4j
public final class TemplateWorkbook {

    /** An area reaching this far below the last bound row is logged with the cell that makes it so. */
    private static final int FAR_BELOW = 10_000;

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
     * @param hidden    cells below what shows that show nothing themselves; {@code null} when there are none
     */
    public record SheetView(String name, int rows, int cols, boolean truncated, List<RowView> data, List<Merge> merges,
                            List<Integer> widths, HiddenCells hidden) {
    }

    /**
     * Formulas with an empty result, whitespace: invisible, but each one read on every open and every export - a
     * helper column filled down the sheet is a million of them. The export leaves them out; the page says they are
     * there, so the template can be cleaned in Excel (2026-11-13).
     *
     * @param lastRow 1-based, the lowest of them
     * @param example the first, as {@code L5: =IF(E5="","",E5*2)}
     */
    public record HiddenCells(int cells, int lastRow, String example) {
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
                widths, hidden(sheet, extent.getRow()));
    }

    private static HiddenCells hidden(Sheet sheet, int lastShown) {
        int cells = 0;
        int lastRow = -1;
        String example = null;
        for (Row row : sheet) {
            if (row.getRowNum() <= lastShown) {
                continue;
            }
            for (Cell cell : row) {
                if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK) {
                    cells++;
                    lastRow = row.getRowNum();
                    if (example == null) {
                        example = cell.getAddress() + ": " + (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                                ? "=" + cell.getCellFormula() : "'" + cell.getStringCellValue() + "'");
                    }
                }
            }
        }
        return cells == 0 ? null : new HiddenCells(cells, lastRow + 1, example);
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

    /**
     * Whether a cell shows anything. Whitespace-only text does not, nor does a formula whose cached result is empty
     * text - a helper column like {@code =IF(E5="","",...)} filled down the whole sheet made the JXLS area a million rows
     * tall (lastCell L1047958; 2026-11-13, the export never came back: JXLS walks every row of its area). Such cells
     * below the area are left where they are by JXLS; the ones inside it are copied like any other.
     */
    private static boolean shows(Cell cell, boolean fills) {
        boolean filled = fills && fill((XSSFCellStyle) cell.getCellStyle()) != null;
        return switch (cell.getCellType()) {
            case BLANK -> filled;
            case STRING -> !cell.getStringCellValue().isBlank() || filled;
            case FORMULA -> cell.getCachedFormulaResultType() != org.apache.poi.ss.usermodel.CellType.STRING
                    || !cell.getStringCellValue().isBlank() || filled;
            default -> true;
        };
    }

    /** The lowest cell that shows something - what an area that tall reaches for. */
    private static Cell lowest(Sheet sheet) {
        for (int r = sheet.getLastRowNum(); r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row != null) {
                for (Cell cell : row) {
                    if (shows(cell, false)) {
                        return cell;
                    }
                }
            }
        }
        return null;
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
        return markup(xlsx, mapping, null);
    }

    /**
     * What JXLS fills: the markup, and the conditional formatting of every sheet with a data row taken off, to be put
     * back by {@link #withConditionalFormatting} once filled.
     *
     * <p>JXLS copies conditional formatting cell by cell - each copied cell adds a rule entry, checked against all the
     * entries before it - so an export's time grew with the square of rows x rules: 40 rules took 15 s for 400 rows, and
     * minutes for a real report (2026-11-13, an export that never came back). Put back afterwards, each rule stays one
     * entry whose ranges follow the rows.
     *
     * @param formatting sheet name -> its {@code <conditionalFormatting>} elements, as XML, in the sheet's order
     */
    public record ForFill(byte[] workbook, Map<String, List<String>> formatting) {
    }

    public static ForFill forFill(byte[] xlsx, TemplateMapping mapping) {
        Map<String, List<String>> held = new LinkedHashMap<>();
        return new ForFill(markup(xlsx, mapping, held), held);
    }

    private static byte[] markup(byte[] xlsx, TemplateMapping mapping, Map<String, List<String>> detach) {
        try (XSSFWorkbook wb = open(xlsx); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (TemplateMapping.SheetMapping sm : mapping.sheets()) {
                int areaLastRow = sm.isEmpty() ? -1 : mark(wb, wb.getSheet(sm.sheet()), sm);
                if (detach != null && areaLastRow >= 0) {
                    leaveOutInvisibleBelow(wb.getSheet(sm.sheet()), areaLastRow);
                }
                if (detach != null && !sm.lists().isEmpty()) {
                    CTWorksheet ws = wb.getSheet(sm.sheet()).getCTWorksheet();
                    List<String> xml = new ArrayList<>();
                    for (int i = ws.sizeOfConditionalFormattingArray() - 1; i >= 0; i--) {
                        xml.addFirst(ws.getConditionalFormattingArray(i).xmlText());
                        ws.removeConditionalFormatting(i);
                    }
                    if (!xml.isEmpty()) {
                        detach.put(sm.sheet(), xml);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The conditional formatting {@link #forFill} took off, back on the filled workbook, its ranges and formulas moved as
     * JXLS moved the rows. A data row filled with {@code n} items is {@code n} rows: everything below moves {@code n - 1}
     * (up one when there is none, the row removed), a range that ends on the data row ends on its last copy, one that
     * spans it grows - POI's own row shifter ({@link XSSFRowShifter#updateConditionalFormatting}), which also shifts the
     * rules' relative references. Lists are applied bottom-up, so each shift sees the rows as the template had them.
     *
     * @param items dataset -> how many items it was filled with
     */
    public static byte[] withConditionalFormatting(byte[] filled, TemplateMapping mapping, ForFill held,
                                                   Map<String, Integer> items) {
        if (held.formatting().isEmpty()) {
            return filled;
        }
        try (XSSFWorkbook wb = open(filled); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (TemplateMapping.SheetMapping sm : mapping.sheets()) {
                List<String> xml = held.formatting().get(sm.sheet());
                XSSFSheet sheet = wb.getSheet(sm.sheet());
                if (xml == null || sheet == null) {
                    continue;
                }
                CTWorksheet ws = sheet.getCTWorksheet();
                for (String one : xml) {
                    ws.addNewConditionalFormatting().set(CTConditionalFormatting.Factory.parse(one));
                }
                XSSFRowShifter shifter = new XSSFRowShifter(sheet);
                int lastRow = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
                List<TemplateMapping.ListBinding> bottomUp = sm.lists().stream()
                        .sorted(Comparator.comparingInt(TemplateMapping.ListBinding::row).reversed()).toList();
                for (TemplateMapping.ListBinding list : bottomUp) {
                    int moved = items.getOrDefault(list.dataset(), 0) - 1;
                    if (moved == 0) {
                        continue;
                    }
                    // rows that would leave the sheet do not move: a whole-column range keeps ending on the last row
                    shifter.updateConditionalFormatting(FormulaShifter.createForRowShift(wb.getSheetIndex(sheet),
                            sheet.getSheetName(), list.row() + 1, lastRow - Math.max(moved, 0), moved,
                            SpreadsheetVersion.EXCEL2007));
                    if (moved > 0) {
                        endOnLastCopy(ws, list.row(), moved);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (XmlException e) {
            throw new IllegalStateException("conditional formatting taken off the template no longer parses", e);
        }
    }

    /** A range ending on the data row covers its copies too; the shifter moved only what is below the row. */
    private static void endOnLastCopy(CTWorksheet ws, int row, int moved) {
        for (CTConditionalFormatting cf : ws.getConditionalFormattingArray()) {
            List<String> ranges = new ArrayList<>();
            boolean changed = false;
            for (Object ref : cf.getSqref()) {
                CellRangeAddress range = CellRangeAddress.valueOf(ref.toString());
                if (range.getLastRow() == row) {
                    range.setLastRow(row + moved);
                    changed = true;
                }
                ranges.add(range.formatAsString());
            }
            if (changed) {
                cf.setSqref(ranges);
            }
        }
    }

    /**
     * For the fill only: cells below the area that show nothing - a formula with an empty result, whitespace - are left
     * out, and rows left with no cell. A helper column like {@code =IF(E5="","",...)} filled down the whole sheet is a
     * million formula cells JXLS would read one by one, outside its area or not (one such sheet: 64 s of JXLS, plus
     * POI reading it each time the workbook is opened - 2026-11-13). Nothing visible goes: formatted cells stay, and
     * JXLS would have left these where they are. Removed top-down: POI finds a row's position by the rows before it.
     */
    private static void leaveOutInvisibleBelow(Sheet sheet, int areaLastRow) {
        List<Integer> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() > areaLastRow) {
                rows.add(row.getRowNum());
            }
        }
        int cells = 0;
        int lastRow = -1;
        for (int r : rows) {
            Row row = sheet.getRow(r);
            List<Cell> invisible = new ArrayList<>();
            for (Cell cell : row) {
                if (!shows(cell, false) && cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK) {
                    invisible.add(cell);
                }
            }
            invisible.forEach(row::removeCell);
            cells += invisible.size();
            if (!invisible.isEmpty()) {
                lastRow = r;
            }
            if (row.getPhysicalNumberOfCells() == 0 && !row.isFormatted() && row.getZeroHeight() == false
                    && !((org.apache.poi.xssf.usermodel.XSSFRow) row).getCTRow().isSetHt()) {
                sheet.removeRow(row);
            }
        }
        if (cells > 0) {
            log.info("sheet '{}': {} cell(s) showing nothing below the report (down to row {}) left out of the export",
                    sheet.getSheetName(), cells, lastRow + 1);
        }
    }

    /** @return the area's last row */
    private static int mark(XSSFWorkbook wb, Sheet sheet, TemplateMapping.SheetMapping sm) {
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
        int lastBound = java.util.stream.Stream.concat(sm.lists().stream().map(TemplateMapping.ListBinding::row),
                sm.values().stream().map(TemplateMapping.CellBinding::row)).mapToInt(Integer::intValue).max().orElse(0);
        if (lastRow - lastBound > FAR_BELOW) {
            Cell far = lowest(sheet);
            log.warn("sheet '{}': JXLS area down to row {} ({} rows below the last bound row) because {} shows {} - "
                    + "every row of the area is filled, clear that cell in the template if it is not needed",
                    sheet.getSheetName(), lastRow + 1, lastRow - lastBound, far == null ? "?" : far.getAddress(),
                    far == null ? "?" : far.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                            ? "=" + far.getCellFormula() : "'" + new DataFormatter().formatCellValue(far) + "'");
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
                bind(cell(sheet, list.row(), cell.col()), "${r." + cell.field() + "}");
            }
        }
        for (TemplateMapping.CellBinding value : sm.values()) {
            bind(cell(sheet, value.row(), value.col()), "${" + TemplateFields.SUMMARY + "." + value.field() + "}");
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
        return lastRow;
    }

    /**
     * The placeholder in place of whatever the cell held, its style kept. Blanked first: on a formula cell
     * {@code setCellValue} only replaces the cached result and keeps the formula - JXLS then saw the formula, never the
     * placeholder, and a bound "No." column holding {@code =ROW()-4} came out as the formula's result (2026-11-13).
     */
    private static void bind(Cell cell, String placeholder) {
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            cell.setBlank();
        }
        cell.setCellValue(placeholder);
    }

    /** Whether binding the cell would have to cut a multi-cell array formula, which Excel does not allow either. */
    static boolean inArrayFormula(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        Cell c = r == null ? null : r.getCell(col);
        return c != null && c.isPartOfArrayFormulaGroup();
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
