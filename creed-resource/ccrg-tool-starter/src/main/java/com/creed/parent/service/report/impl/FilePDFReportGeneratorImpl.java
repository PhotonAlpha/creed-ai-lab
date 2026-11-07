package com.creed.parent.service.report.impl;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.attach.ITagWorker;
import com.itextpdf.html2pdf.attach.ProcessorContext;
import com.itextpdf.html2pdf.attach.impl.DefaultTagWorkerFactory;
import com.itextpdf.html2pdf.attach.impl.tags.HTagWorker;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.IPropertyContainer;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.IElement;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.styledxmlparser.node.IElementNode;
import com.itextpdf.styledxmlparser.resolver.font.BasicFontProvider;
import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.controller.pdf.dto.BootstrapPath;
import com.creed.parent.controller.pdf.dto.ContextInformation;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.PropertiesVo;
import com.creed.parent.controller.vo.PropertyReviewOverviewVo;
import com.creed.parent.controller.vo.PropertyReviewVo;
import com.creed.parent.dal.model.DiffDisplay;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.model.ReportDetails;
import com.creed.parent.service.DiffService;
import com.creed.parent.service.PropertiesMatrixService;
import com.creed.parent.service.report.ReportGenerator;
import com.creed.parent.service.report.ReportMetadataInfo;
import com.creed.parent.utils.PdfUtils;
import com.creed.parent.utils.PropertySourcesTranslateUtil;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T18
 */
