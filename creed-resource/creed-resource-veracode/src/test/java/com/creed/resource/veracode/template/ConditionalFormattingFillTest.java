package com.creed.resource.veracode.template;

import org.apache.poi.ss.usermodel.ConditionalFormatting;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional formatting is taken off before JXLS fills and put back after it, following the rows: JXLS copying it cell
 * by cell made an export quadratic in rows x rules (minutes for a company template).
 */
class ConditionalFormattingFillTest {

    /** Data row 5 (index 4), SCA's severity in column C. */
    private static final TemplateMapping MAPPING = new TemplateMapping(List.of(new TemplateMapping.SheetMapping("SCA",
            List.of(new TemplateMapping.ListBinding("sca", 4, List.of(new TemplateMapping.FieldCell(2, "severity")))),
            List.of())));

    @Test
    void eachRuleStaysOneEntryAndFollowsTheRows() throws Exception {
        Map<String, String> rules = fill(3);

        // the severity rule covers the three copies, its relative reference still the row's own
        assertThat(rules).containsEntry("$C5=\"High\"", "A5:F7")
                // the footer rule moved down two rows, and its formula with it
                .containsEntry("$A33=\"footer\"", "A33:F33")
                // the table frame grew; above the data row nothing moved; a whole column still ends on the last row
                .containsEntry("\"table\"", "A4:F33")
                .containsEntry("\"above\"", "A1:F3")
                .containsEntry("\"column\"", "B1:B1048576")
                .hasSize(5);
    }

    @Test
    void noItemsRemoveTheDataRow() throws Exception {
        Map<String, String> rules = fill(0);

        // the row is gone: its own rule with it, everything below one row up
        assertThat(rules).doesNotContainKey("$C5=\"High\"")
                .containsEntry("$A30=\"footer\"", "A30:F30")
                .containsEntry("\"table\"", "A4:F30");
    }

    @Test
    void manyRowsDoNotMultiplyTheRules() throws Exception {
        Map<String, String> rules = fill(500);

        assertThat(rules).hasSize(5).containsEntry("$C5=\"High\"", "A5:F504");
    }

    /** formula -> ranges, of the filled sheet's conditional formatting */
    private static Map<String, String> fill(int items) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            rows.add(Map.of("severity", i % 2 == 0 ? "High" : "Low"));
        }
        TemplateWorkbook.ForFill template = TemplateWorkbook.forFill(template(), MAPPING);
        assertThat(template.formatting().get("SCA")).hasSize(5);
        byte[] filled = TemplateWorkbook.withConditionalFormatting(JxlsPoiTemplateFillerBuilder.newInstance()
                        .withTemplate(new ByteArrayInputStream(template.workbook()))
                        .buildAndFill(new HashMap<>(Map.of("sca", rows))), MAPPING, template, Map.of("sca", items));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(filled))) {
            SheetConditionalFormatting cf = wb.getSheet("SCA").getSheetConditionalFormatting();
            Map<String, String> rules = new LinkedHashMap<>();
            for (int i = 0; i < cf.getNumConditionalFormattings(); i++) {
                ConditionalFormatting f = cf.getConditionalFormattingAt(i);
                rules.put(f.getRule(0).getFormula1(), String.join(" ", java.util.Arrays.stream(f.getFormattingRanges())
                        .map(CellRangeAddress::formatAsString).toList()));
            }
            return rules;
        }
    }

    private static byte[] template() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SCA");
            for (int r = 0; r < 31; r++) {
                sheet.createRow(r).createCell(0).setCellValue(r == 30 ? "footer" : "t" + r);
            }
            SheetConditionalFormatting cf = sheet.getSheetConditionalFormatting();
            rule(cf, "$C5=\"High\"", "A5:F5");
            rule(cf, "$A31=\"footer\"", "A31:F31");
            rule(cf, "\"table\"", "A4:F31");
            rule(cf, "\"above\"", "A1:F3");
            rule(cf, "\"column\"", "B1:B1048576");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void rule(SheetConditionalFormatting cf, String formula, String range) {
        ConditionalFormattingRule rule = cf.createConditionalFormattingRule(formula);
        rule.createPatternFormatting().setFillBackgroundColor(IndexedColors.RED.getIndex());
        cf.addConditionalFormatting(new CellRangeAddress[] {CellRangeAddress.valueOf(range)}, rule);
    }
}
