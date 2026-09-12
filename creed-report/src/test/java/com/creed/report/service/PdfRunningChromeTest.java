package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.creed.report.model.ServerInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF header and footer must appear on <b>every</b> page, logo included. That is not something
 * the markup shows: it depends on the {@code .page-frame} wrapper table and
 * {@code -fs-table-paginate}, and the two mechanisms that look like they should work instead
 * (an image in an {@code @page} margin box, a {@code position: fixed} box in the page margin)
 * render nothing at all. So these tests render a report long enough to paginate and inspect the
 * produced PDF page by page — text via {@link PdfTextExtractor}, the logo via each page's image
 * XObjects.
 *
 * <p>The two reports wear different chrome and are therefore asserted differently, each on the
 * text only its running block can produce:
 * <ul>
 * <li>{@code report-export-pdf} (dark-bar chrome) keys the header off the meta line (the timestamp,
 * "PDF snapshot") rather than the brand: its {@code @top-left} margin box prints a nearly identical
 * title on every page, so a brand-only assertion would pass even with the running header gone. Two
 * images per page — header logo and footer logo.</li>
 * <li>{@code dynamic-report-export-pdf} (form chrome, modelled on the sample bank form) prints no
 * {@code @top-left} at all, so the brand IS proof of the running header there; the meta line moved
 * into the footnote and is asserted as footer text. Its footnote is text only, like the sample's,
 * so a page carries one image, not two.</li>
 * <li>{@code dynamic-report-statement-pdf} (statement chrome, modelled on docs/template.jpg) has a
 * header of <b>pure image</b> — the logo and nothing else — so there is no header text to assert;
 * what proves that header repeats is the image count, two per page, the second image being the seal
 * in the footnote. That footnote is <b>not</b> in the frame: it is a running element drawn in the
 * {@code @bottom-center} margin box, so these assertions are also what proves that mechanism
 * repeats — text, seal and the "1 of 3" counter it carries, on every page.</li>
 * </ul>
 */
class PdfRunningChromeTest {

    private static final String GENERATED_AT = "2026-09-02 12:00:00";
    private static final String FOOTER_NOTE = "Powered by Spring Boot";

    private final CountryStyles countryStyles = new CountryStyles();

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
    void bothLogosResolveToDataUris() {
        // No base URL reaches the renderer, so anything but a data: URI would silently not draw.
        assertThat(service.logo()).startsWith("data:image/png;base64,");
        assertThat(service.logoInverse()).startsWith("data:image/png;base64,")
                .isNotEqualTo(service.logo());
    }

    /** A logo is decoration: a wrong path must cost the image, never the export. */
    @Test
    void aMissingLogoDegradesToNoImageRatherThanFailing() {
        PdfExportService missing = new PdfExportService(new SpringTemplateEngine(),
                new PathMatchingResourcePatternResolver(), "",
                "classpath:/static/img/does-not-exist.png", "classpath:/static/img/nor-does-this.png",
                "classpath:/static/img/no-stamp.png");
        assertThat(missing.logo()).isEmpty();
        assertThat(missing.logoInverse()).isEmpty();
    }

    /** Same "the PDF half degrades" rule the country templates follow: one logo file is enough. */
    @Test
    void anAbsentInverseLogoFallsBackToThePlainOne() {
        PdfExportService oneLogo = new PdfExportService(new SpringTemplateEngine(),
                new PathMatchingResourcePatternResolver(), "",
                "classpath:/static/img/creed-logo.png", "classpath:/static/img/does-not-exist.png",
                "classpath:/static/img/no-stamp.png");
        assertThat(oneLogo.logoInverse()).isEqualTo(oneLogo.logo()).isNotEmpty();
    }

    /**
      * Anything that is not PNG/JPEG/GIF is refused up front rather than embedded as bytes the
      * renderer would quietly skip. A .css file stands in for "some non-image file" here.
      */
    @Test
    void anSvgLogoIsRejectedBecauseTheRendererCannotDrawOne() {
        PdfExportService svg = new PdfExportService(new SpringTemplateEngine(),
                new PathMatchingResourcePatternResolver(), "",
                "classpath:/static/css/report.css", "", "");
        assertThat(svg.logo()).isEmpty();
    }

    // 160 rows so the report certainly paginates -- on a single page every one of these
    // assertions would hold even with the running chrome removed.
    @Test
    void theDynamicReportRepeatsHeaderFooterAndLogoOnEveryPage() throws IOException {
        // Form chrome: the brand only ever comes from the running header (no @top-left margin box),
        // and the meta line is the footnote's second line. One logo, in the header. The expected
        // text is upper case because the form title is -- text-transform, applied by the renderer.
        assertRunningChrome(dynamicPdf(160), "dynamic-report-export-pdf",
                List.of("SERVER INVENTORY REPORT"),
                List.of(FOOTER_NOTE, GENERATED_AT, "PDF snapshot"), 1);
    }