@Service("filePdfReportGenerator")
@Slf4j
public class FilePDFReportGeneratorImpl implements ReportGenerator {
    private static final Set<String> H_TAGS = new HashSet<>(Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6", "h7"));
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(".*/(?<filePath>[^/]+/LX/[^]]+).*");

    @Getter
    @Setter
    private UnaryOperator<String> extractOriginFileFunc = (original) -> {
        if (StringUtils.isNotBlank(original)) {
            Matcher matcher = FILE_PATH_PATTERN.matcher(original);
            if (matcher.matches()) {
                return matcher.group("filePath");
            }
        }
        return StringUtils.EMPTY;
    };

    public static final List<String> SKIP_KEYS = List.of("gebng.users");

    @Resource
    @Lazy
    private PropertiesMatrixService propertiesMatrixService;

    @Resource
    private TemplateEngine templateEngine;

    @Resource
    @Lazy
    private DiffService diffService;

    @Resource
    ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void generateReport(List<EnvironmentConfigurationVo> environmentConfiguration, ReportTypeEnum reportType, Locale locale, ReportMetadataInfo metadata, OutputStream os) {
        if (CollectionUtils.isEmpty(environmentConfiguration)) {
            log.warn("!!environmentConfiguration is empty!!");
            return;
        }
        // 1. 根据properties进行分组
        PropertyReviewOverviewVo propertyReviewOverview = generateComparison(environmentConfiguration, metadata);
        // 2. 根据分组结果开始生成PDF
        log.info("propertyReviewOverview {} {} with Locale:{}...", reportType, JacksonUtils.toJsonString(propertyReviewOverview), locale);
        // Path tempFile = null;
        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start("generateComparison data");
            Context nonMatchingContext = new Context();
            nonMatchingContext.setLocale(locale);
            ContextInformation nonMatchingContextInformation = new ContextInformation();
            nonMatchingContextInformation.setFooter("Power By Comparator Tool");
            nonMatchingContextInformation.setTitle("Comparison Report - Non Matching");
            nonMatchingContext.setVariable("contextInformation", nonMatchingContextInformation);

            Context matchingContext = new Context();
            matchingContext.setLocale(locale);
            ContextInformation matchingContextInformation = new ContextInformation();
            matchingContextInformation.setFooter("Power By Comparator Tool");
            matchingContextInformation.setTitle("Comparison Report - Matching");
            matchingContext.setVariable("contextInformation", matchingContextInformation);
            stopWatch.stop();

            Runtime runtime = Runtime.getRuntime();
            long startMemory = runtime.totalMemory() - runtime.freeMemory();
            log.info("Before generate PDF memory usage: {} bytes ~= {}", startMemory, PdfUtils.humanReadableByteCount(startMemory));
            stopWatch.start("nonMatchingPdf PDF");
            File nonMatchingPdf = constructPdfDocumentOS(nonMatchingContext, propertyReviewOverview.getNonMatchingProperties(), (left, right) -> diffService.diffDisplayList(left, right),
                    "fragments/non_matching_fragment", "pdf");
            stopWatch.stop();

            stopWatch.start("matchingPdf PDF");
            File matchingPdf = constructPdfDocumentOS(matchingContext, propertyReviewOverview.getMatchingProperties(), (left, right) -> diffService.nonDiffDisplayList(left, right),
                    "fragments/matching_fragment", "pdf");
            stopWatch.stop();

            stopWatch.start("merge PDF");
            // String generateFormattedFileName = propertyReviewOverview.getCustomFilename();
            // if (StringUtils.isBlank(generateFormattedFileName)) {
            //     generateFormattedFileName = RandomStringUtils.randomAlphabetic(5);
            // }
            // generateFormattedFileName = generateFormattedFileName.replace("/", "-");
            // tempFile = Files.createTempFile("tmp-", generateFormattedFileName);
            // log.info("tempFile PDF file created: {}", tempFile);
            try (BufferedOutputStream bos = new BufferedOutputStream(os);
                 PdfWriter nonMatchingwriter = new PdfWriter(nonMatchingPdf);
                 PdfDocument nonMatchingwriterDocument = new PdfDocument(nonMatchingwriter);
                 PdfWriter matchingwriter = new PdfWriter(matchingPdf);
                 PdfDocument matchingwriterDocument = new PdfDocument(matchingwriter);) {
                PdfUtils.merge(Arrays.asList(nonMatchingwriterDocument, matchingwriterDocument), "pwd", false, bos);
            }
            stopWatch.stop();

            log.info(">>stopWatch for generate PDF<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
            long endMemory = runtime.totalMemory() - runtime.freeMemory();
            log.info("After generate PDF memory usage: {} bytes ~= {}", endMemory, PdfUtils.humanReadableByteCount(endMemory));
       /*      final String finalGenerateFormattedFileName = generateFormattedFileName;
            return new ByteArrayResource(Files.readAllBytes(tempFile)) {
                @Override
                public String getFilename() {
                    return finalGenerateFormattedFileName;
                }
            }; */
        } catch (Exception e) {
            log.error("Upload Exception: ", e);
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
        /* finally {
            try {
                if (Objects.nonNull(tempFile)) {
                    boolean result = Files.deleteIfExists(tempFile);
                    log.info("deleting tmp files:{} is success:{}", tempFile, result);
                }
            } catch (IOException e) {
                log.error("deleteIfExists Exception: {}", ExceptionUtils.getMessage(e));
            }
        } */
    }

    @Override
    public boolean supports(ReportTypeEnum reportType) {
        return false;//ReportTypeEnum.PDF.equals(reportType);
    }


    public PropertyReviewOverviewVo generateComparison(List<EnvironmentConfigurationVo> environmentConfiguration, ReportMetadataInfo metadata) {

        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        log.info(">>Before Start Memory Usage: {} bytes ~= {}", startMemory, PdfUtils.humanReadableByteCount(startMemory));

        log.debug(">>environmentConfiguration: {}", JacksonUtils.toJsonString(environmentConfiguration));
        long endMemory = runtime.totalMemory() - runtime.freeMemory();

        log.info(">>After Start Memory Usage: {} bytes ~= {}", endMemory, PdfUtils.humanReadableByteCount(endMemory));

        String connectionId = metadata.getConnectionId();
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.PROPERTIES_COUNTING));

        List<PropertiesVo> sourceDataList = Optional.of(environmentConfiguration).orElse(Collections.emptyList())
                .stream().map(EnvironmentConfigurationVo::getProperties)
                .flatMap(Collection::stream)
                .toList();
        // filter out activeSources
        Map<Integer, List<PropertiesVo>> activeSources = sourceDataList.stream().filter(PropertiesVo::getActive)
                .collect(Collectors.groupingBy(PropertiesVo::getSource, Collectors.mapping(Function.identity(), Collectors.toList())));
        if (activeSources.size() < 2) {
            throw new ServiceException(500, "At least one environmentConfiguration loading failure!");
        }
        Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> sourceUniqueKVMappingFunc = as ->
                as.get(1).stream().collect(Collectors.toMap(entry -> entry.getPropertyName() + ":" + entry.getPropertyValue(), Function.identity()));

        Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> targetUniqueKVMappingFunc = as ->
                as.get(2).stream().collect(Collectors.toMap(entry -> entry.getPropertyName() + ":" + entry.getPropertyValue(), Function.identity()));
        Supplier<Pair<ReportDetails, ReportDetails>> reportDetailsPair = () -> {
            List<ReportDetails> reportDetails = builReportDetails(environmentConfiguration);
            ReportDetails leftReportDetails = reportDetails.get(0);
            ReportDetails rightReportDetails = new ReportDetails();
            if (reportDetails.size() > 1) {
                rightReportDetails = reportDetails.get(1);
            }
            return Pair.of(leftReportDetails, rightReportDetails);
        };
        List<PropertyReviewVo> nonMatchingProperties = computeNonMatching(activeSources, sourceUniqueKVMappingFunc, targetUniqueKVMappingFunc, reportDetailsPair);
        List<PropertyReviewVo> matchingProperties = computeMatching(activeSources, sourceUniqueKVMappingFunc, targetUniqueKVMappingFunc, reportDetailsPair);
        log.debug(">>nonMatchingProperties:{}", JacksonUtils.toJsonString(nonMatchingProperties));

        return PropertyReviewOverviewVo.builder().nonMatchingProperties(nonMatchingProperties).matchingProperties(matchingProperties).build();
    }

