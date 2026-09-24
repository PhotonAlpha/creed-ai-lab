package com.creed.report.controller;

import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.model.ApprovalStatusReport;
import com.creed.report.service.InvalidMergeRequestException;
import com.creed.report.service.PdfExportService;
import com.creed.report.service.PdfMergeService;
import com.lowagie.text.pdf.BaseFont;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>approval-status listing</b> — a faithful reproduction of the printed document in
 * {@code creed-report/docs/template.jpg}, exported as PDF.
 *
 * <pre>
 * GET|POST /approval-status/export/pdf   the PDF
 * GET|POST /approval-status/preview/pdf  the same markup as text/html, for devtools
 * </pre>
 *
 * <p><b>The data is hard-coded here, on purpose.</b> This endpoint exists to pin down a <i>layout</i>
 * — what the header, title band, criteria block, table, footnote and page counter look like when
 * they are right — so it takes no input at all: two calls a week apart produce the same document
 * except for the export timestamp, which is exactly what makes it usable as a reference render and
 * as a regression test. It is the sample payload, not a data source; a report that reads real data
 * takes the same template and passes a model built somewhere else.
 *
 * <p>The JSON is a literal rather than a Java object graph because that is the form the eventual
 * caller will send, so the model, the parse and the template are all exercised the way they will be
 * used — and because the sample is much easier to compare against the picture as JSON.
 *
 * <p><b>Chrome.</b> It wears the statement chrome from {@code fragments/report-chrome-pdf.html}
 * whole ({@code statementStyles/statementHeader/statementTitle/statementFooter}) — logo alone above
 * a rule, title in brand blue between two rules, a two-storey running footer (export date/time and
 * document name opposite the seal, page counter centred below in the {@code @bottom-center} margin
 * box). That set was built for this document, so nothing here defines chrome of its own.
 *
 * <p><b>Brand.</b> The sample is a UOB document; this renders the layout under <i>Creed's</i> own
 * logo and seal. The deliverable is the form, not another organisation's identity.
 */
@Controller
public class ApprovalStatusReportController {

    private static final String PDF_TEMPLATE = "approval-status-export-pdf";

    /** Context-path-relative; the preview resolves it against the request. */
    private static final String PREVIEW_STYLESHEET = "/css/report-pdf-preview.css";

    /**
     * The sample payload, transcribed from {@code docs/template.jpg}.
     *
     * <p>Thirteen rows because the sample says "13 Record(s)" and prints "1 of 2" — the document is
     * two pages, which is the only way this endpoint can demonstrate that the header, the footnote,
     * the seal and the page counter really do repeat. A three-row sample would prove nothing.
     */
    private static final String SAMPLE_JSON = """
            {
              "title": "Approval Status All List",
              "criteria": [
                { "label": "Transaction / Deposit Type", "value": "--" },
                { "label": "Bank Reference",             "value": "--" },
                { "label": "Customer Reference",         "value": "--" },
                { "label": "Account",                    "value": "--" },
                { "label": "Currency",                   "value": "--" },
                { "label": "Amount",                     "value": "--" },
                { "label": "Value / Placement Date",     "value": "--" },
                { "label": "Application Date",           "value": "05/07/2026 - 02/09/2026" },
                { "label": "Payer / Payee",              "value": "--" },
                { "label": "Status",                     "value": "--" }
              ],
              "note": "13 Record(s) (Note: This is a filtered table.)",
              "rows": [
                { "type": "Bulk MEPS", "bankReference": "BK2600000001",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000002",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000003",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000004",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000005",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000006",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000007",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000008",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000009",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000010",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000011",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Approved" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000012",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Approved" },
                { "type": "Telegraphic Transfer", "bankReference": "BK2600000013",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Rejected" }
              ]
            }
            """;

    /** How many copies {@code /export/pdf/merged} will bind together. */
    private static final int MIN_COPIES = 2;
    private static final int MAX_COPIES = 10;

    private final ObjectMapper objectMapper;
    private final PdfExportService pdfExportService;
    private final PdfMergeService pdfMergeService;
    private final MessageSource messageSource;
    private final CountryCatalog countryCatalog;
    private final CountryStyles countryStyles;

    public ApprovalStatusReportController(ObjectMapper objectMapper,
                                          PdfExportService pdfExportService,
                                          PdfMergeService pdfMergeService,
                                          MessageSource messageSource,
                                          CountryCatalog countryCatalog,
                                          CountryStyles countryStyles) {
        this.objectMapper = objectMapper;
        this.pdfExportService = pdfExportService;
        this.pdfMergeService = pdfMergeService;
        this.messageSource = messageSource;
        this.countryStyles = countryStyles;
        this.countryCatalog = countryCatalog;
    }

    /** The document, as PDF. */
    @RequestMapping(value = "/approval-status/export/pdf",
            method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);

