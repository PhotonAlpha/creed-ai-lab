package com.creed.report.controller;

import com.creed.report.export.ExcelExportRequest;
import com.creed.report.export.ExcelExportService;
import com.creed.report.export.ReportType;
import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryStyles;
import com.creed.report.model.ServerInfo;
import com.creed.report.service.AssetService;
import com.creed.report.service.PdfExportService;
import com.creed.report.service.ServerInfoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The report page and its exports, rendered for one <b>country edition</b> in one language.
 *
 * <p>Both axes arrive folded into the {@code Locale} parameter by
 * {@link com.creed.report.config.CountryLocaleResolver}, so every endpoint here recovers the
 * country with a single {@link CountryCatalog#profileFor(Locale)} call and hands the resulting
 * {@link CountryProfile} to the view, the PDF renderer and the Excel strategies alike.
 */
@Controller
public class ReportController {

    /**
     * Filename timestamp. Deliberately <b>not</b> country-formatted: download names must stay
     * ASCII and sortable, whatever calendar the report itself is dated in.
     */
    public static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInfoService serverInfoService;
    private final AssetService assetService;
    private final TemplateEngine templateEngine;
    private final PdfExportService pdfExportService;
    private final ExcelExportService excelExportService;
    private final CountryCatalog countryCatalog;
    private final CountryStyles countryStyles;

    public ReportController(ServerInfoService serverInfoService,
                            AssetService assetService,
                            TemplateEngine templateEngine,
                            PdfExportService pdfExportService,
                            ExcelExportService excelExportService,
                            CountryCatalog countryCatalog,
                            CountryStyles countryStyles) {
        this.serverInfoService = serverInfoService;
        this.assetService = assetService;
        this.templateEngine = templateEngine;
        this.pdfExportService = pdfExportService;
        this.excelExportService = excelExportService;
        this.countryCatalog = countryCatalog;
        this.countryStyles = countryStyles;
    }

    @GetMapping({ "/report"})
    public String report(Model model, Locale locale) {
        CountryProfile profile = countryCatalog.profileFor(locale);
        List<ServerInfo> servers = serverInfoService.listServers(profile.country());
        model.addAttribute("profile", profile);
        // Drives the country switcher; like reportTypes below, nothing is hard-coded in the template.
        model.addAttribute("countries", countryCatalog.countries());
        model.addAttribute("servers", servers);
        model.addAttribute("total", CountryFormatter.number(servers.size(), profile));
        model.addAttribute("generatedAt", CountryFormatter.timestamp(LocalDateTime.now(), profile));
        // Drives the Excel dropdown: whatever export strategies are registered, nothing hard-coded
        // in the template. Linkable ones only — a type needing a posted definition (dynamic) has no
        // link that could work from here.
        model.addAttribute("reportTypes", excelExportService.linkableTypes());
        return "report";
    }

    @GetMapping(value = "/export", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> export(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);
        List<ServerInfo> servers = serverInfoService.listServers(profile.country());

        Context ctx = new Context(locale);
        ctx.setVariable("profile", profile);
        ctx.setVariable("servers", servers);
        ctx.setVariable("total", CountryFormatter.number(servers.size(), profile));
        ctx.setVariable("generatedAt", CountryFormatter.timestamp(now, profile));
        ctx.setVariable("bootstrapCss", assetService.bootstrapCss());
        ctx.setVariable("bootstrapJs", assetService.bootstrapJs());
        // The offline file cannot link a stylesheet, so the same two sheets the live page links
        // are inlined here, shared first and the country's own last.
        ctx.setVariable("reportCss", assetService.reportCss());
        ctx.setVariable("countryCss", countryStyles.browser(profile.country()));

        String html = templateEngine.process("report-export", ctx);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String filename = "creed-server-report-" + profile.code() + "-" + now.format(FILE_TS) + ".html";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * PDF twin of {@link #export(Locale)}: renders {@code report-export-pdf.html} — the PDF adaptation of
     * {@code report-export.html}, same visual language rebuilt in paged-media CSS 2.1 (openpdf-html
     * cannot render the Bootstrap template). See {@link PdfExportService} for the template
     * constraints and CJK font configuration.
     *
     * <p>Localized on both axes: the locale comes from
     * {@link com.creed.report.config.LocaleConfig} — {@code ?lang=} and {@code ?country=} (each kept
     * in a cookie) or {@code Accept-Language}. Strings and the Noto font stack follow the language
     * via the {@code messages*.properties} bundles; the row scope, the date format and the country
     * style block follow the country.
     */
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(Locale locale) {
        LocalDateTime now = LocalDateTime.now();
        CountryProfile profile = countryCatalog.profileFor(locale);
        List<ServerInfo> servers = serverInfoService.listServers(profile.country());

        byte[] body = pdfExportService.renderTemplate("report-export-pdf", Map.of(
                "profile", profile,
                "countryPdfCss", countryStyles.pdf(profile.country()),
                "servers", servers,
                "total", CountryFormatter.number(servers.size(), profile),
                "generatedAt", CountryFormatter.timestamp(now, profile)), locale);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                "creed-server-report-" + profile.code() + "-" + now.format(FILE_TS) + ".pdf");
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Excel (.xlsx) export, dispatched by report type: {@code type} selects the
     * {@link com.creed.report.export.ExcelReportExporter} strategy
     * ({@code server} → the table of this page, {@code environment} → the Environment Inspector
     * snapshot), and every other query parameter is handed to that strategy — which is how the
     * environment report picks up {@code spring.profiles.active} &amp; friends from the URL.
     *
     * <p>Localized like the other exports (headers, sheet names, country scope and date format come
     * from the same locale and country); an unsupported {@code type} answers 400 via
     * {@link com.creed.report.export.UnknownReportTypeException}.
     *
     * <p>POST is accepted alongside GET for {@code type=dynamic}, whose caller-supplied
     * {@code headers}/{@code data} outgrow a query string; form fields land in {@code parameters}
     * exactly as query parameters do.
     */
    @RequestMapping(value = "/export/excel", method = { RequestMethod.GET, RequestMethod.POST },
            produces = ExcelExportService.CONTENT_TYPE)
    public ResponseEntity<byte[]> exportExcel(@RequestParam(name = "type", defaultValue = "server") String type,
                                              @RequestParam Map<String, String> parameters,
                                              Locale locale) {
        ExcelExportRequest request = new ExcelExportRequest(ReportType.of(type), locale,
                countryCatalog.profileFor(locale), parameters, LocalDateTime.now());
        byte[] body = excelExportService.export(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(ExcelExportService.CONTENT_TYPE));
        headers.setContentDispositionFormData("attachment", excelExportService.filename(request));
        headers.setContentLength(body.length);

        return ResponseEntity.ok().headers(headers).body(body);
    }
}