    private List<ReportDetails> builReportDetails(List<EnvironmentConfigurationVo> environmentConfiguration) {
        var reportDetails = new ArrayList<ReportDetails>();
        for (EnvironmentConfigurationVo vo : environmentConfiguration) {
            ReportDetails details = new ReportDetails();
            details.setProfile(vo.getActiveProfile());
            RouteDefinition routeDefinition = vo.getRouteDefinition();
            details.setReleaseName(routeDefinition.getReleaseName());
            details.setCountryCode(routeDefinition.getCountryCode());
            details.setInstance(routeDefinition.getInstance());
            details.setEnvironment(routeDefinition.getEnvironment());
            details.setFileNames(String.join(",", listAllFiles(vo)));
            reportDetails.add(details);
        }
        return reportDetails;
    }

    protected List<PropertyReviewVo> computeNonMatching(Map<Integer, List<PropertiesVo>> activeSources,
                                                        Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> sourceUniqueKVMappingFunc,
                                                        Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> targetUniqueKVMappingFunc,
                                                        Supplier<Pair<ReportDetails, ReportDetails>> reportDetailsPair) {
        Map<String, PropertiesVo> sourceUniqueKVMapping = sourceUniqueKVMappingFunc.apply(activeSources);
        Map<String, PropertiesVo> targetUniqueKVMapping = targetUniqueKVMappingFunc.apply(activeSources);

        Collection<String> matchingKeys = CollectionUtils.intersection(sourceUniqueKVMapping.keySet(), targetUniqueKVMapping.keySet());
        //rest is noneMatching values
        Map<String, PropertiesVo> nonMatching4Source1 = activeSources.get(1).stream().filter(sd -> !matchingKeys.contains(sd.getPropertyName() + ":" + sd.getPropertyValue()))
                .collect(Collectors.toMap(PropertiesVo::getPropertyName, Function.identity(), (o, n) -> o));//16230 207 = 16437
        Map<String, PropertiesVo> nonMatching4Source2 = activeSources.get(2).stream().filter(sd -> !matchingKeys.contains(sd.getPropertyName() + ":" + sd.getPropertyValue()))
                .collect(Collectors.toMap(PropertiesVo::getPropertyName, Function.identity(), (o, n) -> o));//16230 182 = 16412
        HashSet<String> nonMatchingKeys = new HashSet<>(nonMatching4Source1.keySet());
        nonMatchingKeys.addAll(nonMatching4Source2.keySet());
        return getPropertyReviewList(reportDetailsPair, nonMatchingKeys, nonMatching4Source1, nonMatching4Source2);
    }

