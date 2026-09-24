package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.controller.ApprovalStatusReportController;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.BaseFont;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Merging finished PDFs and correcting their page counters — asserted on the merged file, because
 * that is the only place the claim can be checked: a counter is text drawn into a page, and whether
 * the right one ended up there is not something the merge code can tell you about itself.
 *
 * <p>The parts are real approval-status renders through the real template, bundle chain and fonts,
 * for the same reason {@code ApprovalStatusPdfTest} uses the endpoint's own payload: a merge of
 * synthetic two-page PDFs would prove the arithmetic and nothing about the document this exists for.
 */
class PdfMergeServiceTest {

    private static final LocalDateTime EXPORTED_AT = LocalDateTime.of(2026, 9, 11, 17, 52, 27);

    private final CountryStyles countryStyles = new CountryStyles();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PdfMergeService merger = new PdfMergeService();

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
    void mergingKeepsEveryPageAndTheirOrder() throws IOException {
        byte[] part = render(Locale.ENGLISH);
        int partPages = pageCount(part);

        byte[] merged = merger.merge(List.of(part, part));

        assertThat(partPages).as("the sample has to paginate for any of this to mean anything")
                .isGreaterThan(1);
        assertThat(pageCount(merged)).isEqualTo(2 * partPages);
    }

    @Test
    void theCounterIsCorrectedAcrossTheWholeMergedDocument() throws IOException {
        byte[] part = render(Locale.ENGLISH);
        int partPages = pageCount(part);
        int total = 2 * partPages;

        byte[] merged = merger.mergeAndRenumber(List.of(part, part), numbering(Locale.ENGLISH));

        // THE POINT. Without the correction the second half repeats the first half's counter, and
        // a four-page document that counts to two twice is worse than one with no counter at all:
        // a reader cannot tell which page is missing.
        List<String> pages = textOfEveryPage(merged);
        for (int page = 1; page <= total; page++) {
            assertThat(pages.get(page - 1)).as("page %d of %d", page, total)
                    .contains(page + " of " + total);
        }
        // AND THE KNOWN COST OF DOING IT THIS WAY, pinned rather than papered over: the old
        // counter is covered, not deleted. It is invisible on the page and still in the text
        // layer, so an extractor -- or a copy-paste, or a screen reader -- sees both strings.
        // Removing it would mean rewriting the page's content stream; rendering the parts without
        // a counter in the first place would avoid it entirely. Neither is what this route is.
        assertThat(pages.get(partPages)).as("the covered counter is still in the text layer")
                .contains("1 of " + partPages).contains((partPages + 1) + " of " + total);
    }

    @Test
    void everythingElseOnThePageSurvivesTheStamp() throws IOException {
        byte[] part = render(Locale.ENGLISH);
        byte[] merged = merger.mergeAndRenumber(List.of(part, part), numbering(Locale.ENGLISH));

        // The cover rectangle is the risk this test exists for: it is painted over the counter's
        // own box, and the footnote sits two lines above it with the seal floated to the right.
        for (String page : textOfEveryPage(merged)) {
            assertThat(page).contains("Date of Export").contains("Time of Export")
                    .contains("Approval Status All List");
        }
        String firstPage = textOfEveryPage(merged).get(0);
        assertThat(firstPage).contains("13 Record(s)").contains("BK2600000001");
    }

    @Test
    void theStampFollowsTheLocaleTheDocumentWasRenderedIn() throws IOException {
        // Thai: a different separator ("จาก"), a different face, and a Buddhist-era footnote. The
        // stamped counter has to be drawn in the locale's own font -- Helvetica would render the
        // separator as nothing at all.
        byte[] part = render(Locale.forLanguageTag("th"));
        int total = 2 * pageCount(part);

        byte[] merged = merger.mergeAndRenumber(List.of(part, part), numbering(Locale.forLanguageTag("th")));

        List<String> pages = textOfEveryPage(merged);
        String separator = new MessageSourceConfig().messageSource()
                .getMessage("pdf.page.middle", null, Locale.forLanguageTag("th"));
        for (int page = 1; page <= total; page++) {
            assertThat(pages.get(page - 1)).as("Thai counter on page %d", page)
                    .contains(page + separator + total);
        }
    }

