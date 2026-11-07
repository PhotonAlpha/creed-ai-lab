/**
 * Copyright the original author or authors.
 *
 * @author: EthanCao
 */

package com.creed.parent.controller.pdf;


import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.creed.parent.controller.pdf.dto.BootstrapPath;
import com.creed.parent.controller.pdf.dto.ContextInformation;
import com.creed.parent.controller.vo.*;
import com.creed.parent.dal.model.DiffDisplay;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.service.DiffService;
import com.creed.parent.service.PropertiesService;
import com.creed.parent.service.ReportGenerationService;
import com.creed.parent.service.report.AssetService;
import com.creed.parent.service.report.ReportMetadataInfo;
import com.creed.parent.service.report.impl.HTMLReportGeneratorImpl;
import com.creed.parent.service.report.impl.PDFReportGeneratorImpl;
import com.creed.parent.utils.PdfUtils;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
@Slf4j
public class PdfController {
    @Resource
    private TemplateEngine templateEngine;
    @Resource
    private DiffService diffService;
    @Resource
    private ReportGenerationService reportGenerationService;
    @Resource(name = "pdfReportGenerator")
    PDFReportGeneratorImpl reportGenerator;
    @Resource
    @Lazy
    private PropertiesService propertiesService;

    public static String getRequestData() {
        return """
                {
                  "routes": [
                    {
                      "releaseName": "Release_SG_R31.1_218",
                      "projectName": "idb-fdr-fraudops-admin-service",
                      "countryCode": "SG",
                      "app": "IDBSG_FDR_OPS",
                      "environment": "BAU1",
                      "instance": "QUAE49",
                      "node": "",
                      "zone": "adc"
                    },
                    {
                      "releaseName": "Release_SG_R31.1_218",
                      "projectName": "idb-fdr-fraudops-admin-service",
                      "countryCode": "SG",
                      "app": "IDBSG_FDR_OPS",
                      "environment": "PDN1",
                      "instance": "PDN0E121",
                      "node": "",
                      "zone": "adc"
                    }
                  ],
                  "baseRelease": "",
                  "compareAll": true
                }
                """;
        /* return """
                {
                  "routes": [
                          {
                            "releaseName": "Release_SG_R31.1_7218",
                            "projectName": "idbconfigs",
                            "countryCode": "SG",
                            "app": "CCS",
                            "environment": "UAT61",
                            "instance": "QUA177",
                            "node": "",
                            "zone": "bau,gst-bau,gst-qr,gst-uat3,qr"
                          },
                          {
                            "releaseName": "Release_SG_R31.1_7218",
                            "projectName": "idbconfigs",
                            "countryCode": "SG",
                            "app": "CCS",
                            "environment": "PDN1",
                            "instance": "PDN0051",
                            "node": "",
                            "zone": "adc,nxg"
                          }
                        ],
                  "compareAll": true
                }
                """; */
    }