    protected List<PropertyReviewVo> computeMatching(Map<Integer, List<PropertiesVo>> activeSources,
                                                     Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> sourceUniqueKVMappingFunc,
                                                     Function<Map<Integer, List<PropertiesVo>>, Map<String, PropertiesVo>> targetUniqueKVMappingFunc,
                                                     Supplier<Pair<ReportDetails, ReportDetails>> reportDetailsPair) {
        Map<String, PropertiesVo> sourceUniqueKVMapping = sourceUniqueKVMappingFunc.apply(activeSources);
        Map<String, PropertiesVo> targetUniqueKVMapping = targetUniqueKVMappingFunc.apply(activeSources);

        Collection<String> matchingKeys = CollectionUtils.intersection(sourceUniqueKVMapping.keySet(), targetUniqueKVMapping.keySet());
        //rest is matching values
        Map<String, PropertiesVo> matching4Source1 = activeSources.get(1).stream().filter(sd -> matchingKeys.contains(sd.getPropertyName() + ":" + sd.getPropertyValue()))
                .collect(Collectors.toMap(PropertiesVo::getPropertyName, Function.identity(), (o, n) -> o));//16230 207 = 16437
        Map<String, PropertiesVo> matching4Source2 = activeSources.get(2).stream().filter(sd -> matchingKeys.contains(sd.getPropertyName() + ":" + sd.getPropertyValue()))
                .collect(Collectors.toMap(PropertiesVo::getPropertyName, Function.identity(), (o, n) -> o));//16230 182 = 16412
        HashSet<String> propertyKeys = new HashSet<>(matching4Source1.keySet());
        propertyKeys.addAll(matching4Source2.keySet());
        return getPropertyReviewList(reportDetailsPair, propertyKeys, matching4Source1, matching4Source2);
    }


