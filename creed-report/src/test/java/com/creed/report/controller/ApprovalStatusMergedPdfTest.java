package com.creed.report.controller;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryProperties;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.service.InvalidMergeRequestException;
import com.creed.report.service.PdfExportService;
import com.creed.report.service.PdfMergeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The merged export as the endpoint serves it — the wiring the service test cannot see: that the
 * separator and the face come from the same message bundle the template printed from, and that a
 * copy count outside the range is a 400 rather than a 500.
 *
 * <p>Called directly rather than through MockMvc, like the rest of this module's controller tests:
 * there is no web layer worth exercising here, and a direct call keeps the PDF in hand.
 */
class ApprovalStatusMergedPdfTest {

    private ApprovalStatusReportController controller;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(new MessageSourceConfig().messageSource());
        PdfExportService pdf = new PdfExportService(engine, new PathMatchingResourcePatternResolver(),
                "classpath:/fonts/*.ttf", "classpath:/static/img/creed-logo.png",
                "classpath:/static/img/creed-logo-inverse.png",
                "classpath:/static/img/creed-stamp.png");
        controller = new ApprovalStatusReportController(new ObjectMapper(), pdf, new PdfMergeService(),
                new MessageSourceConfig().messageSource(), new CountryCatalog(new CountryProperties()),
                new CountryStyles());
    }

    @Test
    void twoCopiesComeBackAsOneDocumentCountingToFour() throws IOException {
        ResponseEntity<byte[]> response = controller.exportMergedPdf(Locale.ENGLISH, 2, null);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("creed-approval-status-merged-");

        PdfReader reader = new PdfReader(response.getBody());
        try {
            int pages = reader.getNumberOfPages();
            assertThat(pages).as("two copies of a two-page statement").isEqualTo(4);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= pages; page++) {
                assertThat(extractor.getTextFromPage(page)).as("counter on page %d", page)
                        .contains(page + " of " + pages);
            }
        }
        finally {
            reader.close();
        }
    }

    @Test
    void theCounterIsStampedInTheLanguageTheDocumentWasAskedFor() throws IOException {
        // The endpoint's own job: pull `pdf.page.middle` and `pdf.font.family` out of the bundle
        // for THIS locale, so the stamped counter reads and is drawn like the one it replaced.
        // Thai needs the TH edition -- GLOBAL renders en/zh only -- which the resolver gives it
        // from the language tag.
        ResponseEntity<byte[]> response = controller.exportMergedPdf(Locale.forLanguageTag("th"), 2, null);

        PdfReader reader = new PdfReader(response.getBody());
        try {
            String separator = new MessageSourceConfig().messageSource()
                    .getMessage("pdf.page.middle", null, Locale.forLanguageTag("th"));
            int pages = reader.getNumberOfPages();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= pages; page++) {
                assertThat(extractor.getTextFromPage(page)).as("Thai counter on page %d", page)
                        .contains(page + separator + pages);
            }
        }
        finally {
            reader.close();
        }
    }

    @Test
    void twoLanguagesMergeIntoOneDocumentThatCountsStraightThrough() throws IOException {
        // The case one separator cannot serve: the English half prints "1 of 2" and the Thai half
        // "1 จาก 2", so the correction has to look for a different string on each half. The merged
        // file counts in the REQUEST's language -- English here -- on all four pages, Thai ones
        // included, because a document assembled from two languages has no third one of its own.
        ResponseEntity<byte[]> response =
                controller.exportMergedPdf(Locale.ENGLISH, 2, List.of("en", "th"));

        PdfReader reader = new PdfReader(response.getBody());
        try {
            int pages = reader.getNumberOfPages();
            assertThat(pages).isEqualTo(4);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= pages; page++) {
                assertThat(extractor.getTextFromPage(page)).as("counter on page %d", page)
                        .contains(page + " of " + pages);
            }
            // And the halves really are different documents: page 3 is the Thai edition, dated in
            // the Buddhist era. A `langs` list that quietly rendered everything in English would
            // still pass the counter check above.
            assertThat(extractor.getTextFromPage(1)).contains("Date of Export");
            assertThat(extractor.getTextFromPage(3)).contains("2569").doesNotContain("Date of Export");
        }
        finally {
            reader.close();
        }
    }

    @Test
    void aBadLanguageListIsRefusedLikeABadCopyCount() {
        assertThatThrownBy(() -> controller.exportMergedPdf(Locale.ENGLISH, 2, List.of("en")))
                .isInstanceOf(InvalidMergeRequestException.class)
                .hasMessageContaining("between 2 and 10");
        assertThatThrownBy(() -> controller.exportMergedPdf(Locale.ENGLISH, 2, List.of("en", "!!")))
                .isInstanceOf(InvalidMergeRequestException.class)
                .hasMessageContaining("not a language tag");
    }

    @Test
    void aCopyCountOutsideTheRangeIsRefusedRatherThanClamped() {
        // 400, not 500, and not a quietly capped document: the module's rule for caller input.
        assertThatThrownBy(() -> controller.exportMergedPdf(Locale.ENGLISH, 1, null))
                .isInstanceOf(InvalidMergeRequestException.class)
                .hasMessageContaining("between 2 and 10");
        assertThatThrownBy(() -> controller.exportMergedPdf(Locale.ENGLISH, 11, null))
                .isInstanceOf(InvalidMergeRequestException.class);
    }
}
