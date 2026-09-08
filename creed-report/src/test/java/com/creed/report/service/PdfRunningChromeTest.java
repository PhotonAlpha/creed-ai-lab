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
 * <p>The header assertion deliberately keys off the meta line (the timestamp, "PDF snapshot")
 * rather than the brand: the {@code @top-left} margin box prints a nearly identical title on every
 * page, so a brand-only assertion would pass even with the running header gone.
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
                "classpath:/static/img/creed-logo-inverse.png");
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
                "classpath:/static/img/does-not-exist.png", "classpath:/static/img/nor-does-this.png");
        assertThat(missing.logo()).isEmpty();
        assertThat(missing.logoInverse()).isEmpty();
    }

    /** Same "the PDF half degrades" rule the country templates follow: one logo file is enough. */
    @Test
    void anAbsentInverseLogoFallsBackToThePlainOne() {
        PdfExportService oneLogo = new PdfExportService(new SpringTemplateEngine(),
                new PathMatchingResourcePatternResolver(), "",
                "classpath:/static/img/creed-logo.png", "classpath:/static/img/does-not-exist.png");
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
                "classpath:/static/css/report.css", "");
        assertThat(svg.logo()).isEmpty();
    }

    // 160 rows so the report certainly paginates -- on a single page every one of these
    // assertions would hold even with the running chrome removed.
    @Test
    void theDynamicReportRepeatsHeaderFooterAndLogoOnEveryPage() throws IOException {
        assertRunningChrome(dynamicPdf(160), "dynamic-report-export-pdf");
    }

    @Test
    void theServerReportRepeatsHeaderFooterAndLogoOnEveryPage() throws IOException {
        assertRunningChrome(serverPdf(160), "report-export-pdf");
    }

    private void assertRunningChrome(byte[] pdf, String template) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages())
                    .as("%s should paginate, otherwise this proves nothing", template)
                    .isGreaterThan(2);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                String text = extractor.getTextFromPage(page);
                assertThat(text).as("%s running header on page %d", template, page)
                        .contains(GENERATED_AT).contains("PDF snapshot");
                assertThat(text).as("%s running footer on page %d", template, page)
                        .contains(FOOTER_NOTE);
                assertThat(imagesOn(reader, page))
                        .as("%s header + footer logo on page %d", template, page)
                        .isGreaterThanOrEqualTo(2);
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
        DynamicTableService tables = new DynamicTableService(new ObjectMapper(),
                new MessageSourceConfig().messageSource(), new DynamicTableProperties());
        StringBuilder data = new StringBuilder("[");
        for (int i = 1; i <= rows; i++) {
            data.append(i == 1 ? "" : ",")
                    .append("{\"host\":\"creed-host-").append(i)
                    .append("\",\"ip\":\"10.0.0.").append(i % 250)
                    .append("\",\"uptimeDays\":").append(i).append("}");
        }
        CountryProfile profile = CountryProfile.of(ReportCountry.GLOBAL, Locale.ENGLISH);
        DynamicTable table = tables.build(
                new DynamicTableRequest("Ad hoc", "host,ip,uptimeDays", data.append("]").toString()),
                profile, profile.locale());
        return service.renderTemplate("dynamic-report-export-pdf", Map.of(
                "profile", profile,
                "pdfCss", countryStyles.pdf(profile.country()),
                "table", table,
                "total", String.valueOf(table.size()),
                "generatedAt", GENERATED_AT), profile.locale());
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
