package com.creed.report.controller;

import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.service.AssetService;
import com.creed.report.service.PdfExportService;
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
 * <p>Excel is not here: it goes through the existing {@code /export/excel?type=dynamic} strategy,
 * which reads the same three parameters off the request.
 */
@Controller
public class DynamicReportController {

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

    /** PDF of the same table, through the paged-media template. */
    @RequestMapping(value = "/dynamic/export/pdf", method = { RequestMethod.GET, RequestMethod.POST },
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestParam Map<String, String> parameters, Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);
        DynamicTable table = tableService.build(DynamicTableRequest.from(parameters), profile, locale);

        Map<String, Object> variables = new HashMap<>();
        variables.put("profile", profile);
        variables.put("countryPdfCss", countryStyles.pdf(profile.country()));
        variables.put("table", table);
        variables.put("total", CountryFormatter.number(table.size(), profile));
        variables.put("generatedAt", CountryFormatter.timestamp(now, profile));

        byte[] body = pdfExportService.renderTemplate("dynamic-report-export-pdf", variables, locale);
        return download(body, MediaType.APPLICATION_PDF, filename(profile, now, "pdf"));
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