    @Test
    void partsNumberedInDifferentLanguagesAreAllCorrected() throws IOException {
        // The case a single separator cannot serve. An English part prints "1 of 2" and a Thai one
        // "1 จาก 2", and the correction finds the old counter BY THAT STRING -- so a merge told only
        // about " of " finds nothing on the Thai pages, warns, and leaves half the document
        // counting for its part. Part carries each half's own spelling.
        var messages = new MessageSourceConfig().messageSource();
        String en = messages.getMessage("pdf.page.middle", null, Locale.ENGLISH);
        String th = messages.getMessage("pdf.page.middle", null, Locale.forLanguageTag("th"));
        byte[] english = render(Locale.ENGLISH);
        byte[] thai = render(Locale.forLanguageTag("th"));
        int total = pageCount(english) + pageCount(thai);

        byte[] merged = merger.mergeParts(
                List.of(new PdfMergeService.Part(english, en), new PdfMergeService.Part(thai, th)),
                numbering(Locale.ENGLISH));

        List<String> pages = textOfEveryPage(merged);
        assertThat(pages).hasSize(total);
        for (int page = 1; page <= total; page++) {
            // Counted in the language the MERGE was asked in, Thai pages included: a file made of
            // two languages has no third one of its own, so this is the caller's decision.
            assertThat(pages.get(page - 1)).as("page %d of %d", page, total)
                    .contains(page + en + total);
        }
        // The halves are still their own editions -- the Thai one dates in the Buddhist era.
        assertThat(pages.get(pageCount(english))).contains("2569");
    }

    @Test
    void aPartWhoseCounterCannotBeFoundIsLeftAloneRatherThanGuessedAt() throws IOException {
        // Told the wrong separator for the second half, the merge has nothing to match on those
        // pages. It must not stamp a guessed position: a document with one honest counter and one
        // guess is harder to trust than one that was not renumbered. So the Thai pages keep their
        // own counter and the English ones are still corrected.
        var messages = new MessageSourceConfig().messageSource();
        String en = messages.getMessage("pdf.page.middle", null, Locale.ENGLISH);
        byte[] english = render(Locale.ENGLISH);
        byte[] thai = render(Locale.forLanguageTag("th"));
        int englishPages = pageCount(english);
        int total = englishPages + pageCount(thai);

        byte[] merged = merger.mergeParts(
                List.of(new PdfMergeService.Part(english, en), new PdfMergeService.Part(thai, en)),
                numbering(Locale.ENGLISH));

        List<String> pages = textOfEveryPage(merged);
        assertThat(pages.get(0)).contains(1 + en + total);
        String thaiSeparator = messages.getMessage("pdf.page.middle", null, Locale.forLanguageTag("th"));
        assertThat(pages.get(englishPages)).as("left exactly as it was")
                .contains(1 + thaiSeparator + pageCount(thai))
                .doesNotContain((englishPages + 1) + en + total);
    }

    @Test
    void mergingOneDocumentChangesNothingAndMergingNoneIsRefused() throws IOException {
        byte[] part = render(Locale.ENGLISH);

        // A single part already counts to its own length, so there is nothing to correct and
        // nothing is painted over -- the counters read exactly as they did.
        byte[] merged = merger.mergeAndRenumber(List.of(part), numbering(Locale.ENGLISH));
        int pages = pageCount(merged);
        List<String> text = textOfEveryPage(merged);
        for (int page = 1; page <= pages; page++) {
            assertThat(text.get(page - 1)).contains(page + " of " + pages);
        }

        assertThatThrownBy(() -> merger.merge(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    /** {@code -Dpdf.sample.dir} to eyeball the merged file, like the other PDF tests. */
    @Test
    @EnabledIfSystemProperty(named = "pdf.sample.dir", matches = ".+")
    void dumpMergedSample() throws IOException {
        Path dir = Path.of(System.getProperty("pdf.sample.dir"));
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("th"))) {
            byte[] part = render(locale);
            Files.write(dir.resolve("approval-status-merged-" + locale.toLanguageTag() + ".pdf"),
                    merger.mergeAndRenumber(List.of(part, part), numbering(locale)));
        }
    }

    private PdfMergeService.PageNumbering numbering(Locale locale) {
        var messages = new MessageSourceConfig().messageSource();
        BaseFont font = service.stampingFont(messages.getMessage("pdf.font.family", null, locale));
        return PdfMergeService.PageNumbering.statement(
                messages.getMessage("pdf.page.middle", null, locale), font);
    }

    private byte[] render(Locale locale) {
        // Thai is an edition's language, not a global one: GLOBAL offers en/zh-CN/zh-TW, so asking
        // it for Thai renders English and the test would be measuring the wrong document.
        return render(locale.getLanguage().equals("th") ? ReportCountry.TH : ReportCountry.GLOBAL, locale);
    }

    private byte[] render(ReportCountry country, Locale locale) {
        CountryProfile profile = CountryProfile.of(country, locale);
        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("pdfCss", countryStyles.pdf(profile.country(), profile.locale()));
        variables.put("report", ApprovalStatusReportController.sampleReport(objectMapper));
        variables.put("exportDate", CountryFormatter.date(EXPORTED_AT, profile));
        variables.put("exportTime", CountryFormatter.time(EXPORTED_AT, profile));
        return service.renderTemplate("approval-status-export-pdf", variables, profile.locale());
    }

    private static int pageCount(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        }
        finally {
            reader.close();
        }
    }

    private static List<String> textOfEveryPage(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(extractor.getTextFromPage(page));
            }
            return pages;
        }
        finally {
            reader.close();
        }
    }
}
