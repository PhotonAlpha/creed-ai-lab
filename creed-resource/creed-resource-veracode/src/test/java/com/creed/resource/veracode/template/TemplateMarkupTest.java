package com.creed.resource.veracode.template;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a company workbook may hold that the sample does not: other sheet names, formulas in the cells bound. */
class TemplateMarkupTest {

    private static final List<Map<String, Object>> ROWS = List.of(Map.of("severity", "High", "no", 1),
            Map.of("severity", "Low", "no", 2), Map.of("severity", "High", "no", 3));

    /**
     * The mapping names its sheet as the workbook does - nothing is looked up by a fixed name - and JXLS takes any name
     * Excel allows: spaces, CJK, an apostrophe, characters of formula syntax, a name that reads as a reference.
     */
    @Test
    void anySheetNameExcelAllowsIsFilled() throws Exception {
        for (String name : List.of("SCA 组件漏洞", "O'Brien data", "Data (2026)", "a!b", "x,y;z", "A1", "R1C1", "TRUE",
                "汇总 & 统计", "#data", "Sheet with 31 characters-------")) {
            byte[] template = workbook(name, false);
            TemplateMapping mapping = mapping(name);
            validate(mapping, template);

            List<String> column = column(fill(template, mapping), name, 1);

            assertThat(column).as(name).startsWith("DT", "", "", "", "High", "Low", "High");
        }
    }

    /** A bound cell that held a formula ("No." as =ROW()-4) takes the placeholder, not the formula's result. */
    @Test
    void aFormulaInABoundCellIsReplaced() throws Exception {
        byte[] template = workbook("SCA", true);

        List<String> column = column(fill(template, mapping("SCA")), "SCA", 1);

        assertThat(column).startsWith("DT", "", "", "", "High", "Low", "High");
    }

    /**
     * A helper column filled down the sheet (=IF(..,"",..) - the case seen: jx:area lastCell L1047958) neither stretches
     * the area nor reaches JXLS; a formatted cell below the report and a real value far down stay.
     */
    @Test
    void cellsShowingNothingBelowTheReportStayOutOfTheArea() throws Exception {
        byte[] template;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(workbook("SCA", false)));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheet("SCA");
            for (int r = 3; r < 5000; r++) {
                Row row = sheet.getRow(r) != null ? sheet.getRow(r) : sheet.createRow(r);
                org.apache.poi.xssf.usermodel.XSSFCell helper = (org.apache.poi.xssf.usermodel.XSSFCell) row.createCell(11);
                helper.setCellFormula("IF(E" + (r + 1) + "=\"\",\"\",E" + (r + 1) + "*2)");
                // Excel's cached result: empty text
                helper.getCTCell().setT(org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.STR);
                helper.getCTCell().setV("");
            }
            sheet.getRow(20).createCell(13).setCellValue("   ");
            org.apache.poi.ss.usermodel.CellStyle bordered = wb.createCellStyle();
            bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            sheet.getRow(30).createCell(3).setCellStyle(bordered);
            wb.write(out);
            template = out.toByteArray();
        }
        TemplateMapping mapping = mapping("SCA");

        // the page says they are there, so the template can be cleaned in Excel
        TemplateWorkbook.SheetView view = TemplateWorkbook.read(template).stream()
                .filter(v -> v.name().equals("SCA")).findFirst().orElseThrow();
        assertThat(view.rows()).isEqualTo(6);
        assertThat(view.hidden()).isEqualTo(new TemplateWorkbook.HiddenCells(4995, 5000, "L7: =IF(E7=\"\",\"\",E7*2)"));
        assertThat(TemplateWorkbook.read(workbook("SCA", false)).get(1).hidden()).isNull();

        try (XSSFWorkbook marked = new XSSFWorkbook(new ByteArrayInputStream(TemplateWorkbook.withJxlsMarkup(template, mapping)))) {
            assertThat(marked.getSheet("SCA").getRow(0).getCell(0).getCellComment().getString().getString())
                    .startsWith("jx:area(lastCell=\"C6\")");
        }
        TemplateWorkbook.ForFill forFill = TemplateWorkbook.forFill(template, mapping);
        try (XSSFWorkbook toFill = new XSSFWorkbook(new ByteArrayInputStream(forFill.workbook()))) {
            Sheet sheet = toFill.getSheet("SCA");
            // the helpers below the area are gone (a row left empty with them), the one inside it stays; the formatted
            // cell stays, its row with it
            assertThat(sheet.getRow(4999)).isNull();
            assertThat(sheet.getRow(20)).isNull();
            assertThat(sheet.getRow(30).getCell(11)).isNull();
            assertThat(sheet.getRow(4).getCell(11).getCellFormula()).isEqualTo("IF(E5=\"\",\"\",E5*2)");
            assertThat(sheet.getRow(30).getCell(3).getCellStyle().getBorderBottom())
                    .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        }
        assertThat(column(fill(template, mapping), "SCA", 1)).startsWith("DT", "", "", "", "High", "Low", "High");
    }

    @Test
    void aCellOfAnArrayFormulaCannotBeBound() throws Exception {
        byte[] template;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(workbook("SCA", false)));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.getSheet("SCA").setArrayFormula("A1:A3*2", CellRangeAddress.valueOf("B5:C5"));
            wb.write(out);
            template = out.toByteArray();
        }

        assertThatThrownBy(() -> validate(mapping("SCA"), template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SCA!B5: the cell is part of an array formula - remove the formula in Excel or bind another cell");
    }

    /** Data row 5 (B = severity, C = no), summary B1; the mapped sheet is not the first. */
    private static TemplateMapping mapping(String sheet) {
        return new TemplateMapping(List.of(new TemplateMapping.SheetMapping(sheet,
                List.of(new TemplateMapping.ListBinding("sca", 4, List.of(new TemplateMapping.FieldCell(1, "severity"),
                        new TemplateMapping.FieldCell(2, "no")))),
                List.of(new TemplateMapping.CellBinding(0, 1, "projectKey")))));
    }

    private static byte[] workbook(String name, boolean formulas) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("封面").createRow(0).createCell(0).setCellValue("cover");
            Sheet sheet = wb.createSheet(name);
            for (int r = 0; r < 6; r++) {
                sheet.createRow(r).createCell(0).setCellValue(r == 5 ? "footer" : "t" + r);
            }
            if (formulas) {
                sheet.getRow(0).createCell(1).setCellFormula("TODAY()");
                sheet.getRow(4).createCell(1).setCellFormula("ROW()-4");
                sheet.getRow(4).createCell(2).setCellFormula("1+1");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void validate(TemplateMapping mapping, byte[] template) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            ReportTemplateService.validate(mapping, wb);
        }
    }

    private static byte[] fill(byte[] template, TemplateMapping mapping) {
        TemplateWorkbook.ForFill forFill = TemplateWorkbook.forFill(template, mapping);
        byte[] filled = JxlsPoiTemplateFillerBuilder.newInstance()
                .withTemplate(new ByteArrayInputStream(forFill.workbook()))
                .buildAndFill(new HashMap<>(Map.of("sca", ROWS, "summary", Map.of("projectKey", "DT"))));
        return TemplateWorkbook.withConditionalFormatting(filled, mapping, forFill, Map.of("sca", ROWS.size()));
    }

    private static List<String> column(byte[] xlsx, String sheet, int col) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("cover");
            Sheet s = wb.getSheet(sheet);
            DataFormatter formatter = new DataFormatter();
            List<String> values = new ArrayList<>();
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                values.add(row == null ? "" : formatter.formatCellValue(row.getCell(col)));
            }
            return values;
        }
    }
}
