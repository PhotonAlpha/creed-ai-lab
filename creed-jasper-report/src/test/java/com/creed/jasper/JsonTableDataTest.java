package com.creed.jasper;

import com.creed.jasper.dynamic.ReportShape;
import com.creed.jasper.dynamic.TableColumn;
import com.creed.jasper.dynamic.TableDesign;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.render.TableData;
import com.creed.jasper.service.JasperReportService;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filling straight from JSON — the first thing the DynamicJasper renderer in {@code docs/1.jpeg}
 * does ({@code JsonQueryExecuterFactory.JSON_INPUT_STREAM}), carried over.
 *
 * <p>It is the half of the migration that makes caller-defined columns useful end to end: with a
 * generated table <b>and</b> a JSON source there is no Java type for the row anywhere, so a caller
 * can send both the shape and the data and get a report back. {@code $F{host}} binds to the JSON
 * property {@code host}; nothing had to be compiled against it.
 */
class JsonTableDataTest {

    private static final String TEMPLATE = "jasper/approval-status.jrxml";
    private static final TableDesign LAYOUT =
            new TableDesign(22, 24, Color.decode("#C9CCD1"), "TableHeader", "TableCell");

    private static final String JSON = """
            { "servers": [
                { "host": "creed-gw-01",  "ip": "10.0.0.1", "env": "prod" },
                { "host": "creed-gw-02",  "ip": "10.0.0.2", "env": "staging" },
                { "host": "creed-pay-01", "ip": "10.0.0.3", "env": "prod" }
            ] }
            """;

    private final JasperReportService jasper = new JasperReportService(true);

    @Test
    void theRowsComeOutOfTheJsonWithNoJavaTypeInBetween() {
        List<TableColumn> columns = List.of(
                TableColumn.of("host", "Host", 40),
                TableColumn.of("ip", "IP Address", 30),
                TableColumn.of("env", "Env", 30));

        String csv = new String(render(columns, "servers"), StandardCharsets.UTF_8);

        assertThat(csv).contains("Host").contains("IP Address").contains("Env")
                .contains("creed-gw-01").contains("10.0.0.3").contains("staging");
    }

    @Test
    void aColumnTheJsonDoesNotCarryIsAnEmptyCell() {
        // Same contract as the map-backed source: for a table whose shape the caller chose, a
        // property the data lacks is blank, not "null" and not a failure.
        String csv = new String(render(List.of(
                TableColumn.of("host", "Host", 50),
                TableColumn.of("zone", "Zone", 50)), "servers"), StandardCharsets.UTF_8);

        assertThat(csv).contains("Zone").contains("creed-gw-01").doesNotContain("null");
    }

    /**
     * Fills the chrome-stripped template from JSON and exports CSV — CSV because what is being
     * checked is the binding, and a text format shows it without a PDF reader in the way.
     */
    private byte[] render(List<TableColumn> columns, String path) {
        TableData data = TableData.json(JSON, path);
        ReportShape shape = ReportShape.tableOnly(TEMPLATE, LAYOUT, columns).fedByJson(data.jsonQuery());
        JasperReport report = jasper.compile(shape);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JRParameter.REPORT_LOCALE, ReportLanguage.EN.locale());
        parameters.put(JRParameter.IS_IGNORE_PAGINATION, Boolean.TRUE);
        data.contributeTo(parameters);

        // No data source: the query executer builds one from the stream the TableData contributed.
        return jasper.export(report, parameters, data.dataSource(), ExportFormat.CSV);
    }
}
