package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.ReportProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.ReportRow;
import com.creed.resource.veracode.service.ReportParser.ParsedReport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportParserTest {

    /** Binds an empty source, so the record's {@code @DefaultValue}s are what the parser sees. */
    private final ReportParser parser = new ReportParser(
            new Binder(new MapConfigurationPropertySource()).bindOrCreate("veracode.report", ReportProperties.class));

    @Test
    void readsTheReportTableAndSkipsTheOthers() {
        ParsedReport report = parser.parseStorage("""
                <table><tbody><tr><th>Scan date</th><th>Findings</th></tr><tr><td>x</td><td>1</td></tr></tbody></table>
                <table><tbody>
                <tr><th><p>CVE ID</p></th><th>Vulnerability</th><th>Library</th><th>severity</th><th>CVSS</th><th>Affects&nbsp;Version</th></tr>
                <tr><td><p>cve-2021-44228</p></td><td>Log4Shell</td><td>log4j-core</td><td>Very High</td><td>10.0</td><td>2.3.0&nbsp;</td></tr>
                </tbody></table>
                """);

        assertThat(report.rows()).singleElement().satisfies(r -> {
            assertThat(r.cve()).isEqualTo("CVE-2021-44228");
            assertThat(r.title()).isEqualTo("Log4Shell");
            assertThat(r.affectedVersion()).isEqualTo("2.3.0");
            assertThat(r.cvss()).isEqualTo(10.0);
            assertThat(r.location()).isEqualTo("table 2, row 1");
        });
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void aCellWithTwoCvesIsTwoRowsAndANonCveCellFallsBackToTheTitle() {
        ParsedReport report = parser.parseStorage(table(
                "<tr><td>CVE-2021-4104, CVE-2022-23302</td><td>Log4j 1.2</td><td>2.3.0</td><td>6.6 (v3.1)</td></tr>",
                "<tr><td>N/A (CWE-89)</td><td>SQL Injection</td><td>2.3.0</td><td></td></tr>"));

        assertThat(report.rows()).extracting(ReportRow::cve).containsExactly("CVE-2021-4104", "CVE-2022-23302", null);
        assertThat(report.rows().getFirst().cvss()).isEqualTo(6.6);
        assertThat(report.rows().get(2).title()).isEqualTo("SQL Injection");
    }

    @Test
    void incompleteRowsAreReportedNotGuessed() {
        ParsedReport report = parser.parseStorage(table(
                "<tr><td>CVE-2021-44228</td><td>Log4Shell</td><td></td><td></td></tr>",
                "<tr><td></td><td></td><td>2.3.0</td><td>3.3</td></tr>",
                "<tr><td></td><td></td><td></td><td></td></tr>",
                "<tr><td>CVE-2022-1471</td><td>SnakeYaml</td><td>2.3.0</td><td>high</td></tr>"));

        assertThat(report.rows()).extracting(ReportRow::cve).containsExactly("CVE-2022-1471");
        assertThat(report.warnings()).containsExactly(
                "table 1, row 1: skipped, no affected version",
                "table 1, row 2: skipped, neither a CVE nor a title",
                "table 1, row 4: CVSS 'high' is not a number, left empty");
    }

    @Test
    void aNestedTableContributesNoRows() {
        ParsedReport report = parser.parseStorage(table(
                "<tr><td>CVE-2021-44228</td><td>Log4Shell<table><tr><td>CVE-2000-0001</td><td>x</td><td>9.9</td></tr></table></td><td>2.3.0</td><td></td></tr>"));

        assertThat(report.rows()).extracting(ReportRow::cve).containsExactly("CVE-2021-44228");
    }

    @Test
    void aPageWithoutAReportTableIsRejected() {
        assertThatThrownBy(() -> parser.parseStorage("<table><tr><th>Approver</th></tr></table>"))
                .isInstanceOf(ReportParser.ReportFormatException.class)
                .hasMessageContaining("affected-version");
        assertThatThrownBy(() -> parser.parseStorage(""))
                .isInstanceOf(ReportParser.ReportFormatException.class);
    }

    @Test
    void rowsWithTheSameCveAndVersionMergeIntoOneFinding() {
        ParsedReport report = parser.parseStorage("""
                <table><tr><th>CVE</th><th>Title</th><th>Component</th><th>Severity</th><th>CVSS</th><th>Affected Version</th></tr>
                <tr><td>CVE-2022-1471</td><td>SnakeYaml</td><td>snakeyaml 1.33</td><td>Medium</td><td>5.0</td><td>2.3.0</td></tr>
                <tr><td>CVE-2022-1471</td><td>SnakeYaml</td><td>snakeyaml 1.30</td><td>High</td><td>8.3</td><td>2.3.0</td></tr>
                <tr><td>CVE-2022-1471</td><td>SnakeYaml</td><td>snakeyaml 1.33</td><td>High</td><td>8.3</td><td>2.4.0</td></tr>
                </table>
                """);

        List<Finding> findings = Finding.group(report.rows());

        assertThat(findings).extracting(Finding::id).containsExactly("CVE-2022-1471@2.3.0", "CVE-2022-1471@2.4.0");
        assertThat(findings.getFirst().components()).containsExactly("snakeyaml 1.33", "snakeyaml 1.30");
        assertThat(findings.getFirst().severity()).isEqualTo("High");
        assertThat(findings.getFirst().cvss()).isEqualTo(8.3);
        assertThat(findings.getFirst().locations()).containsExactly("table 1, row 1", "table 1, row 2");
    }

    @Test
    void csvWithQuotesNewlinesBomAndSemicolons() {
        String csv = "\uFEFFCVE;Title;Affected Version;CVSS;Description\r\n"
                + "CVE-2021-44228;\"Log4Shell; \"\"JNDI\"\"\";2.3.0;10.0;\"line one\nline two\"\r\n"
                + ";;;;\r\n"
                + "N/A;SQL Injection;2.3.0;;\n";

        ParsedReport report = parser.parseFile("report.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows().getFirst()).satisfies(r -> {
            assertThat(r.cve()).isEqualTo("CVE-2021-44228");
            assertThat(r.title()).isEqualTo("Log4Shell; \"JNDI\"");
            assertThat(r.description()).isEqualTo("line one\nline two");
            assertThat(r.location()).isEqualTo("CSV, row 2");
        });
        assertThat(report.rows().get(1).location()).isEqualTo("CSV, row 4");
    }

    @Test
    void csvThatIsNotUtf8IsReadAsGb18030() {
        String csv = "CVE,Title,Affected Version\nCVE-2022-1471,反序列化漏洞,2.3.0\n";

        ParsedReport report = parser.parseFile("report.csv", csv.getBytes(Charset.forName("GB18030")));

        assertThat(report.rows().getFirst().title()).isEqualTo("反序列化漏洞");
    }

    @Test
    void workbookFindsTheHeaderBelowTitleRowsAndKeepsVersionsAsShown() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet notes = wb.createSheet("Notes");
            notes.createRow(0).createCell(0).setCellValue("nothing here");
            Sheet sheet = wb.createSheet("Findings");
            sheet.createRow(0).createCell(0).setCellValue("Veracode SCA - creed-platform");
            Row header = sheet.createRow(2);
            String[] names = {"CVE", "Title", "Affected Version", "CVSS"};
            for (int i = 0; i < names.length; i++) {
                header.createCell(i).setCellValue(names[i]);
            }
            Row row = sheet.createRow(3);
            row.createCell(0).setCellValue("CVE-2020-36518");
            row.createCell(1).setCellValue("jackson-databind DoS");
            row.createCell(2).setCellValue(2.3);   // typed as a number in Excel
            row.createCell(3).setCellValue(7.5);
            wb.write(out);
            xlsx = out.toByteArray();
        }

        // No extension: sniffed from the zip signature.
        ParsedReport report = parser.parseFile("download", xlsx);

        assertThat(report.rows()).singleElement().satisfies(r -> {
            assertThat(r.affectedVersion()).isEqualTo("2.3");
            assertThat(r.cvss()).isEqualTo(7.5);
            assertThat(r.location()).isEqualTo("sheet 'Findings', row 4");
        });
    }

    @Test
    void anHtmlFileReadsLikeThePageBody() {
        byte[] html = ("<html><head><meta charset=\"utf-8\"></head><body>" + table(
                "<tr><td>CVE-2021-44228</td><td>Log4Shell</td><td>2.3.0</td><td>10</td></tr>") + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parseFile("Veracode Report.html", html).rows()).extracting(ReportRow::cve)
                .containsExactly("CVE-2021-44228");
    }

    @Test
    void unknownFilesAreRejected() {
        assertThatThrownBy(() -> parser.parseFile("report.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ReportParser.UnsupportedFileException.class);
        assertThatThrownBy(() -> parser.parseFile("report.csv", new byte[0]))
                .isInstanceOf(ReportParser.ReportFormatException.class);
        assertThatThrownBy(() -> parser.parseFile("report.xlsx", "not a zip".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ReportParser.ReportFormatException.class);
    }

    private static String table(String... rows) {
        return "<table><tbody><tr><th>CVE</th><th>Title</th><th>Affected Version</th><th>CVSS</th></tr>"
                + String.join("", rows) + "</tbody></table>";
    }
}