    @Deprecated(forRemoval = true)
    @GetMapping("/show/{tmpName}")
    public String showHtml(@PathVariable("tmpName") String tmpName, Model mode, HttpServletRequest request) throws IOException {
        String baseUrl = getCurrentBaseUrl();
        log.info("baseUrl:{}", baseUrl);
        mode.addAttribute("baseUrl", baseUrl);

        // Locale locale = new Locale("zh", "TC");
        // 注意区别大小写， 在linux中 messages_zh_CN.properties country 不能是小写，否则无法识别。
        // Locale locale = new Locale("zh", "CN");
        Locale locale = new Locale("en");
        new SessionLocaleResolver().setLocale(request, null, locale);

        if (StringUtils.equalsIgnoreCase("cover_index", tmpName)) {
            mode.addAttribute("fragmentTemplate", "fragments/cover_fragment");
            mode.addAttribute("fragmentName", "cover");
        } else {
            mode.addAttribute("fragmentTemplate", "fragments/diff_fragment");
            mode.addAttribute("fragmentName", "pdf");
        }
        ContextInformation contextInformation = new ContextInformation();
        contextInformation.setTitle("Comparison Report");
        contextInformation.setFooter("Power By Comparator Tool");
        mode.addAttribute("contextInformation", contextInformation);
        // InputStream is = new FileSystemResource("/Users/venojk/Desktop/workspace/sourcecode/ccrg-tool/.idea/httpRequests/2025-06-26T192147.200.json").getInputStream();

        // R<PropertyReviewOverviewVo> propertyValue = JacksonUtils.objectMapper().readValue(is, new TypeReference<>() {
        // });
        // List<PropertyReviewVo> propertyValueData = propertyValue.getData().getNonMatchingProperties();

        // PropertyReviewVo leftPropertyReview = propertyValueData.get(0);
        // PropertyReviewVo rightPropertyReview = propertyValueData.get(1);
        // ReportDetails leftReport = leftPropertyReview.getReportDetails();
        // ReportDetails rightReport = rightPropertyReview.getReportDetails();
        // List<DiffDisplay> displayList = diffService.diffDisplayList(leftPropertyReview, rightPropertyReview);
        // log.debug("displayList:{}", JacksonUtils.toJsonString(displayList));
        // mode.addAttribute("displayList", displayList);
        // mode.addAttribute("leftReport", leftPropertyReview);
        // mode.addAttribute("rightReport", rightPropertyReview);
        // generatePdf(locale, displayList, leftPropertyReview, rightPropertyReview);
        return tmpName;
    }

    @Resource
    private ThreadPoolTaskExecutor taskExecutor;


