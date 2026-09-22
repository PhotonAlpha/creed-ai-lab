package com.creed.jasper;

import com.creed.jasper.dynamic.ReportShape;
import com.creed.jasper.dynamic.TableColumn;
import com.creed.jasper.dynamic.TableDesign;
import com.creed.jasper.dynamic.TableDesigner;
import com.creed.jasper.dynamic.TableRows;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.service.JasperReportService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generated table: that the columns really are data, and the three ways a generator like this
 * quietly produces a wrong document.
 *
 * <p>Asserted on the {@link JasperDesign} where the question is structural (how wide is column
 * three?) and on rendered PDF text where it is not (did the caption actually print?) — the design
 * is what {@code TableDesigner} writes, and the PDF is the only place to see that it filled.
 */
class TableDesignerTest {

    private static final String TEMPLATE = "jasper/approval-status.jrxml";

    /** Same geometry the real listing uses; the heights are sized for the Thai line. */
    private static final TableDesign LAYOUT =
            new TableDesign(22, 46, Color.decode("#C9CCD1"), "TableHeader", "TableCell");

    private final JasperReportService jasper = new JasperReportService(false);

    @Test
    void theColumnsAreDataAndTheTemplateHasNoTableOfItsOwn() throws JRException, IOException {
        // The template ships no columnHeader, no detail band and no fields. It is the generator
        // that puts them there -- which is the whole claim this module makes.
        JasperDesign bare = load();
        assertThat(bare.getColumnHeader()).as("the template declares no caption row").isNull();
        assertThat(((JRDesignSection) bare.getDetailSection()).getBandsList())
                .as("the template declares no detail band").isEmpty();
        assertThat(bare.getFieldsList()).as("the template declares no fields").isEmpty();

        List<TableColumn> columns = List.of(
                TableColumn.of("host", "Host", 40),
                TableColumn.of("ip", "IP", 30),
                TableColumn.of("env", "Env", 30));
        TableDesigner.write(bare, LAYOUT, columns);

        assertThat(bare.getColumnHeader()).isNotNull();
        assertThat(((JRDesignSection) bare.getDetailSection()).getBandsList()).hasSize(1);
        assertThat(bare.getFieldsList()).extracting("name")
                .containsExactly("host", "ip", "env");
    }

    @Test
    void theWeightsFillTheContentWidthExactly() throws JRException, IOException {
        // Relative weights, absolute page: the rounding remainder goes to the last column so the
        // row ends exactly on the right margin. A one-point gap there is the kind of thing nobody
        // can unsee once it ships.
        for (List<TableColumn> columns : List.of(
                List.of(TableColumn.of("a", "A", 1), TableColumn.of("b", "B", 1)),
                List.of(TableColumn.of("a", "A", 26), TableColumn.of("b", "B", 22),
                        TableColumn.of("c", "C", 34), TableColumn.of("d", "D", 18)),
                // Three thirds: 515/3 does not divide, which is the case that exposes the rounding.
                List.of(TableColumn.of("a", "A", 1), TableColumn.of("b", "B", 1),
                        TableColumn.of("c", "C", 1)))) {
            JasperDesign design = load();
            TableDesigner.write(design, LAYOUT, columns);

            JRDesignBand header = (JRDesignBand) design.getColumnHeader();
            int total = 0;
            int expectedX = 0;
            for (var element : header.getElements()) {
                assertThat(element.getX()).as("columns are laid end to end").isEqualTo(expectedX);
                expectedX += element.getWidth();
                total += element.getWidth();
            }
            assertThat(total).as("%d columns fill the content width", columns.size())
                    .isEqualTo(design.getColumnWidth());
        }
    }

    @Test
    void aDifferentColumnListIsADifferentDocument() throws JRException, IOException {
        // The point of the exercise: two shapes, one template, no file edited.
        String twoColumns = render(List.of(
                TableColumn.of("host", "Host", 50),
                TableColumn.of("ip", "IP Address", 50)));
        assertThat(twoColumns).contains("Host").contains("IP Address")
                .contains("creed-gw-01").contains("10.0.0.1")
                .doesNotContain("Zone");

        String fourColumns = render(List.of(
                TableColumn.of("host", "Host", 30),
                TableColumn.of("ip", "IP Address", 25),
                TableColumn.of("env", "Env", 20),
                TableColumn.of("zone", "Zone", 25)));
        assertThat(fourColumns).contains("Host").contains("Zone")
                .contains("cn-east-1a").contains("prod");
    }

    @Test
    void aKeyTheRowDoesNotCarryIsAnEmptyCellRatherThanAnError() throws JRException, IOException {
        // For a table whose shape the caller chose, a sparse row is normal -- the cell is blank,
        // not the string "null" and not an exception. (An unmapped FIELD is a different matter and
        // does throw; see FieldDataSource.)
        String page = render(List.of(
                TableColumn.of("host", "Host", 50),
                TableColumn.of("missing", "Not Supplied", 50)));
        assertThat(page).contains("Not Supplied").contains("creed-gw-01").doesNotContain("null");
    }

