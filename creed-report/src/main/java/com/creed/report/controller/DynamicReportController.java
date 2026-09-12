package com.creed.report.controller;

import com.creed.report.dynamic.DynamicReportTemplate;
import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.service.AssetService;
import com.creed.report.service.PdfExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A report whose table is described by the caller: {@code headers} names the columns, {@code data}
 * carries the rows as JSON. Same country/language machinery as the server report — the country
 * decides the row formats, the style block and the content fragment, the language the labels — so
 * a caller-defined table is localized without the caller knowing anything about locales.
 *
 * <p>Every endpoint answers <b>GET and POST</b>. GET keeps a whole report shareable as one link;
 * POST exists because {@code data} is caller-sized and a real payload outgrows a query string —
 * which is also why the page's export buttons are forms that re-post the current definition rather
 * than links that would have to re-encode it.
 *
 * <p><b>Multiple layouts.</b> {@code template=} picks the paper the PDF is printed on — see
 * {@link DynamicReportTemplate}. It rides in with the rest of the definition, so the page's export
 * buttons re-post it like everything else, and an unknown code is 400 rather than a silent
 * fallback. The offline HTML export deliberately ignores it: it is the live page's twin and has no
 * pages to lay out.
 *
 * <p><b>Debugging the PDF.</b> {@code /dynamic/preview/pdf} serves the PDF template's own XHTML
 * as {@code text/html}, so the paged-media markup can be opened in devtools instead of guessed at
 * from the finished bytes. Same endpoint parameters — {@code template=} included — same model,
 * same string.
 *
 * <p>Excel is not here: it goes through the existing {@code /export/excel?type=dynamic} strategy,
 * which reads the same three parameters off the request.
 */
@Controller
public class DynamicReportController {

    /** Context-path-relative; the preview resolves it against the request. */
    private static final String PREVIEW_STYLESHEET = "/css/report-pdf-preview.css";

    private final DynamicTableService tableService;
    private final AssetService assetService;
    private final TemplateEngine templateEngine;
    private final PdfExportService pdfExportService;
    private final CountryCatalog countryCatalog;
    private final CountryStyles countryStyles;

    public DynamicReportController(DynamicTableService tableService,
                                   AssetService assetService,
                                   TemplateEngine templateEngine,
                                   PdfExportService pdfExportService,
                                   CountryCatalog countryCatalog,
                                   CountryStyles countryStyles) {
        this.tableService = tableService;
        this.assetService = assetService;
        this.templateEngine = templateEngine;
        this.pdfExportService = pdfExportService;
        this.countryCatalog = countryCatalog;
        this.countryStyles = countryStyles;
    }

    /**
     * The page. With no {@code headers} it renders just the definition form, so the endpoint is
     * usable without first constructing a URL by hand.
     */
    @RequestMapping(value = "/dynamic", method = { RequestMethod.GET, RequestMethod.POST })
    public String dynamic(@RequestParam Map<String, String> parameters, Model model, Locale locale) {
        CountryProfile profile = countryCatalog.profileFor(locale);
        DynamicTableRequest request = DynamicTableRequest.from(parameters);

        model.addAttribute("profile", profile);
        model.addAttribute("countries", countryCatalog.countries());
        model.addAttribute("generatedAt", CountryFormatter.timestamp(LocalDateTime.now(), profile));
        model.addAttribute("definition", request);
        // Model-driven like the country switcher and the Excel dropdown: a new layout shows up in
        // the picker by existing, with no template edit. Resolving here also means a bad
        // template= is refused on the page, where the caller can see it, not only on the download.
        model.addAttribute("templates", DynamicReportTemplate.values());
        model.addAttribute("template", request.reportTemplate());

        if (!request.isBlank()) {
            DynamicTable table = tableService.build(request, profile, locale);
            model.addAttribute("table", table);
            model.addAttribute("total", CountryFormatter.number(table.size(), profile));
        }
        return "dynamic-report";
    }

    /** Self-contained offline HTML of the same table. */
    @RequestMapping(value = "/dynamic/export", method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> parameters, Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);
        DynamicTable table = tableService.build(DynamicTableRequest.from(parameters), profile, locale);

        Context ctx = new Context(locale);
        ctx.setVariable("profile", profile);
        ctx.setVariable("table", table);
        ctx.setVariable("total", CountryFormatter.number(table.size(), profile));
        ctx.setVariable("generatedAt", CountryFormatter.timestamp(now, profile));
        ctx.setVariable("bootstrapCss", assetService.bootstrapCss());
        ctx.setVariable("bootstrapJs", assetService.bootstrapJs());
        ctx.setVariable("reportCss", assetService.reportCss());
        ctx.setVariable("countryCss", countryStyles.browser(profile.country()));