    @GetMapping("/display/{tmpName}")
    public String displayHtml(@PathVariable("tmpName") String tmpName, Model mode, HttpServletRequest request) throws IOException {
        String baseUrl = getCurrentBaseUrl();
        log.info("baseUrl:{}", baseUrl);
        Map<String, Object> params = new HashMap<>();

        params.put("baseUrl", baseUrl);

        // Locale locale = new Locale("zh", "TC");
        // 注意区别大小写， 在linux中 messages_zh_CN.properties country 不能是小写，否则无法识别。
        // Locale locale = new Locale("zh", "CN");
        Locale locale = new Locale("en");
        new SessionLocaleResolver().setLocale(request, null, locale);
        ContextInformation contextInformation = new ContextInformation();
        contextInformation.setFooter("Power By Comparator Tool");
        contextInformation.setTitle("Comparison Report");

        params.put("contextInformation", contextInformation);
        // InputStream is = new FileSystemResource("/Users/venojk/Desktop/workspace/sourcecode/ccrg-tool/.idea/httpRequests/2025-06-27T173152.200.json").getInputStream();
        RouteFormVo routeFormVo = JacksonUtils.parseObject(getRequestData(), RouteFormVo.class);

        List<RouteInfoVo> routes = Optional.of(routeFormVo).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
        ReportMetadataInfo metadataInfo = new ReportMetadataInfo(UUID.randomUUID().toString(), routeFormVo);

        List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(routes);
        PropertyReviewOverviewVo propertyReviewOverview = reportGenerator.generateComparison(environmentConfiguration, metadataInfo);
        params.put("noneMatchingFragmentTemplate", "fragments/non-matching-html-fragment");
        params.put("noneMatchingFragmentName", "noneMatchingHtml");

        params.put("matchingFragmentTemplate", "fragments/matching-html-fragment");
        params.put("matchingFragmentName", "matchingHtml");


        List<PropertyReviewVo> nonMatchingProperties = propertyReviewOverview.getNonMatchingProperties();
        PropertyReviewVo leftPropertyReviewN = nonMatchingProperties.get(0);
        PropertyReviewVo rightPropertyReviewN = nonMatchingProperties.get(1);
        List<DiffDisplay> diffDisplaysN = diffService.diffDisplayList(leftPropertyReviewN, rightPropertyReviewN);
        PropertiesOverviewVo nonMatchingRecord = new PropertiesOverviewVo(leftPropertyReviewN, rightPropertyReviewN, diffDisplaysN);

        List<PropertyReviewVo> matchingProperties = propertyReviewOverview.getMatchingProperties();
        PropertyReviewVo leftPropertyReview = matchingProperties.get(0);
        PropertyReviewVo rightPropertyReview = matchingProperties.get(1);
        List<DiffDisplay> diffDisplays = diffService.nonDiffDisplayList(leftPropertyReview, rightPropertyReview);
        PropertiesOverviewVo matchingRecord = new PropertiesOverviewVo(leftPropertyReview, rightPropertyReview, diffDisplays);

        params.put("reportPropertiesOverview", new ReportPropertiesOverviewVo(nonMatchingRecord, matchingRecord));

        /* List<PropertyReviewVo> propertyValueData;

        if (org.apache.commons.lang3.StringUtils.equals(fragment, "matching")) {
            params.put("fragmentTemplate", "fragments/matching_fragment");
            params.put("fragmentName", "pdf");
            contextInformation.setTitle("Comparison Report - Matching");
            propertyValueData = propertyValue.getMatchingProperties();
        } else {
            params.put("fragmentTemplate", "fragments/non_matching_fragment");
            params.put("fragmentName", "pdf");
            contextInformation.setTitle("Comparison Report - Non Matching");
            propertyValueData = propertyValue.getNonMatchingProperties();
        }

        PropertyReviewVo leftPropertyReviewN = propertyValueData.get(0);
        PropertyReviewVo rightPropertyReviewN = propertyValueData.get(1);
        List<DiffDisplay> displayList;
        if (org.apache.commons.lang3.StringUtils.equals(fragment, "matching")) {
            displayList = diffService.nonDiffDisplayList(leftPropertyReviewN, rightPropertyReviewN);

        } else {
            displayList = diffService.diffDisplayList(leftPropertyReviewN, rightPropertyReviewN);
        }
        log.info("see:{}", JacksonUtils.toJsonString(displayList));
        params.put("displayList", displayList);
        params.put("leftReport", leftPropertyReviewN);
        params.put("rightReport", rightPropertyReviewN); */
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            mode.addAttribute(entry.getKey(), entry.getValue());
        }
        RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);
        // CompletableFuture.runAsync(() ->
        //         generatePdf(locale, params)
        // , taskExecutor);
        generateHtml(locale, params);
        return tmpName;
    }

    @Resource(name = "htmlReportGenerator")
    private HTMLReportGeneratorImpl htmlReportGenerator;

    /**
     * The HTML comparison report (Non Matching / Matching / Overall Comparison) rendered straight into
     * the browser — the same model and template as the downloaded file, nothing written to disk.
     * GET compares the built-in {@link #getRequestData()} sample; POST takes a {@code RouteFormVo}
     * (two routes) as JSON.
     */
    @RequestMapping(value = "/report/html-preview", method = {RequestMethod.GET, RequestMethod.POST})
    public String previewHtmlReport(@RequestBody(required = false) RouteFormVo routeForm, Model model, HttpServletRequest request) {
        RouteFormVo routeFormVo = Optional.ofNullable(routeForm)
                .orElseGet(() -> JacksonUtils.parseObject(getRequestData(), RouteFormVo.class));
        List<RouteInfoVo> routes = Optional.ofNullable(routeFormVo.getRoutes()).orElse(Collections.emptyList());
        if (routes.size() < 2) {
            throw new ServiceException(400, "Two routes are required for a comparison, got " + routes.size());
        }
        new SessionLocaleResolver().setLocale(request, null, Locale.ENGLISH);

        List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(routes);
        ReportMetadataInfo metadataInfo = new ReportMetadataInfo(UUID.randomUUID().toString(), routeFormVo);
        model.addAllAttributes(htmlReportGenerator.buildReportModel(environmentConfiguration, metadataInfo));
        return HTMLReportGeneratorImpl.REPORT_TEMPLATE;
    }

    public static String getCurrentBaseUrl() {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest req = sra.getRequest();
        return req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort() + req.getContextPath();
    }

    public static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    @SneakyThrows
    private void generatePdf(Locale locale, List<DiffDisplay> displayList, PropertyReviewVo leftReport, PropertyReviewVo rightReport) {
        Context context = new Context();
        context.setLocale(locale);

        ContextInformation contextInformation = new ContextInformation();
        contextInformation.setTitle("Comparison Report");
        contextInformation.setFooter("Power By Comparator Tool");
        context.setVariable("contextInformation", contextInformation);

        context.setVariable("displayList", displayList);
        context.setVariable("leftReport", leftReport);
        context.setVariable("rightReport", rightReport);

        try (ByteArrayOutputStream os1 = generatePdf(context, "pdf-index", "fragments/diff_fragment", "pdf", locale);
            ) {
            ByteArrayOutputStream os = merge(Arrays.asList(
                    new PdfDocument(
                            new PdfReader(new ByteArrayInputStream(os1.toByteArray())))
            ), "pwd", false);

            Files.write(Paths.get("/Users/venojk/Downloads", "thymeleaf_test%s.pdf".formatted(LocalDateTime.now().format(DTF))), os.toByteArray(), StandardOpenOption.CREATE);
        }
       /*  try (ByteArrayOutputStream os1 = generatePdf(context, "pdf-index", "fragments/diff_fragment", "pdf", locale);
             ByteArrayOutputStream os2 = generateCoverPage("Cover Header", "cover_index", "fragments/cover_fragment", "cover", locale)) {
            ByteArrayOutputStream os = merge(Arrays.asList(
                    new PdfDocument(
                            new PdfReader(new ByteArrayInputStream(os2.toByteArray()))),
                    new PdfDocument(
                            new PdfReader(new ByteArrayInputStream(os1.toByteArray())))
            ), "pwd", false);
            Files.write(Paths.get("./logs", "thymeleaf_test.pdf"), os.toByteArray(), StandardOpenOption.CREATE);
        } */

    }
    @SneakyThrows
    private void generatePdf(Locale locale, Map<String, Object> params) {
        Context context = new Context();
        context.setLocale(locale);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }

        try (ByteArrayOutputStream os1 = generatePdf(context, "pdf-index", (String) params.get("fragmentTemplate"), (String) params.get("fragmentName"), locale);
            ) {
            ByteArrayOutputStream os = merge(Arrays.asList(
                    new PdfDocument(
                            new PdfReader(new ByteArrayInputStream(os1.toByteArray())))
            ), "pwd", false);

            Files.write(Paths.get("/Users/venojk/Downloads", "thymeleaf_test%s.pdf".formatted(LocalDateTime.now().format(DTF))), os.toByteArray(), StandardOpenOption.CREATE);
        }
    }

    @Resource
    AssetService assetService;


    @SneakyThrows
    private void generateHtml(Locale locale, Map<String, Object> params) {
        Context context = new Context();
        context.setLocale(locale);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }

        context.setVariable("bootstrapCss", assetService.getBootstrapCss());
        context.setVariable("bootstrapIconsCss", assetService.getBootstrapIconsCss());
        context.setVariable("styleCss", assetService.getStyleCss());
        context.setVariable("bootstrapJs", assetService.getBootstrapJs());
        context.setVariable("favIcon", assetService.getFavicon());
        context.setVariable("logo", assetService.getLogoImg());

        String html = templateEngine.process("report-html-export-index", context);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        /* Compiler compiler = new Compiler();
        CompilerOptions compilerOptions = new CompilerOptions();
        compilerOptions.setOutputCharset(StandardCharsets.UTF_8);
        CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(compilerOptions);
        String fineName = "thymeleaf_test%s.html".formatted(LocalDateTime.now().format(DTF));
        SourceFile sourceFile = SourceFile.fromCode(fineName, html);

        Result result = compiler.compile(List.of(), List.of(sourceFile), compilerOptions);
        if (!result.success) {
            throw new RuntimeException(result.errors.toString());
        }
        byte[] body = compiler.toSource().getBytes(StandardCharsets.UTF_8); */
        Files.write(Paths.get("/Users/gmtqic/Downloads", "thymeleaf_test%s.html".formatted(LocalDateTime.now().format(DTF))), body, StandardOpenOption.CREATE);

    }
    protected List<String> fontList(Locale locale) {
        if (StringUtils.equals(locale.getLanguage(), "zh")) {
            return Arrays.asList(
                    "static/pdf/fonts/NotoSans/NotoSansSC-Regular.ttf",
                    "static/pdf/fonts/NotoSans/NotoSansSC-Bold.ttf",
                    "static/pdf/fonts/NotoSans/NotoSansSC-Light.ttf"
            );
        }
        return Arrays.asList(
                "static/pdf/fonts/OpenSans/OpenSans-Regular.ttf",
                "static/pdf/fonts/OpenSans/OpenSans-Bold.ttf",
                "static/pdf/fonts/OpenSans/OpenSans-Light.ttf"
        );
    }


    public ByteArrayOutputStream generatePdf(Context context, String template, String fragmentTemplate, String fragmentName, Locale locale) throws IOException {
        return reportGenerationService.constructPdfDocumentOS(context, template, fragmentTemplate, fragmentName, locale);
        /* String customCssContentPath;
        if (StringUtils.equals(locale.getLanguage(), "zh")) {
            customCssContentPath = new ClassPathResource("static/pdf/css/custom.SC.css").getFile().getAbsolutePath();
        } else {
            customCssContentPath = new ClassPathResource("static/pdf/css/custom.css").getFile().getAbsolutePath();
        }
        String cssContentPath = new ClassPathResource("static/pdf/css/bootstrap.min.css").getFile().getAbsolutePath();
        String messagePath = new ClassPathResource("static/pdf/img/message.svg").getFile().getAbsolutePath();
        String moneyPath = new ClassPathResource("static/pdf/img/money.svg").getFile().getAbsolutePath();
        String peoplesPath = new ClassPathResource("static/pdf/img/peoples.svg").getFile().getAbsolutePath();
        String profilePath = new ClassPathResource("static/pdf/img/logo-infinity.svg").getFile().getAbsolutePath();
        String iconCheckedPath = new ClassPathResource("static/pdf/img/IconChecked.png").getFile().getAbsolutePath();
        String iconUncheckedPath = new ClassPathResource("static/pdf/img/IconUnchecked.png").getFile().getAbsolutePath();
        String shoppingPath = new ClassPathResource("static/pdf/img/shopping.svg").getFile().getAbsolutePath();
        BootstrapPath bootstrapPath = new BootstrapPath();
        bootstrapPath.setCustomCssContent(customCssContentPath);
        bootstrapPath.setCssContent(cssContentPath);
        bootstrapPath.setMessageImg(messagePath);
        bootstrapPath.setMoneyImg(moneyPath);
        bootstrapPath.setPeoplesImg(peoplesPath);
        bootstrapPath.setProfileImg(profilePath);
        bootstrapPath.setShoppingImg(shoppingPath);
        bootstrapPath.setIconCheckedImg(iconCheckedPath);
        bootstrapPath.setIconUncheckedImg(iconUncheckedPath);

        context.setVariable("bootstrapPath", bootstrapPath);
        context.setVariable("fragmentTemplate", fragmentTemplate);
        context.setVariable("fragmentName", fragmentName);

        String htmlContent = templateEngine.process(template, context);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document;
        try (PdfWriter writer = new PdfWriter(outputStream);
             PdfDocument pdfDocument = new PdfDocument(writer)) {

            // pdfDocument.setTagged();
            PageSize pageSize = PageSize.A4.rotate();
            pdfDocument.setDefaultPageSize(pageSize);

            // Configure ConverterProperties to ignore exceptions
            ConverterProperties converterProperties = new ConverterProperties();
            DefaultFontProvider fontProvider = new DefaultFontProvider(false, false, false);

            for (String fontPath : fontList(locale)) {
                FontProgram fontProgram = FontProgramFactory.createFont(new ClassPathResource(fontPath).getFile().getAbsolutePath());
                log.info("fontPath:{}", fontPath);
                fontProvider.addFont(fontProgram);
            }
            converterProperties.setFontProvider(fontProvider);

             *//* document = HtmlConverter.convertToDocument(htmlContent, pdfDocument, converterProperties);
            document.close(); *//*
            HtmlConverter.convertToPdf(htmlContent, pdfDocument, converterProperties);
        }

        return outputStream;
        // Files.write(Paths.get("/logs/dep", "thymeleaf_test.pdf"), outputStream.toByteArray(), StandardOpenOption.CREATE); */
    }

    protected ByteArrayOutputStream generateCoverPage(String title, String template, String fragmentTemplate, String fragmentName, Locale locale) throws IOException {
        Context context = new Context();
        String customCssContentPath = new ClassPathResource("static/pdf/css/custom.css").getFile().getAbsolutePath();
        String cssContentPath = new ClassPathResource("static/pdf/css/bootstrap.min.css").getFile().getAbsolutePath();
        String messagePath = new ClassPathResource("static/pdf/img/message.svg").getFile().getAbsolutePath();
        String moneyPath = new ClassPathResource("static/pdf/img/money.svg").getFile().getAbsolutePath();
        String peoplesPath = new ClassPathResource("static/pdf/img/peoples.svg").getFile().getAbsolutePath();
        String profilePath = new ClassPathResource("static/pdf/img/logo-infinity.svg").getFile().getAbsolutePath();
        String iconCheckedPath = new ClassPathResource("static/pdf/img/IconChecked.png").getFile().getAbsolutePath();
        String iconUncheckedPath = new ClassPathResource("static/pdf/img/IconUnchecked.png").getFile().getAbsolutePath();
        String shoppingPath = new ClassPathResource("static/pdf/img/shopping.svg").getFile().getAbsolutePath();
        BootstrapPath bootstrapPath = new BootstrapPath();
        bootstrapPath.setCustomCssContent(customCssContentPath);
        bootstrapPath.setCssContent(cssContentPath);
        bootstrapPath.setMessageImg(messagePath);
        bootstrapPath.setMoneyImg(moneyPath);
        bootstrapPath.setPeoplesImg(peoplesPath);
        bootstrapPath.setProfileImg(profilePath);
        bootstrapPath.setShoppingImg(shoppingPath);
        bootstrapPath.setIconCheckedImg(iconCheckedPath);
        bootstrapPath.setIconUncheckedImg(iconUncheckedPath);

        context.setVariable("bootstrapPath", bootstrapPath);
        context.setVariable("fragmentTemplate", fragmentTemplate);
        context.setVariable("fragmentName", fragmentName);
        context.setVariable("title", title);

        // 1. create cover pdf
        String coverContent = templateEngine.process(template, context);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document;
        try (PdfDocument tempPdfDocument = new PdfDocument(new PdfWriter(baos))) {
            // tempPdfDocument.setTagged();
            PageSize pageSize = PageSize.A4.rotate();
            tempPdfDocument.setDefaultPageSize(pageSize);
            // Configure ConverterProperties to ignore exceptions
            ConverterProperties converterProperties = new ConverterProperties();
            DefaultFontProvider fontProvider = new DefaultFontProvider(false, false, false);

            /* String fontFolder = new ClassPathResource("static/pdf/fonts/" + fontName).getFile().getAbsolutePath();
            fontProvider.addDirectory(fontFolder); */
            for (String fontPath : fontList(locale)) {
                FontProgram fontProgram = FontProgramFactory.createFont(new ClassPathResource(fontPath).getFile().getAbsolutePath());
                log.info("fontPath:{}", fontPath);
                fontProvider.addFont(fontProgram);
            }
            converterProperties.setFontProvider(fontProvider);

            /* document = HtmlConverter.convertToDocument(coverContent, tempPdfDocument, converterProperties);
            document.close(); */
            HtmlConverter.convertToPdf(coverContent, tempPdfDocument, converterProperties);
        }
        return baos;
    }

    /**
     * https://kb.itextpdf.com/home/it7kb/examples/adding-a-cover-page-to-an-existing-pdf
     * @param pdfDocuments
     * @param password
     * @param needPwd
     */
    protected ByteArrayOutputStream merge(List<PdfDocument> pdfDocuments, String password, boolean needPwd){
        return PdfUtils.merge(pdfDocuments, password, needPwd);
    }

}