    @Test
    void aDuplicateFieldIsRefusedRatherThanShadowed() throws JRException, IOException {
        JasperDesign design = load();
        assertThatThrownBy(() -> TableDesigner.write(design, LAYOUT, List.of(
                TableColumn.of("host", "Host", 50),
                TableColumn.of("host", "Host again", 50))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate column field 'host'");
    }

    @Test
    void anEmptyTableIsRefused() throws JRException, IOException {
        JasperDesign design = load();
        assertThatThrownBy(() -> TableDesigner.write(design, LAYOUT, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one column");
    }

    @Test
    void writingTwiceReplacesTheTableRatherThanAccumulatingFields() throws JRException, IOException {
        // A design re-tabled with a different shape must not keep the fields of the old one:
        // JasperReports asks the data source for EVERY declared field, not only the ones an
        // expression references, so a leftover "ip" would be requested at fill time and answered
        // by a data source built from the new columns.
        JasperDesign design = load();
        TableDesigner.write(design, LAYOUT, List.of(
                TableColumn.of("host", "Host", 50), TableColumn.of("ip", "IP", 50)));
        TableDesigner.write(design, LAYOUT, List.of(
                TableColumn.of("host", "Host", 100)));

        assertThat(design.getFieldsList()).extracting("name").containsExactly("host");
        assertThat(((JRDesignSection) design.getDetailSection()).getBandsList()).hasSize(1);
        assertThat(((JRDesignBand) design.getColumnHeader()).getElements()).hasSize(1);
    }

    @Test
    void theCompiledReportIsCachedPerColumnListNotPerTemplate() {
        // Two shapes of one template are two compiled reports; caching on the file alone would
        // serve the first caller's columns to the second.
        JasperReportService caching = new JasperReportService(true);
        List<TableColumn> two = List.of(TableColumn.of("a", "A", 1), TableColumn.of("b", "B", 1));
        List<TableColumn> three = List.of(TableColumn.of("a", "A", 1),
                TableColumn.of("b", "B", 1), TableColumn.of("c", "C", 1));

        JasperReport first = caching.compile(ReportShape.document(TEMPLATE, LAYOUT, two));
        assertThat(caching.compile(ReportShape.document(TEMPLATE, LAYOUT, two)))
                .as("same shape, same report").isSameAs(first);
        assertThat(caching.compile(ReportShape.document(TEMPLATE, LAYOUT, three)))
                .as("different columns").isNotSameAs(first);
        // Chrome is part of the shape too: the table-only design is a different compiled report.
        assertThat(caching.compile(ReportShape.tableOnly(TEMPLATE, LAYOUT, two)))
                .as("same columns, no chrome").isNotSameAs(first);
    }

    private static JasperDesign load() throws JRException, IOException {
        try (InputStream in = new ClassPathResource(TEMPLATE).getInputStream()) {
            return JRXmlLoader.load(in);
        }
    }

    /** Fills the template with the given columns over a fixed three-row sample; page-one text. */
    private String render(List<TableColumn> columns) throws JRException, IOException {
        JasperReport report = jasper.compile(ReportShape.document(TEMPLATE, LAYOUT, columns));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JRParameter.REPORT_LOCALE, ReportLanguage.EN.locale());
        parameters.put("reportTitle", "Generated Table");
        parameters.put("note", "3 Record(s)");
        parameters.put("exportDate", "Sep 11, 2026");
        parameters.put("exportTime", "5:52:27 PM");
        // The criteria block is part of this template's chrome; a caller-shaped table has none, so
        // the subreport is handed an empty list and prints nothing.
        parameters.put("criteriaReport", jasper.compile("jasper/approval-status-criteria.jrxml"));
        parameters.put("criteriaData", TableRows.of(List.of(), List.of(
                TableColumn.of("label", "Label", 1), TableColumn.of("value", "Value", 1))));
        parameters.put("logo", "img/creed-logo.png");
        parameters.put("stamp", "img/creed-stamp.png");

        JasperPrint print = JasperFillManager.fillReport(report, parameters,
                TableRows.of(rows(), columns));
        PdfReader reader = new PdfReader(jasper.toPdf(print, "generated"));
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        }
        finally {
            reader.close();
        }
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] row : new String[][] {
                { "creed-gw-01", "10.0.0.1", "prod", "cn-east-1a" },
                { "creed-gw-02", "10.0.0.2", "staging", "ap-se-1a" },
                { "creed-pay-01", "10.0.0.3", "prod", "cn-east-1b" } }) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("host", row[0]);
            map.put("ip", row[1]);
            map.put("env", row[2]);
            map.put("zone", row[3]);
            rows.add(map);
        }
        return rows;
    }
}
