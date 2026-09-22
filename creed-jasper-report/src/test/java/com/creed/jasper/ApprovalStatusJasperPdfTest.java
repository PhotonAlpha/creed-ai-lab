package com.creed.jasper;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.render.ApprovalStatusRenderer;
import com.creed.jasper.service.ApprovalStatusSamples;
import com.creed.jasper.service.JasperReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval-status listing rendered through the real JasperReports engine, the real jrxml, the
 * real bundle chain and the real embedded fonts — asserted on the finished PDF, not on the template.
 *
 * <p>What is checked is what the picture shows and the jrxml cannot prove: two pages, the header
 * logo and the footer seal on <b>every</b> one of them, the footnote and the centred "N of M"
 * counter on every one, the criteria block four to a row, and the multi-line account cell.
 *
 * <p>Deliberately the same assertions as {@code creed-report}'s {@code ApprovalStatusPdfTest}:
 * the two modules render the same document, so the same things have to be true of both PDFs.
 */
class ApprovalStatusJasperPdfTest {

    /** A fixed instant, so a sample differs from the last one only when the layout does. */
    private static final LocalDateTime EXPORTED_AT = LocalDateTime.of(2026, 9, 11, 17, 52, 27);

    private final ObjectMapper objectMapper = new ObjectMapper();
    // cacheTemplates=true: the ENGINE is shared across the class, so each jrxml is compiled
    // once; the renderers over it are per-report values and cost nothing.
    private final JasperReportService jasper = new JasperReportService(true);

    @Test
    void theHardCodedSampleIsValidJsonAndComplete() {
        ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

        assertThat(report.title()).isEqualTo("Approval Status All List");
        assertThat(report.note()).contains("13 Record(s)");
        assertThat(report.criteria()).hasSize(10)
                .anySatisfy(criterion -> assertThat(criterion.label()).isEqualTo("Application Date"));
        // Thirteen rows is not decoration: it is what makes the document two pages, which is the
        // only way the repeated chrome and the page counter can be observed at all.
        assertThat(report.rows()).hasSize(13);
        assertThat(report.rows().get(0).account()).hasSize(4).last().isEqualTo("SGD");
        // The join is the model's, not the template's -- the breaks are data.
        assertThat(report.rows().get(0).accountText())
                .isEqualTo("VASA COMPANY 3 WITH ACCOUNT\nNAME MO\n1013672712\nSGD");
    }

