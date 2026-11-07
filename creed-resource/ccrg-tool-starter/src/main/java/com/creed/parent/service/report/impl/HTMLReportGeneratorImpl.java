package com.creed.parent.service.report.impl;

import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.controller.pdf.dto.ContextInformation;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.OverallSummaryVo;
import com.creed.parent.controller.vo.PropertiesOverviewVo;
import com.creed.parent.controller.vo.PropertiesVo;
import com.creed.parent.controller.vo.PropertyReviewOverviewVo;
import com.creed.parent.controller.vo.PropertyReviewVo;
import com.creed.parent.controller.vo.ReportPropertiesOverviewVo;
import com.creed.parent.dal.model.DiffDisplay;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.model.ReportDetails;
import com.creed.parent.service.DiffService;
import com.creed.parent.service.report.AssetService;
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
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
@Service("htmlReportGenerator")
@Slf4j
public class HTMLReportGeneratorImpl implements ReportGenerator {
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
    private TemplateEngine templateEngine;

    @Resource
    @Lazy
    private DiffService diffService;

    @Resource
    ApplicationEventPublisher applicationEventPublisher;

    @Resource
    AssetService assetService;

    /** The page every HTML comparison renders — the download and the {@code PdfController} preview alike. */
    public static final String REPORT_TEMPLATE = "report-html-export-index";