        byte[] body = templateEngine.process("dynamic-report-export", ctx).getBytes(StandardCharsets.UTF_8);
        return download(body, MediaType.parseMediaType("text/html; charset=UTF-8"),
                filename(profile, now, "html"));
    }

    /** PDF of the same table, through the paged-media template {@code template=} asked for. */
    @RequestMapping(value = "/dynamic/export/pdf", method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestParam Map<String, String> parameters, Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);
        DynamicReportTemplate template = DynamicReportTemplate.from(parameters);

        byte[] body = pdfExportService.renderTemplate(template.pdfTemplate(),
                pdfModel(parameters, profile, locale, now), locale);
        return download(body, MediaType.APPLICATION_PDF, filename(profile, now, "pdf"));
    }

    /**
     * The PDF template rendered <b>to the browser</b> instead of to bytes: same URL parameters,
     * same model, same string — {@code text/html} inline, so devtools can inspect the boxes,
     * toggle the rules and live-edit the CSS that the PDF is actually made of.
     *
     * <p>It is the same string by construction, not by convention:
     * {@link PdfExportService#renderTemplateHtml} is the first half of the export above, stopped
     * before Flying Saucer, logo data URIs and all. A preview that re-built the model, or rendered
     * a browser twin of the template, would be a lookalike free to drift — and a lookalike is
     * worthless for the one question this endpoint exists to answer: why does the PDF look like
     * that?
     *
     * <p>What the browser adds is {@code ${previewCss}} — {@code report-pdf-preview.css}, the one
     * stylesheet the renderer never sees. It sizes {@code <body>} to the {@code @page} box so
     * column widths and wrap points match the PDF's to the millimetre, and {@code @font-face}s the
     * very TTFs the PDF embeds (served by {@link com.creed.report.config.PdfPreviewConfig}). It is
     * passed as a resolved URL rather than left to an {@code @{...}} in the template, because the
     * PDF path renders that template on a plain non-web context where a link expression throws.
     *
     * <p>Limits worth knowing before trusting a pixel: on screen the sheet just grows — the running
     * header/footer repeat per page only in the PDF — and no browser implements the {@code @page}
     * margin boxes, so the page counter is missing. <b>Cmd+P is the paginated check</b>: Chrome
     * honours the document's own {@code @page} size/margins and repeats the {@code .page-frame}
     * {@code thead}/{@code tfoot} per page, just as {@code -fs-table-paginate} does.
     */
    @RequestMapping(value = "/dynamic/preview/pdf", method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewPdf(@RequestParam Map<String, String> parameters,
                                             Locale locale, HttpServletRequest request) {
        CountryProfile profile = countryCatalog.profileFor(locale);
        Map<String, Object> variables = pdfModel(parameters, profile, locale, LocalDateTime.now());
        // Only the preview sets this; its presence is what switches the shim on in the template.
        variables.put("previewCss", request.getContextPath() + PREVIEW_STYLESHEET);

        String html = pdfExportService.renderTemplateHtml(
                DynamicReportTemplate.from(parameters).pdfTemplate(), variables, locale);
        // Inline, not an attachment: the point is to look at it in a tab.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .body(html);
    }

    /**
     * The PDF template's model, built once for both the export and its preview — the two must not
     * be able to disagree about what they are rendering.
     */
    private Map<String, Object> pdfModel(Map<String, String> parameters, CountryProfile profile,
                                         Locale locale, LocalDateTime now) {
        DynamicTable table = tableService.build(DynamicTableRequest.from(parameters), profile, locale);

        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("pdfCss", countryStyles.pdf(profile.country()));
        variables.put("table", table);
        variables.put("total", CountryFormatter.number(table.size(), profile));
        variables.put("generatedAt", CountryFormatter.timestamp(now, profile));
        // The statement chrome prints the two halves apart ("Date of Export: ... | Time of
        // Export: ..."); built here, not in that template, so both layouts date a report from the
        // same instant and neither can format it its own way.
        variables.put("exportDate", CountryFormatter.date(now, profile));
        variables.put("exportTime", CountryFormatter.time(now, profile));
        return variables;
    }

    private static String filename(CountryProfile profile, LocalDateTime now, String extension) {
        // ASCII and sortable, like the server report's — never the country's own date format.
        return "creed-dynamic-report-" + profile.code() + "-"
                + now.format(ReportController.FILE_TS) + "." + extension;
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
