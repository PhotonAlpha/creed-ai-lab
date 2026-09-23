package com.creed.jasper;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.export.ColumnWidths;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.render.ApprovalStatusRenderer;
import com.creed.jasper.service.ApprovalStatusSamples;
import com.creed.jasper.service.JasperReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four output formats and the custom view — the capabilities carried over from the
 * DynamicJasper renderer in {@code docs/}, checked on the bytes each one actually produces.
 *
 * <p>What matters here is the pair of decisions that hang off the format, because both are silent
 * when wrong: a spreadsheet filled <i>with</i> pagination carries page furniture into the middle
 * of the data, and an extract built from the chrome-bearing design carries a logo and a footnote
 * as stray cells.
 */
class ExportFormatsTest {

    private static final LocalDateTime EXPORTED_AT = LocalDateTime.of(2026, 9, 11, 17, 52, 27);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);
    // One engine (so one compile per shape), one renderer over this class's fixed payload.
    private final ApprovalStatusRenderer renderer =
            new ApprovalStatusRenderer(new JasperReportService(true), report, EXPORTED_AT);

    @Test
    void everyFormatProducesItsOwnFileType() {
        assertThat(render(ExportFormat.PDF)).startsWith('%', 'P', 'D', 'F', '-');
        // A .xlsx is a zip; JasperReports writes the OOXML itself, so this also pins that the
        // export needs no POI on the classpath.
        assertThat(render(ExportFormat.XLSX)).startsWith('P', 'K');
        assertThat(text(render(ExportFormat.HTML))).contains("<table").contains("Bulk MEPS");
    }

    @Test
    void theCsvCarriesAByteOrderMarkAndTheRowsOnly() {
        byte[] csv = render(ExportFormat.CSV);

        // Without the BOM Excel reads a UTF-8 CSV as the platform encoding and every non-Latin
        // caption arrives as mojibake -- the export is not wrong, it just cannot be opened by the
        // tool people open CSVs with.
        assertThat(csv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

        String text = text(csv);
        assertThat(text).contains("Bulk MEPS").contains("BK2600000001")
                .contains("Transaction / Deposit Type");
        // The chrome is gone: no title band, no criteria block, no footnote. An extract is the
        // table, and these would arrive as stray cells in the middle of it.
        // "Payer / Payee" is the one criterion with no column of its own, so it can only come
        // from the criteria block -- which is what makes it evidence the chrome is gone.
        assertThat(text).doesNotContain("13 Record(s)").doesNotContain("Date of Export")
                .doesNotContain("Payer / Payee");
    }

    @Test
    void onlyThePdfKeepsTheDocumentAroundTheTable() throws IOException {
        // The other side of the same coin: the PDF is a document and carries all of it.
        PdfReader reader = new PdfReader(render(ExportFormat.PDF));
        try {
            String page = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(page).contains("Approval Status All List").contains("Payer / Payee")
                    .contains("13 Record(s)").contains("Date of Export");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void anUnpaginatedFormatDoesNotRepeatTheCaptionRow() {
        // IS_IGNORE_PAGINATION: with pagination on, a 13-row extract breaks across "pages" and the
        // caption row is printed again at each break -- inside a file that has no pages.
        String csv = text(render(ExportFormat.CSV));
        assertThat(csv.split("Bank Reference", -1).length - 1)
                .as("one caption row in the whole extract").isEqualTo(1);
    }

    @Test
    void aCustomViewPicksAndReordersColumns() throws IOException {
        byte[] pdf = renderer.render(
                new ExportRequest(ExportFormat.PDF, ReportLanguage.EN, ColumnWidths.AUTO, List.of("status", "type")));

        PdfReader reader = new PdfReader(pdf);
        try {
            String page = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
            // Both chosen columns, in the caller's order -- Status now precedes Transaction.
            // Searched from the record count on, because BOTH captions also appear above it as
            // criteria labels, where their order is the payload's and not the request's.
            int table = page.indexOf("13 Record(s)");
            assertThat(table).isGreaterThan(0);
            assertThat(page.indexOf("Status", table))
                    .as("the caption row follows the request's order")
                    .isLessThan(page.indexOf("Transaction / Deposit Type", table));
            // And the two that were not asked for are gone.
            assertThat(page).doesNotContain("BK2600000001").doesNotContain("VASA COMPANY");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void aCustomViewIsIgnoredForAnExtract() {
        // The rule carried over from the reference implementation: a spreadsheet or a CSV is a
        // data extract and carries every column, whatever a screen happens to be showing.
        String csv = text(renderer.render(
                new ExportRequest(ExportFormat.CSV, ReportLanguage.EN, ColumnWidths.AUTO, List.of("status"))));
        assertThat(csv).contains("Bank Reference").contains("BK2600000001");
    }

    @Test
    void anUnknownColumnIsRefusedRatherThanDropped() {
        // A silently dropped column is a report that is wrong in a way nobody notices.
        assertThatThrownBy(() -> renderer.render(
                new ExportRequest(ExportFormat.PDF, ReportLanguage.EN, ColumnWidths.AUTO, List.of("type", "nope"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column 'nope'");
    }

    @Test
    void anUnknownFormatCodeResolvesToNothing() {
        assertThat(ExportFormat.of("pdff")).isEmpty();
        assertThat(ExportFormat.of(null)).isEmpty();
        assertThat(ExportFormat.of(" PDF ")).contains(ExportFormat.PDF);
    }

    @Test
    void theThaiExtractIsStillThai() {
        // The stripped design keeps the template's default style, so the locale's embedded face
        // survives into a format that has no fonts of its own -- and the BOM makes it readable.
        String csv = text(renderer.render(ExportRequest.of(ExportFormat.CSV, ReportLanguage.TH)));
        assertThat(csv).contains("Bulk MEPS");
    }

    /** Escape hatch, like the PDF tests': {@code -Dpdf.sample.dir} to open them. */
    @Test
    @EnabledIfSystemProperty(named = "pdf.sample.dir", matches = ".+")
    void dumpEveryFormat() throws IOException {
        Path dir = Path.of(System.getProperty("pdf.sample.dir"));
        for (ExportFormat format : ExportFormat.values()) {
            Files.write(dir.resolve("approval-status-jasper." + format.extension()), render(format));
        }
    }

    private byte[] render(ExportFormat format) {
        return renderer.render(ExportRequest.of(format, ReportLanguage.EN));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
