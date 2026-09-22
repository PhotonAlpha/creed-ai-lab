package com.creed.report.service;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.i18n.ReportCountry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser preview of the PDF template ({@code GET /dynamic/preview/pdf}), at the level that
 * matters: it must be the <b>same</b> XHTML the PDF is laid out from, plus a stylesheet link, and
 * nothing about it may leak into a PDF render.
 *
 * <p>Rendered through {@link PdfExportService#renderTemplateHtml} on the same standalone engine and
 * bundle chain {@link PdfExportServiceTest} uses, so a preview that only works because a Spring
 * context papered over something cannot pass here.
 */
class PdfPreviewHtmlTest {

    private static final String TEMPLATE = "dynamic-report-export-pdf";
    private static final String STATEMENT_TEMPLATE = "dynamic-report-statement-pdf";
    private static final String PREVIEW_CSS = "/report/css/report-pdf-preview.css";
    private static final String HEADERS = "host,ip,app,uptimeDays";
    private static final String DATA = """
            [{"host":"creed-th-gw-01","ip":"10.30.1.11","app":"creed-gateway","uptimeDays":1234}]
            """;

    private final CountryStyles countryStyles = new CountryStyles();
    private DynamicTableService tableService;
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
        tableService = new DynamicTableService(new ObjectMapper(),
                new MessageSourceConfig().messageSource(), new DynamicTableProperties());
        service = new PdfExportService(engine, new PathMatchingResourcePatternResolver(),
                "classpath:/fonts/*.ttf,classpath:/fonts/*.otf",
                "classpath:/static/img/creed-logo.png",
                "classpath:/static/img/creed-logo-inverse.png",
                "classpath:/static/img/creed-stamp.png");
    }

    @Test
    void thePreviewIsTheExportsMarkupPlusTheStylesheetLink() {
        // WYSIWYG has exactly one mechanical guarantee behind it: strip the shim from the preview
        // and what is left is byte-for-byte what the renderer was given.
        String export = render(ReportCountry.GLOBAL, Locale.ENGLISH, false);
        String preview = render(ReportCountry.GLOBAL, Locale.ENGLISH, true);

        assertThat(preview).contains(PREVIEW_CSS);
        assertThat(stripShim(preview)).isEqualTo(stripShim(export));
    }

    @Test
    void aPdfRenderCarriesNoPreviewMarkup() {
        String export = render(ReportCountry.GLOBAL, Locale.ENGLISH, false);

        // th:replace outranks th:if on one tag; the outer th:block is what keeps this true.
        // (Both the preview stylesheet's name and the words "<link>" appear in the template's own
        // comments, so assert on the markup the shim would have emitted, not on those strings.)
        assertThat(export).doesNotContain("<link rel=")
                .doesNotContain("--preview-page-width")
                .doesNotContain(PREVIEW_CSS);
    }

    @Test
    void thePageBoxMirrorsTheFormChromesPageRule() {
        String preview = render(ReportCountry.GLOBAL, Locale.ENGLISH, true);

        // The same numbers the @page rule in report-chrome-pdf :: formStyles carries; the preview
        // sheet is worthless as a measurement if the two drift apart.
        assertThat(preview).contains("@page").contains("margin: 14mm 12mm 16mm 12mm;");
        assertThat(preview).contains("--preview-page-width: 297mm;")
                .contains("--preview-page-height: 210mm;")
                .contains("--preview-page-margin: 14mm 12mm 16mm 12mm;")
                .contains("--preview-margin-top: 14mm;")
                .contains("--preview-content-height: 180mm;");
    }

    @Test
    void theStatementPreviewMirrorsItsOwnPageRuleAndNotTheFormChromes() {
        // Each chrome set owns its page geometry, so the preview numbers must follow the template
        // being previewed -- a portrait layout measured against the landscape sheet would put every
        // column and every wrap point in the wrong place, which is the one thing this shim exists
        // to get right.
        String preview = render(STATEMENT_TEMPLATE, ReportCountry.GLOBAL, Locale.ENGLISH, true);

        assertThat(preview).contains("size: A4;").contains("margin: 14mm 14mm 34mm 14mm;");
        assertThat(preview).contains("--preview-page-width: 210mm;")
                .contains("--preview-page-height: 297mm;")
                .contains("--preview-page-margin: 14mm 14mm 34mm 14mm;")
                .contains("--preview-content-height: 249mm;")
                .doesNotContain("--preview-page-width: 297mm;");
        // The shim parks the running footer in the bottom margin band, which it can only do if the
        // chrome hands it that band's size along with the page box.
        assertThat(preview).contains("--preview-margin-bottom: 34mm;")
                .contains("--preview-margin-side: 14mm;");
    }

    @Test
    void theStatementLayoutPrintsItsSealAndNoPreviewMarkup() {
        String export = render(STATEMENT_TEMPLATE, ReportCountry.GLOBAL, Locale.ENGLISH, false);

        // The seal comes from the same render funnel the logos do -- no controller passes it.
        assertThat(export).contains("statement-stamp").contains("data:image/png;base64,");
        assertThat(export).doesNotContain("<link rel=").doesNotContain("--preview-page-width");
    }

    @Test
    void thePreviewKeepsTheEmbeddedLogoAndTheLocalesFontStack() {
        // Both come from the render funnel, not the controller: a preview missing either would
        // still look plausible and measure differently from the PDF.
        assertThat(render(ReportCountry.GLOBAL, Locale.ENGLISH, true))
                .contains("data:image/png;base64,")
                .contains("font-family: 'Noto Sans', Helvetica, sans-serif;");
        assertThat(render(ReportCountry.TH, Locale.forLanguageTag("th"), true))
                .contains("font-family: 'Noto Sans Thai', 'Noto Sans', Helvetica, sans-serif;");
    }

    @Test
    void thePreviewStillRendersToAPdf() {
        // The shim is browser-only CSS, but it must not be able to break the renderer either.
        byte[] pdf = service.renderHtml(render(ReportCountry.GLOBAL, Locale.ENGLISH, true));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    /**
     * Cuts out the one region the shim may occupy — from the end of the template comment that
     * introduces it to {@code </head>} — so the two renders can be compared everywhere else.
     */
    private static String stripShim(String html) {
        int marker = html.indexOf("<!-- Browser preview only:");
        assertThat(marker).as("the template's preview marker comment").isGreaterThan(0);
        int start = html.indexOf("-->", marker) + 3;
        int end = html.indexOf("</head>");
        assertThat(end).isGreaterThan(start);
        return html.substring(0, start) + html.substring(end);
    }

    private String render(ReportCountry country, Locale language, boolean preview) {
        return render(TEMPLATE, country, language, preview);
    }

    private String render(String template, ReportCountry country, Locale language, boolean preview) {
        CountryProfile profile = CountryProfile.of(country, language);
        DynamicTable table = tableService.build(
                new DynamicTableRequest("Preview", HEADERS, DATA), profile, language);

        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("pdfCss", countryStyles.pdf(country, profile.locale()));
        variables.put("table", table);
        variables.put("total", "1");
        variables.put("generatedAt", "2026-09-10 12:00:00");
        variables.put("exportDate", "10/09/2026");
        variables.put("exportTime", "12:00:00");
        if (preview) {
            variables.put("previewCss", PREVIEW_CSS);
        }
        return service.renderTemplateHtml(template, variables, profile.locale());
    }
}