    @Test
    void everyPageCarriesTheLogoTheSealTheFootnoteAndItsPageNumber() throws IOException {
        PdfReader reader = new PdfReader(render(ReportLanguage.EN));
        try {
            int pages = reader.getNumberOfPages();
            assertThat(pages).as("the sample must paginate, like the document it reproduces")
                    .isGreaterThan(1);

            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= pages; page++) {
                String text = extractor.getTextFromPage(page);
                assertThat(text).as("running footnote on page %d", page)
                        .contains("Date of Export").contains("Time of Export")
                        .contains("Approval Status All List");
                assertThat(text).as("centred page counter on page %d", page)
                        .contains(page + " of " + pages);
                // The <jr:table> repeats its own caption row on every page it spills onto -- what
                // a `columnHeader` band used to give, now the component's business.
                assertThat(text).as("the table's caption row on page %d", page)
                        .contains("Bank Reference").contains("Account").contains("Status");
                // The page header is a bare logo -- no text at all -- so the image count is the
                // only evidence it repeats. Two: the header logo and the footer's seal.
                assertThat(imagesOn(reader, page)).as("logo + seal on page %d", page)
                        .isGreaterThanOrEqualTo(2);
            }
        }
        finally {
            reader.close();
        }
    }

    @Test
    void thePageOneBodyIsTheDocumentInTheSample() throws IOException {
        PdfReader reader = new PdfReader(render(ReportLanguage.EN));
        try {
            String page = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(page)
                    // title band + the criteria block, captions and values
                    .contains("Approval Status All List")
                    .contains("Transaction / Deposit Type").contains("Customer Reference")
                    .contains("Application Date").contains("05/07/2026 - 02/09/2026")
                    // the count line, verbatim from the payload rather than recomputed
                    .contains("13 Record(s) (Note: This is a filtered table.)")
                    // the table, including the account cell's separate lines
                    .contains("Bulk MEPS").contains("BK2600000001")
                    .contains("VASA COMPANY 3 WITH ACCOUNT").contains("1013672712").contains("SGD")
                    .contains("Processing");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void theCriteriaBlockIsLaidOutFourToARow() throws IOException {
        // The subreport's columnCount=4 + printOrder=Horizontal, checked where it is observable:
        // the first four captions are extracted before the fifth, i.e. they are one row and the
        // block is not a single column running down the page. Vertical print order would put
        // "Currency" (the 5th) second.
        PdfReader reader = new PdfReader(render(ReportLanguage.EN));
        try {
            String page = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(page.indexOf("Account")).isLessThan(page.indexOf("Currency"));
            assertThat(page.indexOf("Currency")).isLessThan(page.indexOf("Payer / Payee"));
            // ...and the last row is short (two of four), which simply ends early.
            assertThat(page).contains("Payer / Payee");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void everyLanguageRendersTheWholeDocument() throws IOException {
        for (ReportLanguage language : ReportLanguage.values()) {
            PdfReader reader = new PdfReader(render(language));
            try {
                assertThat(reader.getNumberOfPages()).as("%s", language).isGreaterThan(1);
                // Latin survives in every language: the criteria VALUES are references and dates,
                // which is the assumption the Latin-only "Creed Sans Data" family rests on. A
                // missing or mis-ordered font family is what would blank these.
                assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).as("%s", language)
                        .contains("05/07/2026 - 02/09/2026").contains("BK2600000001");
            }
            finally {
                reader.close();
            }
        }
    }

    @Test
    void theFooterWordingAndTheThaiEraFollowTheLanguage() throws IOException {
        PdfReader reader = new PdfReader(render(ReportLanguage.TH));
        try {
            String page = new PdfTextExtractor(reader).getTextFromPage(1);
            // Bundle: the Thai properties, reached because ReportLanguage normalised the locale to
            // one this module ships rather than letting ResourceBundle fall back to the JVM's.
            assertThat(page).contains("วันที่ส่งออก").contains("เวลาที่ส่งออก");
            // Calendar: 2026 CE is 2569 BE. Nothing else in the document dates itself.
            assertThat(page).contains("2569").doesNotContain("2026,");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void everyCaptionSurvivesTheTallerScripts() throws IOException {
        // THE REGRESSION THIS MODULE ALREADY SHIPPED ONCE. A text element shorter than the line
        // its FACE needs prints nothing at all -- no warning, no clipped glyph -- and the Noto
        // faces are not the same height: at 8.5pt Latin needs 11.58pt, SC/TC 12.31, Thai 12.84.
        // A 12pt-tall caption therefore looks right in English and is BLANK in Chinese and Thai,
        // which is how the title and every criteria caption first went missing in two languages
        // while the English proof read perfectly. Only a non-Latin render can catch it.
        for (ReportLanguage language : new ReportLanguage[] {
                ReportLanguage.TH, ReportLanguage.ZH_CN, ReportLanguage.ZH_TW }) {
            String page = firstPageText(render(language));
            // The 14pt title, which prints in the title band AND in the footer -- so two
            // occurrences, and one of them going missing is exactly the failure being pinned.
            assertThat(page.split("Approval Status All List", -1).length - 1)
                    .as("title band + footer in %s", language).isEqualTo(2);
            // An 8.5pt criteria caption. "Customer Reference" appears nowhere else: the table's
            // own header says "Bank Reference", so this cannot pass on the header alone.
            assertThat(page).as("criteria captions in %s", language).contains("Customer Reference");
            // The 8pt footer line and page counter, the shortest elements in the document.
            assertThat(page).as("footer and counter in %s", language)
                    .contains("13 Record(s)").contains("1");
        }
    }

    @Test
    void theCaptionsWeightFollowsTheScript() throws IOException {
        // The jrxml's conditional styles, and this module's answer to creed-report's locale CSS
        // overlay: the same caption is bold in Chinese and regular in Thai. Asserted on the fonts
        // the page actually references -- a weight is not in the extracted text.
        assertThat(faceNamesOn(render(ReportLanguage.ZH_CN)))
                .as("zh bolds .criteria-label, so the SC Bold face has to be embedded")
                .anyMatch(name -> name.contains("SC") && name.contains("Bold"));
        assertThat(faceNamesOn(render(ReportLanguage.TH)))
                .as("th un-bolds the small chrome; the Thai Bold face is still there for the table"
                        + " header, so this only pins that the Thai family resolved at all")
                .anyMatch(name -> name.contains("Thai"));
    }

    /** Same escape hatch as creed-report's PDF tests: {@code -Dpdf.sample.dir} to eyeball them. */
    @Test
    @EnabledIfSystemProperty(named = "pdf.sample.dir", matches = ".+")
    void dumpSamples() throws IOException {
        Path dir = Path.of(System.getProperty("pdf.sample.dir"));
        for (ReportLanguage language : ReportLanguage.values()) {
            Files.write(dir.resolve("approval-status-jasper-"
                    + language.locale().toLanguageTag() + ".pdf"), render(language));
        }
    }

    /** Page one's extracted text. */
    private static String firstPageText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        }
        finally {
            reader.close();
        }
    }

    /** The BaseFont names page one references — how an embedded face is observable at all. */
    private static java.util.Set<String> faceNamesOn(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary fonts = resources == null ? null : resources.getAsDict(PdfName.FONT);
            if (fonts != null) {
                for (PdfName key : fonts.getKeys()) {
                    PdfDictionary font = fonts.getAsDict(key);
                    PdfName baseFont = font == null ? null : font.getAsName(PdfName.BASEFONT);
                    if (baseFont != null) {
                        names.add(PdfName.decodeName(baseFont.toString()));
                    }
                }
            }
            return names;
        }
        finally {
            reader.close();
        }
    }

    private byte[] render(ReportLanguage language) {
        return new ApprovalStatusRenderer(jasper, ApprovalStatusSamples.report(objectMapper), EXPORTED_AT)
                .render(ExportRequest.of(ExportFormat.PDF, language));
    }

    /** How many image XObjects the page's resources reference. */
    private static int imagesOn(PdfReader reader, int page) {
        PdfDictionary resources = reader.getPageN(page).getAsDict(PdfName.RESOURCES);
        PdfDictionary xObjects = resources == null ? null : resources.getAsDict(PdfName.XOBJECT);
        if (xObjects == null) {
            return 0;
        }
        return (int) xObjects.getKeys().stream()
                .map(xObjects::getAsStream)
                .filter(stream -> stream != null && PdfName.IMAGE.equals(stream.get(PdfName.SUBTYPE)))
                .count();
    }

    /** Lossless byte-to-char view, for searching ASCII names inside binary PDF output. */
    @SuppressWarnings("unused")
    private static String ascii(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }
}