    private List<PropertyReviewVo> getPropertyReviewList(Supplier<Pair<ReportDetails, ReportDetails>> reportDetailsPair,
                                                         Collection<String> uniqueKeys,
                                                         Map<String, PropertiesVo> sourceProperties,
                                                         Map<String, PropertiesVo> targetProperties) {
        Map<String, String> source1Properties = uniqueKeys.stream()
                .filter(sourceProperties::containsKey)
                .collect(Collectors.toMap(key -> key, key -> Objects.toString(sourceProperties.get(key).getPropertyValue(), "")))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (o, n) -> o, LinkedHashMap::new));

        Map<String, String> source1PropertiesOrigin = uniqueKeys.stream()
                .filter(sourceProperties::containsKey)
                .collect(Collectors.toMap(key -> key, key -> getOriginValueFunc().apply(sourceProperties.get(key).getOrigin())))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (o, n) -> o, LinkedHashMap::new));
        String yaml1 = PropertySourcesTranslateUtil.map2YamlConverter(source1Properties);
        String yaml1PropertiesOrigin = PropertySourcesTranslateUtil.map2YamlConverter(source1PropertiesOrigin);

        Map<String, String> source2Properties = uniqueKeys.stream()
                .filter(targetProperties::containsKey)
                .collect(Collectors.toMap(key -> key, key -> Objects.toString(targetProperties.get(key).getPropertyValue(), "")))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (o, n) -> o, LinkedHashMap::new));
        Map<String, String> source2PropertiesOrigin = uniqueKeys.stream()
                .filter(targetProperties::containsKey)
                .collect(Collectors.toMap(key -> key, key -> getOriginValueFunc().apply(targetProperties.get(key).getOrigin())))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (o, n) -> o, LinkedHashMap::new));
        String yaml2 = PropertySourcesTranslateUtil.map2YamlConverter(source2Properties);
        String yaml2PropertiesOrigin = PropertySourcesTranslateUtil.map2YamlConverter(source2PropertiesOrigin);

        Set<String> missingInTarget = SetUtils.difference(source1Properties.keySet(), source2Properties.keySet());
        Set<String> missingInSource = SetUtils.difference(source2Properties.keySet(), source1Properties.keySet());
        // group by parent property key
        Predicate<String> filterByKey = keyStr -> !StringUtils.startsWithAny(keyStr, SKIP_KEYS.toArray(String[]::new));
        List<PropertiesVo> missingInTargetProps = missingInTarget.stream()
                .filter(filterByKey)
                .sorted(Comparator.naturalOrder())
                .map(sourceProperties::get)
                .filter(Objects::nonNull)
                .toList();

        List<PropertiesVo> missingInSourceProps = missingInSource.stream()
                .filter(filterByKey)
                .sorted(Comparator.naturalOrder())
                .map(targetProperties::get)
                .filter(Objects::nonNull)
                .toList();
        Comparator<String> lengthComparator = Comparator.comparingInt(str -> StringUtils.length(str));
        Comparator<String> tComparator = lengthComparator.thenComparing(Comparator.naturalOrder());
        // get all original
        List<String> changeFiles = Stream.of(sourceProperties.values(), targetProperties.values())
                .flatMap(Collection::stream)
                .map(PropertiesVo::getOrigin)
                .map(extractOriginFileFunc)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .sorted(tComparator)
                .toList();
        // List<String> baselineOriginalFiles = sourceProperties.values().stream()
        //         .map(PropertiesVo::getOrigin)
        //         .map(extractOriginFileFunc)
        //         .filter(StringUtils::isNotBlank)
        //         .distinct()
        //         .sorted(tComparator)
        //         .toList();
        // List<String> comparisonOriginalFiles = targetProperties.values().stream()
        //         .map(PropertiesVo::getOrigin)
        //         .map(extractOriginFileFunc)
        //         .filter(StringUtils::isNotBlank)
        //         .distinct()
        //         .sorted(tComparator)
        //         .toList();


        Pair<ReportDetails, ReportDetails> pair = reportDetailsPair.get();
        return Arrays.asList(PropertyReviewVo.builder()
                        .content(yaml1)
                        .contentOrigin(yaml1PropertiesOrigin)
                        .reportDetails(pair.getLeft())
                        .changeFiles(changeFiles)
                        .missingProperties(missingInTargetProps)
                        .build(),
                PropertyReviewVo.builder()
                        .content(yaml2)
                        .contentOrigin(yaml2PropertiesOrigin)
                        .reportDetails(pair.getRight())
                        .changeFiles(changeFiles)
                        .missingProperties(missingInSourceProps)
                        .build());
    }

    private List<String> listAllFiles(EnvironmentConfigurationVo vo) {
        return Optional.ofNullable(vo).map(EnvironmentConfigurationVo::getProperties).orElse(Collections.emptyList())
                .stream().map(PropertiesVo::getOrigin)
                .map(origin -> StringUtils.substringBetween(origin, "[file:", "]"))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    protected Function<Object, String> getOriginValueFunc() {
        return value -> Objects.toString(value, "").replaceAll(" ", "");
    }

    public File constructPdfDocumentOS(Context context, List<PropertyReviewVo> propertyValueData, BiFunction<PropertyReviewVo, PropertyReviewVo, List<DiffDisplay>> diffServiceFunc, String fragmentTemplate, String fragmentName) throws IOException {
        PropertyReviewVo leftPropertyReview = propertyValueData.get(0);
        PropertyReviewVo rightPropertyReview = propertyValueData.get(1);
        List<DiffDisplay> displayList = diffServiceFunc.apply(leftPropertyReview, rightPropertyReview);
        log.debug("see:{}", JacksonUtils.toJsonString(displayList));
        context.setVariable("displayList", displayList);
        context.setVariable("leftReport", leftPropertyReview);
        context.setVariable("rightReport", rightPropertyReview);
        return constructPdfDocumentOS(context, "pdf-index", fragmentTemplate, fragmentName, context.getLocale());
    }

    public File constructPdfDocumentOS(Context context, String template, String fragmentTemplate, String fragmentName, Locale locale) throws IOException {
        // String baseUrl = getCurrentBaseUrl();
        String customCssContentPath;
        // see https://stackoverflow.com/questions/53203909/spring-boot-thymeleaf-css-is-not-applied-to-template
        if (org.thymeleaf.util.StringUtils.equals(locale.getLanguage(), "zh")) {
            customCssContentPath = "pdf/css/custom.SC.css";
        } else {
            customCssContentPath = "pdf/css/custom.css";
        }
        // String cssContentPath =  "/pdf/css/custom.css";
        String cssContentPath = "pdf/css/bootstrap.min.css";
        String profilePath = "pdf/img/logo-infinity.svg";
        BootstrapPath bootstrapPath = new BootstrapPath();
        bootstrapPath.setCustomUtilsCss("pdf/css/custom-utilities.css");
        bootstrapPath.setCustomCssContent(customCssContentPath);
        bootstrapPath.setCssContent(cssContentPath);
        bootstrapPath.setProfileImg(profilePath);

        context.setVariable("bootstrapPath", bootstrapPath);
        context.setVariable("fragmentTemplate", fragmentTemplate);
        context.setVariable("fragmentName", fragmentName);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("constructPdfDocumentOS-templateEngine process");
        String htmlContent = templateEngine.process(template, context);
        stopWatch.stop();
        File tempPdf = File.createTempFile("pdf-", ".pdf");
        log.info("tempPdf:{}", tempPdf.toPath());
        stopWatch.start("constructPdfDocumentOS-generatePDF process");
        try (PdfWriter writer = new PdfWriter(Files.newOutputStream(tempPdf.toPath()));
             PdfDocument pdfDocument = new PdfDocument(writer)) {

            // pdfDocument.setTagged();
            PageSize pageSize = PageSize.A4.rotate();
            pdfDocument.setDefaultPageSize(pageSize);
            pdfDocument.setTagged();
            // Configure ConverterProperties to ignore exceptions
            ConverterProperties converterProperties = new ConverterProperties();
            BasicFontProvider fontProvider = new BasicFontProvider(false, false, false);

            for (String fontPath : fontList(locale)) {
                FontProgram fontProgram = FontProgramFactory.createFont(new ClassPathResource(fontPath).getInputStream().readAllBytes(), true);
                log.info("fontPath:{}", fontPath);
                fontProvider.addFont(fontProgram);
            }
            converterProperties.setFontProvider(fontProvider);
            converterProperties.setBaseUri(getCurrentBaseUrl() + "/");
            // https://kb.itextpdf.com/itext/creating-well-tagged-pdf-documents-with-pdfhtml
            DefaultTagWorkerFactory factory = new DefaultTagWorkerFactory() {
                @Override
                public ITagWorker getCustomTagWorker(IElementNode tag, ProcessorContext context) {
                    if (H_TAGS.contains(tag.name())) {
                        return new HTagWorker(tag, context) {
                            @Override
                            public boolean processTagChild(ITagWorker childTagWorker, ProcessorContext context) {
                                return super.processTagChild(childTagWorker, context);
                            }

                            @Override
                            public IPropertyContainer getElementResult() {
                                IPropertyContainer elementResult = super.getElementResult();
                                if (elementResult instanceof Div) {
                                    for (IElement child : ((Div) elementResult).getChildren()) {
                                        if (child instanceof Paragraph) {
                                            ((Paragraph) child).setNeutralRole();
                                        }
                                    }
                                }
                                return elementResult;
                            }
                        };
                    }
                    /* use PDF comment instead
                    else if ("span".equals(tag.name()) && StringUtils.isNotBlank(tag.getAttribute("data-comment"))) {
                        return new AnnotatedSpanTagWorker(tag, context);
                    }*/
                    return super.getCustomTagWorker(tag, context);
                }
            };
            converterProperties.setTagWorkerFactory(factory);
            // converterProperties.setCssApplierFactory()

            /* document = HtmlConverter.convertToDocument(htmlContent, pdfDocument, converterProperties);
            document.close(); */

            stopWatch.stop();
            stopWatch.start("HtmlConverter.convertToPdf...");
            HtmlConverter.convertToPdf(htmlContent, pdfDocument, converterProperties);

            // Document convertedDocument = HtmlConverter.convertToDocument(htmlContent, pdfDocument, converterProperties);
            // PdfAnnotation comment = new PdfTextAnnotation(new Rectangle(100, 600, 0, 0))
            //         .setContents("this is a comment")
            //         .setTitle(new PdfString("注释作者"))
            //         .setContents("Hello PDF");
            // PdfPage page = pdfDocument.getPage(1);
            // page.addAnnotation(comment);
            // convertedDocument.close();

        }
        stopWatch.stop();
        log.info(">>stopWatch for template PDF<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        return tempPdf;
        // Files.write(Paths.get("/logs/dep", "thymeleaf_test.pdf"), outputStream.toByteArray(), StandardOpenOption.CREATE);
    }

    public String getCurrentBaseUrl() {
        // ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        /* if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attr) {
            HttpServletRequest httpServletRequest = attr.getRequest();
            String baseUrl = httpServletRequest.getScheme() + "://" + httpServletRequest.getServerName() + ":" + httpServletRequest.getServerPort() + httpServletRequest.getContextPath();
            log.info("baseUrl:{}", baseUrl);
            return baseUrl;
        }
        log.info("looking baseUrl failed!");
        return StringUtils.EMPTY; */
        return propertiesMatrixService.getRootPath();
    }

    protected List<String> fontList(Locale locale) {
        if (org.thymeleaf.util.StringUtils.equals(locale.getLanguage(), "zh")) {
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
}