        byte[] body = pdfExportService.renderTemplate(PDF_TEMPLATE, model(profile, locale, now), locale);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        // ASCII and sortable, like every other export here -- never the country's own date format.
        headers.setContentDispositionFormData("attachment",
                "creed-approval-status-" + now.format(ReportController.FILE_TS) + ".pdf");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * The same markup, served as {@code text/html} — the PDF template in devtools, exactly as
     * {@code /dynamic/preview/pdf} does it. Same string by construction: it is
     * {@link PdfExportService#renderTemplate} stopped before the renderer, so what the browser
     * shows and what Flying Saucer laid out cannot drift.
     */
    /**
     * The same listing <b>twice over in one file</b>, with the footer's page counter corrected to
     * run across the whole document.
     *
     * <p>What it demonstrates is the correction, not the payload: two renders of a two-page
     * statement both say "1 of 2" and "2 of 2", so a naive concatenation is a four-page document
     * that counts to two twice. {@link PdfMergeService} finds each page's old counter, paints it
     * out and redraws it, so the merged file reads 1, 2, 3, 4 of 4.
     *
     * <p>Both halves are rendered exactly as {@code /approval-status/export/pdf} renders its one —
     * same template, same model, same locale — because a merge whose parts are built differently
     * from the real export proves nothing about the real export.
     *
     * @param copies how many to bind together, 2..10. Out of range is a <b>400</b>, like every
     *               other bad input here: a silently clamped count answers a document nobody asked
     *               for
     */
    @RequestMapping(value = "/approval-status/export/pdf/merged",
            method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportMergedPdf(Locale locale,
                                                  @RequestParam(defaultValue = "2") int copies,
                                                  @RequestParam(required = false) List<String> langs) {
        LocalDateTime now = LocalDateTime.now();
        List<Locale> languages = languagesFor(locale, copies, langs);

        // Each part carries the separator IT printed, because that is the string the correction
        // looks for on its pages -- an English part says "1 of 2" and a Thai one "1 จาก 2", and a
        // merge that assumed one of them would silently leave the other half counting for itself.
        List<PdfMergeService.Part> parts = new ArrayList<>(languages.size());
        for (Locale language : languages) {
            CountryProfile profile = countryCatalog.profile(
                    countryCatalog.resolve(null, null, language), language);
            byte[] part = pdfExportService.renderTemplate(PDF_TEMPLATE,
                    model(profile, profile.locale(), now), profile.locale());
            parts.add(new PdfMergeService.Part(part,
                    messageSource.getMessage("pdf.page.middle", null, profile.locale())));
        }

        // The MERGED document reads in the request's own language. A file assembled from an English
        // half and a Thai half has no intrinsic one, so this is a decision rather than a lookup:
        // the caller asked in a language, and the counter answers in it.
        String separator = messageSource.getMessage("pdf.page.middle", null, locale);
        BaseFont font = pdfExportService.stampingFont(
                messageSource.getMessage("pdf.font.family", null, locale));
        byte[] body = pdfMergeService.mergeParts(parts,
                PdfMergeService.PageNumbering.statement(separator, font));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                "creed-approval-status-merged-" + now.format(ReportController.FILE_TS) + ".pdf");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @RequestMapping(value = "/approval-status/preview/pdf",
            method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewPdf(Locale locale, HttpServletRequest request) {
        CountryProfile profile = countryCatalog.profileFor(locale);
        Map<String, Object> variables = model(profile, locale, LocalDateTime.now());
        // Only the preview sets this; its presence is what switches the shim on in the template.
        variables.put("previewCss", request.getContextPath() + PREVIEW_STYLESHEET);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .body(pdfExportService.renderTemplateHtml(PDF_TEMPLATE, variables, locale));
    }

    /**
     * One model for both endpoints — the two must not be able to disagree about the document.
     *
     * <p>{@code locale} is the one the template will be rendered in, and it is passed to
     * {@link CountryStyles#pdf} for exactly that reason: the stylesheet's locale layer (whether
     * this script's captions go bold) and the message bundle the faces come from then cannot
     * resolve to different languages.
     */
    /**
     * Which language each part is rendered in: the {@code langs} list if one was given, otherwise
     * {@code copies} copies of the request's own.
     *
     * <p>A tag with no region is resolved the way the rest of the module resolves one — to the
     * country whose default language it is, so {@code langs=en,th} really does produce an English
     * part and a <b>Thai</b> one rather than two English ones (the GLOBAL edition renders en/zh
     * only).
     */
    private List<Locale> languagesFor(Locale locale, int copies, List<String> langs) {
        if (langs == null || langs.isEmpty()) {
            if (copies < MIN_COPIES || copies > MAX_COPIES) {
                // 400, like every other bad input here -- a bare IllegalArgumentException is a 500.
                throw new InvalidMergeRequestException("copies must be between " + MIN_COPIES
                        + " and " + MAX_COPIES + ", not " + copies);
            }
            return Collections.nCopies(copies, locale);
        }
        if (langs.size() < MIN_COPIES || langs.size() > MAX_COPIES) {
            throw new InvalidMergeRequestException("langs must name between " + MIN_COPIES + " and "
                    + MAX_COPIES + " languages, not " + langs.size());
        }
        List<Locale> languages = new ArrayList<>(langs.size());
        for (String tag : langs) {
            Locale language = Locale.forLanguageTag(tag.trim());
            if (language.getLanguage().isEmpty()) {
                throw new InvalidMergeRequestException("'" + tag + "' is not a language tag");
            }
            languages.add(language);
        }
        return languages;
    }

    private Map<String, Object> model(CountryProfile profile, Locale locale, LocalDateTime now) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("pdfCss", countryStyles.pdf(profile.country(), locale));
        variables.put("report", sampleReport(objectMapper));
        // The footnote prints the two halves either side of a divider, so they arrive formatted and
        // separate -- the country's single datePattern covers both at once and cannot be split.
        variables.put("exportDate", CountryFormatter.date(now, profile));
        variables.put("exportTime", CountryFormatter.time(now, profile));
        return variables;
    }

    /**
     * The hard-coded sample, parsed. Public so every test that needs this document renders
     * <b>this</b> payload rather than a copy of it that could drift from what the endpoint actually
     * serves — the layout test in this package, and the merge test in {@code ..service}.
     */
    public static ApprovalStatusReport sampleReport(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(SAMPLE_JSON, ApprovalStatusReport.class);
        }
        catch (JsonProcessingException ex) {
            // The JSON is a constant in this file: if it does not parse, the build is broken, not
            // the request. Fail loudly rather than serving an empty document.
            throw new IllegalStateException("The hard-coded approval-status sample is not valid JSON", ex);
        }
    }
}