    @Override
    public void generateReport(List<EnvironmentConfigurationVo> environmentConfiguration, ReportTypeEnum reportType, Locale locale, ReportMetadataInfo metadata, OutputStream os) {
        if (CollectionUtils.isEmpty(environmentConfiguration)) {
            log.warn("!!environmentConfiguration is empty!!");
            return;
        }
        log.info("propertyReviewOverview {} with Locale:{}...", reportType, locale);
        // outside the try: generateComparison's ServiceException must reach the caller unwrapped, as before
        Map<String, Object> model = buildReportModel(environmentConfiguration, metadata);
        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start("generateComparison data");
            Runtime runtime = Runtime.getRuntime();
            long startMemory = runtime.totalMemory() - runtime.freeMemory();
            log.info("Before generate HTML memory usage: {} bytes ~= {}", startMemory, PdfUtils.humanReadableByteCount(startMemory));

            Context context = new Context();
            context.setLocale(locale);
            context.setVariables(model);

            String html = templateEngine.process(REPORT_TEMPLATE, context);

            stopWatch.stop();
            log.info(">>stopWatch for generate HTML<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
            long endMemory = runtime.totalMemory() - runtime.freeMemory();
            log.info("After generate HTML memory usage: {} bytes ~= {}", endMemory, PdfUtils.humanReadableByteCount(endMemory));

            try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                IOUtils.write(html, bos, StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            log.error("Upload Exception: ", e);
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    /**
     * Every variable {@link #REPORT_TEMPLATE} reads: assets inlined, all three tabs. Shared by the
     * download above and the preview endpoint, so a page in the browser is exactly the file a user gets.
     */
    public Map<String, Object> buildReportModel(List<EnvironmentConfigurationVo> environmentConfiguration, ReportMetadataInfo metadata) {
        // 1. 根据properties进行分组
        PropertyReviewOverviewVo propertyReviewOverview = generateComparison(environmentConfiguration, metadata);
        // The whole comparison as JSON — every property twice, plus the overall copy. DEBUG only.
        if (log.isDebugEnabled()) {
            log.debug("propertyReviewOverview {}", JacksonUtils.toJsonString(propertyReviewOverview));
        }
        // 2. 根据分组结果开始生成HTML
        Map<String, Object> model = new HashMap<>();
        ContextInformation contextInformation = new ContextInformation();
        contextInformation.setFooter("Power By Comparator Tool");
        contextInformation.setTitle("Comparison Report");
        model.put("contextInformation", contextInformation);
        model.put("bootstrapCss", assetService.getBootstrapCss());
        model.put("bootstrapIconsCss", assetService.getBootstrapIconsCss());
        model.put("styleCss", assetService.getStyleCss());
        model.put("bootstrapJs", assetService.getBootstrapJs());
        model.put("favIcon", assetService.getFavicon());
        model.put("logo", assetService.getLogoImg());

        model.put("noneMatchingFragmentTemplate", "fragments/non-matching-html-fragment");
        model.put("noneMatchingFragmentName", "noneMatchingHtml");

        model.put("matchingFragmentTemplate", "fragments/matching-html-fragment");
        model.put("matchingFragmentName", "matchingHtml");

        model.put("overallFragmentTemplate", "fragments/overall-html-fragment");
        model.put("overallFragmentName", "overallHtml");

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

        List<PropertyReviewVo> overallProperties = propertyReviewOverview.getOverallProperties();
        PropertyReviewVo leftPropertyReviewO = overallProperties.get(0);
        PropertyReviewVo rightPropertyReviewO = overallProperties.get(1);
        List<DiffDisplay> diffDisplaysO = diffService.overallDisplayList(leftPropertyReviewO, rightPropertyReviewO);
        PropertiesOverviewVo overallRecord = new PropertiesOverviewVo(leftPropertyReviewO, rightPropertyReviewO, diffDisplaysO);

        model.put("reportPropertiesOverview", new ReportPropertiesOverviewVo(nonMatchingRecord, matchingRecord,
                overallRecord, propertyReviewOverview.getOverallSummary()));
        return model;
    }

    @Override
    public boolean supports(ReportTypeEnum reportType) {
        return ReportTypeEnum.HTML.equals(reportType);
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

        Map<String, PropertiesVo> allSource1 = byPropertyName(activeSources.get(1));
        Map<String, PropertiesVo> allSource2 = byPropertyName(activeSources.get(2));
        List<PropertyReviewVo> overallProperties = computeOverall(allSource1, allSource2, reportDetailsPair);

        return PropertyReviewOverviewVo.builder()
                .nonMatchingProperties(nonMatchingProperties)
                .matchingProperties(matchingProperties)
                .overallProperties(overallProperties)
                .overallSummary(summarize(allSource1, allSource2))
                .build();
    }

    /** First occurrence wins on a repeated name — the same merge the matching / non-matching maps use. */
    private Map<String, PropertiesVo> byPropertyName(List<PropertiesVo> properties) {
        return properties.stream().collect(Collectors.toMap(PropertiesVo::getPropertyName, Function.identity(), (o, n) -> o));
    }

    /**
     * Every property of both sources, matching or not: the union of names. Rendered through
     * DiffUtils with EQUAL parts it is the whole file side by side, which neither the matching nor the
     * non-matching half can show on its own — each has only one kind of line.
     */
    protected List<PropertyReviewVo> computeOverall(Map<String, PropertiesVo> allSource1,
                                                    Map<String, PropertiesVo> allSource2,
                                                    Supplier<Pair<ReportDetails, ReportDetails>> reportDetailsPair) {
        Set<String> allKeys = new HashSet<>(allSource1.keySet());
        allKeys.addAll(allSource2.keySet());
        return getPropertyReviewList(reportDetailsPair, allKeys, allSource1, allSource2);
    }

    protected OverallSummaryVo summarize(Map<String, PropertiesVo> allSource1, Map<String, PropertiesVo> allSource2) {
        int identical = 0;
        int changed = 0;
        for (Map.Entry<String, PropertiesVo> entry : allSource1.entrySet()) {
            PropertiesVo other = allSource2.get(entry.getKey());
            if (other == null) {
                continue;
            }
            // compared as rendered: the YAML holds Objects.toString(value, "")
            if (Objects.equals(Objects.toString(entry.getValue().getPropertyValue(), ""), Objects.toString(other.getPropertyValue(), ""))) {
                identical++;
            } else {
                changed++;
            }
        }
        int onlyInBaseline = allSource1.size() - identical - changed;
        int onlyInComparison = allSource2.size() - identical - changed;
        return new OverallSummaryVo(identical + changed + onlyInBaseline + onlyInComparison, identical, changed, onlyInBaseline, onlyInComparison);
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


}