    @Test
    void theStatementReportRepeatsHeaderFooterAndSealOnEveryPage() throws IOException {
        // Statement chrome: no header TEXT at all, so the header's proof is the image count --
        // two per page means both the <thead> logo and the <tfoot> seal made it onto every one.
        // "Date of Export" is printed only by the running footnote.
        byte[] pdf = statementPdf(160);
        assertRunningChrome(pdf, "dynamic-report-statement-pdf", List.of(),
                List.of("Date of Export", "Time of Export", "Ad hoc"), 2);
        assertPageCounter(pdf);
    }

    @Test
    void theServerReportRepeatsHeaderFooterAndLogoOnEveryPage() throws IOException {
        assertRunningChrome(serverPdf(160), "report-export-pdf",
                List.of(GENERATED_AT, "PDF snapshot"), List.of(FOOTER_NOTE), 2);
    }

    private void assertRunningChrome(byte[] pdf, String template, List<String> header,
                                     List<String> footer, int logos) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages())
                    .as("%s should paginate, otherwise this proves nothing", template)
                    .isGreaterThan(2);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                String text = extractor.getTextFromPage(page);
                // An empty list means the layout's header carries no text at all (the statement
                // chrome's is a bare logo); its repetition is proved by the image count below.
                if (!header.isEmpty()) {
                    assertThat(text).as("%s running header on page %d", template, page)
                            .contains(header);
                }
                assertThat(text).as("%s running footer on page %d", template, page)
                        .contains(footer);
                assertThat(imagesOn(reader, page))
                        .as("%s running logo(s) on page %d", template, page)
                        .isGreaterThanOrEqualTo(logos);
            }
        }
        finally {
            reader.close();
        }
    }

    /**
     * The statement chrome's second footer storey: {@code @bottom-center} prints "<page> of
     * <pages>" — the sample's wording, and the reason that chrome uses only {@code pdf.page.middle}
     * and none of the prefix/suffix keys the other two wrap their counter in.
     */
    private static void assertPageCounter(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            int pages = reader.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                assertThat(extractor.getTextFromPage(page))
                        .as("statement page counter on page %d", page)
                        .contains(page + " of " + pages);
            }
        }
        finally {
            reader.close();
        }
    }

    /** How many image XObjects the page's resources reference — two logos per page here. */
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

    private byte[] dynamicPdf(int rows) {
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        DynamicTable table = table(rows, profile);
        return service.renderTemplate("dynamic-report-export-pdf", Map.of(
                "profile", profile,
                "pdfCss", countryStyles.pdf(profile.country()),
                "table", table,
                "total", String.valueOf(table.size()),
                "generatedAt", GENERATED_AT), profile.locale());
    }

    /** The same table on statement paper: the second layout of the same endpoint. */
    private byte[] statementPdf(int rows) {
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        DynamicTable table = table(rows, profile);
        return service.renderTemplate("dynamic-report-statement-pdf", Map.of(
                "profile", profile,
                "pdfCss", countryStyles.pdf(profile.country()),
                "table", table,
                "total", String.valueOf(table.size()),
                "generatedAt", GENERATED_AT,
                "exportDate", "02/09/2026",
                "exportTime", "12:00:00"), profile.locale());
    }

    private static DynamicTable table(int rows, CountryProfile profile) {
        DynamicTableService tables = new DynamicTableService(new ObjectMapper(),
                new MessageSourceConfig().messageSource(), new DynamicTableProperties());
        StringBuilder data = new StringBuilder("[");
        for (int i = 1; i <= rows; i++) {
            data.append(i == 1 ? "" : ",")
                    .append("{\"host\":\"creed-host-").append(i)
                    .append("\",\"ip\":\"10.0.0.").append(i % 250)
                    .append("\",\"uptimeDays\":").append(i).append("}");
        }
        return tables.build(
                new DynamicTableRequest("Ad hoc", "host,ip,uptimeDays", data.append("]").toString()),
                profile, profile.locale());
    }

    private byte[] serverPdf(int rows) {
        List<ServerInfo> servers = new ArrayList<>(IntStream.rangeClosed(1, rows)
                .mapToObj(i -> new ServerInfo("creed-host-" + i, "10.0.0." + (i % 250),
                        "creed-gateway", "CN", "gateway", "prod", "cn-east-1a", "blue"))
                .toList());
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        return service.renderTemplate("report-export-pdf", Map.of(
                "profile", profile,
                "pdfCss", countryStyles.pdf(profile.country()),
                "servers", servers,
                "total", String.valueOf(servers.size()),
                "generatedAt", GENERATED_AT), profile.locale());
    }
}
