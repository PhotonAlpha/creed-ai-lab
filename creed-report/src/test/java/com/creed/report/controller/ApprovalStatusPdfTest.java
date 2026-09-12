package com.creed.report.controller;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.creed.report.model.ApprovalStatusReport;
import com.creed.report.service.PdfExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval-status listing, rendered through the real engine, bundle chain and fonts — the
 * layout {@code docs/template.jpg} describes, checked on the finished PDF rather than on the markup.
 *
 * <p>It renders the endpoint's <b>own</b> payload ({@link ApprovalStatusReportController#sampleReport})
 * rather than a copy: this document has no inputs, so the only thing that could make the test pass
 * while the endpoint ships something else is a second sample, and there isn't one.
 *
 * <p>What is asserted is what the picture shows and the markup cannot prove: two pages, the header
 * logo and the footer seal on <b>every</b> one of them, the footnote and the centred "N of M"
 * counter on every one, and the criteria block and multi-line account cell present in the text.
 */
class ApprovalStatusPdfTest {

    private final CountryStyles countryStyles = new CountryStyles();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PdfExportService service;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
        service = new PdfExportService(engine, new PathMatchingResourcePatternResolver(),
                "classpath:/fonts/*.ttf", "classpath:/static/img/creed-logo.png",
                "classpath:/static/img/creed-logo-inverse.png",
                "classpath:/static/img/creed-stamp.png");
    }

    @Test
    void theHardCodedSampleIsValidJsonAndComplete() {
        ApprovalStatusReport report = ApprovalStatusReportController.sampleReport(objectMapper);

        assertThat(report.title()).isEqualTo("Approval Status All List");
        assertThat(report.note()).contains("13 Record(s)");
        assertThat(report.criteria()).hasSize(10)
                .anySatisfy(criterion -> assertThat(criterion.label()).isEqualTo("Application Date"));
        // Thirteen rows is not decoration: it is what makes the document two pages, which is the
        // only way the repeated chrome and the page counter can be observed at all.
        assertThat(report.rows()).hasSize(13);
        assertThat(report.rows().get(0).account()).hasSize(4).last().isEqualTo("SGD");
    }

    @Test
    void everyPageCarriesTheLogoTheSealTheFootnoteAndItsPageNumber() throws IOException {
        PdfReader reader = new PdfReader(render());
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
                // The header is a bare logo -- no text at all -- so the image count is the only
                // evidence it repeats. Two: the header logo and the footnote's seal.
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
        PdfReader reader = new PdfReader(render());
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

    /** Same escape hatch as {@code PdfSampleDumpTest}: {@code -Dpdf.sample.dir} to eyeball it. */
    @Test
    @EnabledIfSystemProperty(named = "pdf.sample.dir", matches = ".+")
    void dumpSample() throws IOException {
        Files.write(Path.of(System.getProperty("pdf.sample.dir"), "approval-status.pdf"), render());
    }

    private byte[] render() {
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("pdfCss", countryStyles.pdf(profile.country()));
        variables.put("report", ApprovalStatusReportController.sampleReport(objectMapper));
        variables.put("exportDate", "Sep 11, 2026");
        variables.put("exportTime", "5:52:27 PM");
        return service.renderTemplate("approval-status-export-pdf", variables, profile.locale());
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
}
