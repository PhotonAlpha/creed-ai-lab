package com.creed.report.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * The workbook's shared cell styles, created once and reused by every sheet.
 *
 * <p>Styles are workbook-scoped resources in POI (an .xlsx file holds a single style table and
 * Excel caps it at 64k entries), so they must never be created per cell — hence this holder,
 * built once per export and handed to every {@link ExcelSheetBuilder} of that workbook.
 *
 * <p>The palette mirrors the report page: a dark header band like Bootstrap's {@code table-dark},
 * a muted caption line, and thin grid borders on the data cells.
 */
final class ExcelStyles {

    private final CellStyle title;
    private final CellStyle caption;
    private final CellStyle header;
    private final CellStyle text;
    private final CellStyle number;
    private final CellStyle label;

    ExcelStyles(Workbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);

        Font captionFont = workbook.createFont();
        captionFont.setItalic(true);
        captionFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        Font labelFont = workbook.createFont();
        labelFont.setBold(true);

        this.title = workbook.createCellStyle();
        this.title.setFont(titleFont);
        this.title.setVerticalAlignment(VerticalAlignment.CENTER);

        this.caption = workbook.createCellStyle();
        this.caption.setFont(captionFont);

        this.header = bordered(workbook);
        this.header.setFont(headerFont);
        this.header.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
        this.header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        this.header.setAlignment(HorizontalAlignment.LEFT);
        this.header.setVerticalAlignment(VerticalAlignment.CENTER);

        this.text = bordered(workbook);
        this.text.setVerticalAlignment(VerticalAlignment.TOP);

        this.number = bordered(workbook);
        this.number.setAlignment(HorizontalAlignment.RIGHT);
        this.number.setVerticalAlignment(VerticalAlignment.TOP);

        this.label = bordered(workbook);
        this.label.setFont(labelFont);
        this.label.setVerticalAlignment(VerticalAlignment.TOP);
    }

    private static CellStyle bordered(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    CellStyle title() {
        return title;
    }

    CellStyle caption() {
        return caption;
    }

    CellStyle header() {
        return header;
    }

    CellStyle text() {
        return text;
    }

    CellStyle number() {
        return number;
    }

    /** Bold-but-bordered style for the key column of the key/value summary sheets. */
    CellStyle label() {
        return label;
    }
}
