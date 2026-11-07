package com.creed.parent.service;

import com.google.common.base.Suppliers;
import com.creed.parent.client.vo.confluence.attachment.AttachmentContentVO;
import com.creed.parent.client.vo.confluence.attachment.ResultsVO;
import com.creed.parent.client.vo.confluence.attachment.SimpleAttachmentContentVO;
import com.creed.parent.client.vo.confluence.content.ChildrenVO;
import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.PageVO;
import com.creed.parent.client.vo.confluence.metadata.AttachmentPropertyVO;
import com.creed.parent.client.vo.confluence.metadata.ContentPropertyVO;
import com.creed.parent.config.PropertiesMatrixConfig;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.constant.WalkPathTypeEnum;
import com.creed.parent.controller.dict.vo.DictListReqVO;
import com.creed.parent.controller.prompt.vo.*;
import com.creed.parent.controller.vo.*;
import com.creed.parent.convert.ConfluenceConvert;
import com.creed.parent.convert.DictConvert;
import com.creed.parent.convert.RoutesConvert;
import com.creed.parent.dal.model.WalkPathInfo;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.model.ReportDetails;
import com.creed.parent.repo.ConfluenceRepository;
import com.creed.parent.service.walkpath.AppHomeDirCalculateService;
import com.creed.parent.service.walkpath.ZonesCalculateService;
import com.creed.parent.utils.DateTimeUtil;
import com.creed.parent.utils.PropertySourcesTranslateUtil;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StopWatch;
import org.springframework.web.context.request.RequestContextHolder;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * @date 7/14/25
 */
@Service
@Slf4j
public class PromptCombinationGenerativeService {
    public static final String ROUTE_FORM_KEY = "RouteFormMetadata";
    private static final Set<String> H_TAGS = new HashSet<>(Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6", "h7"));
    private static final Semaphore SEMAPHORE = new Semaphore(60);
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(".*/(?<filePath>[^/]+/LX/[^]]+).*");
    public static final List<String> SKIP_KEYS = List.of("gebng.users");

    public static final Pattern NXG_PATTERN = Pattern.compile("^\\D+\\d*(?<nxgNo>\\d)");
    // public static final Pattern NXG_PATTERN = Pattern.compile("(?<nxgNo>\\d+)$");
    public static final Pattern ZONE_PATTERN = Pattern.compile("^\\D+(?<zoneNo>\\d)\\d*");
    public static final Set<String> EXCLUDE_LIST = Set.of("ADC", "TDC", "LC", "adc", "tdc", "lc", "nxg", "nxg2");
    @Resource
    AppHomeDirCalculateService appHomeDirCalculateService;
    @Resource
    ZonesCalculateService zonesCalculateService;
    @Resource
    PropertiesMatrixConfig propertiesMatrixConfig;
    @Resource
    private DictLoadService dictV2Service;
    @Resource
    private DictService dictService;
    @Resource
    @Lazy
    private PromptCombinationGenerativeService self;
    @Resource
    @Lazy
    private ReportGenerationService reportGenerationService;

    @Resource
    @Lazy
    private PropertiesService propertiesService;

    @Resource
    ApplicationEventPublisher applicationEventPublisher;

    @Resource(name = "springTemplateEngine")
    private SpringTemplateEngine springTemplateEngine;

    @Resource
    @Lazy
    ConfluenceRepository confluenceRepository;

    @Resource
    @Lazy
    private DictLoadService dictLoadService;
    // confluence page ancestorId
    @Value("${access.confluence.project-id:GN}")
    private String projectId;
    @Value("${access.confluence.ancestor-id:386415517}")
    private String ancestorId;

    /**
     * description PromptCombinationGenerativeService
     * @author EthanCao
     * @since 2025-12-19T10
     * cache the request
     */
    public PromptCombinationResponseVo listInstanceMatrix(PromptCombinationRequest request) {
        PromptCombinationResponseVo response = new PromptCombinationResponseVo();
        WalkPathInfo baselineWalkPathInfo = DictConvert.INSTANCE.convert2WalkPathBaseLine(request);
        WalkPathInfo comparisonWalkPathInfo = DictConvert.INSTANCE.convert2WalkPathComparison(request);

        baselineWalkPathInfo.setDictType(WalkPathTypeEnum.COMMON.name());
        List<SelectedOptionVo> selectedOptions = dictV2Service.pathWalk(baselineWalkPathInfo);
        if (CollectionUtils.isEmpty(selectedOptions)) {
            log.warn("country is empty");
            throw new ServiceException(500, "country is empty");
        }
        // take first country code
        String countryCode = selectedOptions.stream().findFirst().map(SelectedOptionVo::getName).orElse(StringUtils.EMPTY);
        baselineWalkPathInfo.setCountryCode(countryCode);
        comparisonWalkPathInfo.setCountryCode(countryCode);
        String baselineRelease = request.getBaselineRelease();
        String comparisonRelease = request.getComparisonRelease();
        Map<String, Map<String, Set<String>>> baselineInstanceMatrix;
        Map<String, Map<String, Set<String>>> comparisonInstanceMatrix;
        if (StringUtils.equals(baselineRelease, comparisonRelease)) {
            baselineInstanceMatrix = doInstanceMatrix(baselineWalkPathInfo);
            comparisonInstanceMatrix = baselineInstanceMatrix;
        } else {
            baselineInstanceMatrix = doInstanceMatrix(baselineWalkPathInfo);
            comparisonInstanceMatrix = doInstanceMatrix(comparisonWalkPathInfo);
        }

        response.setBaselineInstanceMatrix(baselineInstanceMatrix);
        response.setComparisonInstanceMatrix(comparisonInstanceMatrix);
        response.setBaselineRelease(baselineRelease);
        response.setComparisonRelease(comparisonRelease);
        return response;

    }

    public Map<String, Map<String, Set<String>>> doInstanceMatrix(WalkPathInfo walkPathInfo) {
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getProjectName()), "Project can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getReleaseName()), "Release can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getApp()), "Application can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getCountryCode()), "Country Code can not be empty");
        Map<String, Map<String, Set<String>>> instanceMatrix = appHomeDirCalculateService.searchInstanceMatrix(Path.of(propertiesMatrixConfig.getRootPath()), walkPathInfo);
        log.info("doInstanceMatrix:{}", JacksonUtils.toJsonString(instanceMatrix));
        return instanceMatrix;
    }


    protected Map<String, Set<String>> listZoneMatrix(WalkPathInfo walkPathInfo) {
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getProjectName()), "Project can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getReleaseName()), "Release can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getApp()), "Application can not be empty");
        Assert.isTrue(StringUtils.isNotBlank(walkPathInfo.getCountryCode()), "Country Code can not be empty");
        walkPathInfo.setEnvironment("QUA* OR PDN*");
        return zonesCalculateService.searchInstanceMatrix(Path.of(propertiesMatrixConfig.getRootPath()), walkPathInfo, "on-profile", "spring.config.activate.on-profile", AppCodeEnum.toAppEnum(walkPathInfo.getApp()));
    }

    public String generateReportsInConfluence(PromptBatchForm request, ReportTypeEnum reportType) {
        List<PromptCombinationRequest> batchRequests = request.getBatch();
        log.info("generateReportsInConfluence starting...");

        String fileName = DateTimeUtil.getTimeStr() + " Configs Report";
        RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);
        String connectionId = request.getConnectionId();
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.INIT));

        // 1. generate draft confluence page
        ConfluenceContentVO draftConfluencePage = confluenceRepository.generateDraftConfluencePage(projectId, ancestorId, fileName, "<p>Generating reports...</p>");
        String draftPageId = Optional.ofNullable(draftConfluencePage)
                .map(ConfluenceContentVO::getId).orElseThrow(() -> new ServiceException(500, "Generate draft page failed"));
        // 2. generate generateComparison

        StopWatch stopWatch = new StopWatch();
        stopWatch.start("generateReportsInConfluence");
        List<RouteFormVo> finalListing = new ArrayList<>();
        for (PromptCombinationRequest combinationRequest : batchRequests) {
            List<RouteWebFormVo> promptCombination = self.generateCombination(combinationRequest);

            List<RouteFormVo> combinationList = promptCombination.stream()
                    .map(this::enrichRouteFormVo)
                    .flatMap(Collection::stream)
                    .toList();
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.SET_TOTAL, combinationList.size()));
            log.info("doGenerateAndUploadPdf:{}", JacksonUtils.toJsonString(combinationList));
            // generate PDF
            doGenerateAndUpload2Cloud(combinationList, connectionId, draftPageId, reportType);
            finalListing.addAll(combinationList);
        }
        stopWatch.stop();
        log.info(">>stopWatch for generateReportsInConfluence<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.COMPLETED));
        List<RouteInfoGroupingVO> routeInfoGrouping = reportGenerationService.groupingForTemplate(finalListing);
        // update confluence page content
        String htmlTemplate = reportGenerationService.renderHtmlTemplate(routeInfoGrouping);

        // ContentProperty 存在大小限制，需要转为json元数据 然后再合并
        // RouteFormMetaData.json
        BatchFormVo batchFormVo = new BatchFormVo();
        batchFormVo.setBatch(finalListing);
        SimpleAttachmentContentVO simpleAttachmentContent = reportGenerationService.uploadFile2Cloud(draftPageId, new ByteArrayResource(JacksonUtils.toJsonByte(batchFormVo)) {
            @Override
            public String getFilename() {
                return ROUTE_FORM_KEY + ".json";
            }
        });

        ContentPropertyVO<AttachmentPropertyVO> contentProperty = ConfluenceConvert.INSTANCE.buildContentProperty(draftPageId, ROUTE_FORM_KEY, simpleAttachmentContent.getId(), DateTimeUtil.getTimeStr());
        confluenceRepository.createContentProperty(draftPageId, contentProperty);

        ConfluenceContentVO finalConfluenceContent = confluenceRepository.updateDraftConfluencePage(draftConfluencePage, htmlTemplate);
        // draftConfluencePage
        String generatedLink = Optional.ofNullable(finalConfluenceContent)
                .map(ConfluenceContentVO::getLinks)
                .map(link -> link.getBase() + link.getWebui()).orElse(StringUtils.EMPTY);
        log.info(">>generatedLink:{}", generatedLink);
        log.info("generateReportsInConfluence ending...");
        // 4. if failed, delete confluence page TODO
        // if successful, return page link
        return generatedLink;
    }

    public void doGenerateAndUpload2Cloud(List<RouteFormVo> combinationList, String connectionId, String draftPageId, ReportTypeEnum reportType) {
        log.info("doGenerateAndUpload2Cloud:{} reportType:{}", JacksonUtils.toJsonString(combinationList), reportType);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("doGenerateAndUpload2Cloud");
        // create draft confluence page
        List<CompletableFuture<SimpleAttachmentContentVO>> futureList = new ArrayList<>();
        for (RouteFormVo enrichedVo : combinationList) {
            /* PropertyReviewOverviewVo propertyReviewOverview = null;
            try {
                propertyReviewOverview = reportGenerationService.generateComparison(connectionId, enrichedVo.getRoutes());
            } catch (Exception e) {
                log.error(">>generateComparison error", e);
            } */
            List<EnvironmentConfigurationVo> environmentConfiguration = propertiesService.getAllProperties(enrichedVo.getRoutes());

            if (CollectionUtils.isEmpty(environmentConfiguration)) {
                continue;
            }
            log.debug(">>propertyReviewOverview:{}", JacksonUtils.toJsonString(environmentConfiguration));
            log.info(">>property results:{}", environmentConfiguration.size());
            // 3. upload pdf to confluence page
            futureList.add(reportGenerationService.generateAndUpload2Cloud(draftPageId, connectionId, environmentConfiguration, enrichedVo, Locale.ENGLISH, reportType));
            applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.REPORT_INIT));
        }
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        stopWatch.stop();
        log.info(">>stopWatch for doGenerateAndUploadPdf<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
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

    protected Function<Object, String> getOriginValueFunc() {
        return value -> Objects.toString(value, "").replaceAll(" ", "");
    }

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

    List<RouteFormVo> enrichRouteFormVo(RouteWebFormVo vo) {
        log.info("enrichRouteForm starting...");
        Optional<RouteInfoWebVo> firstInfo = vo.getRoutes().stream().findFirst();
        String appCode = firstInfo.map(RouteInfoWebVo::getApp).orElse(StringUtils.EMPTY);
        // if MS, export all MS modules
        // Collection<String> matchingKeys = CollectionUtils.intersection(sourceUniqueKVMapping.keySet(), targetUniqueKVMapping.keySet());
        if (firstInfo.isPresent() && StringUtils.equalsIgnoreCase(appCode, "MS")) {
            // make sure left and right side module consistent, if there is diff, just ignore
            List<String> moduleList = vo.getRoutes().stream()
                    .map(this::findAllModuleList)
                    .reduce((pre, aft) -> CollectionUtils.intersection(pre, aft).stream().toList())
                    .orElse(Collections.emptyList());
            log.info("final moduleList in batch:{}", moduleList);
            return moduleList.stream().map(str -> {
                RouteFormVo routeFormVo = RoutesConvert.INSTANCE.copy(vo);
                routeFormVo.getRoutes().forEach(r -> r.setModule(str));
                return routeFormVo;
            }).toList();
        }
        return Collections.singletonList(RoutesConvert.INSTANCE.copy(vo));
    }

    private List<String> findAllModuleList(RouteInfoWebVo infoVo) {
        WalkPathInfo walkPathInfo = new WalkPathInfo();
        BeanUtils.copyProperties(infoVo, walkPathInfo);
        walkPathInfo.setDictType(WalkPathTypeEnum.MODULE.name());
        List<SelectedOptionVo> modules = dictLoadService.pathWalk(walkPathInfo);
        if (CollectionUtils.isEmpty(modules)) {
            return Collections.emptyList();
        }

        List<String> moduleList = modules.stream().map(SelectedOptionVo::getName).toList();
        log.info("findAllModuleList meta info:{} result:{}", infoVo, moduleList);
        return moduleList;
    }

    public void doGenerateReportsInConfluence(List<RouteWebFormVo> routeInfos, String connectionId) {
        log.info("generateReportsInConfluence starting...");
        String fileName = DateTimeUtil.getTimeStr() + " Configs Report";
        RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.INIT));

    }

    /**
     * 生成单个的组合列表，为生成表报做准备
     * description PromptCombinationGenerativeService
     * @author EthanCao
     * @since 2025-12-19T10
     */
    public List<RouteWebFormVo> generateCombination(PromptCombinationRequest request) {
        Map<String, Set<String>> baselineInstanceMatrix = request.getBaselineInstanceMatrix();
        Map<String, Set<String>> comparisonInstanceMatrix = request.getComparisonInstanceMatrix();
        // Map<String, String> baselineNxg = findByNxgNo(baselineInstanceMatrix.keySet());
        // Map<String, String> comparisonNxg = findByNxgNo(comparisonInstanceMatrix.keySet());
        // Set<String> ngxs = baselineNxg.keySet();
        List<List<RouteWebFormVo>> finalRoutes = new ArrayList<>();

        String baselineRelease = request.getBaselineRelease();
        String comparisonRelease = request.getComparisonRelease();
        Supplier<Map<String, Set<String>>> baselineZoneMatrixSupplier;
        Supplier<Map<String, Set<String>>> comparisonZoneMatrixSupplier;
        //before in loop, get ZoneMatrix in advance
        WalkPathInfo baselineWalkPathInfo = DictConvert.INSTANCE.convert2WalkPathBaseLine(request);
        if (StringUtils.equals(baselineRelease, comparisonRelease)) {
            baselineZoneMatrixSupplier = Suppliers.memoize(() -> listZoneMatrix(baselineWalkPathInfo));
            comparisonZoneMatrixSupplier = baselineZoneMatrixSupplier;
        } else {
            WalkPathInfo comparisonWalkPathInfo = DictConvert.INSTANCE.convert2WalkPathComparison(request);
            baselineZoneMatrixSupplier = Suppliers.memoize(() -> listZoneMatrix(baselineWalkPathInfo));
            comparisonZoneMatrixSupplier = Suppliers.memoize(() -> listZoneMatrix(comparisonWalkPathInfo));
        }

        TreeSet<String> baselineEnvs = new TreeSet<>(baselineInstanceMatrix.keySet());
        TreeSet<String> comparisonEnvs = new TreeSet<>(comparisonInstanceMatrix.keySet());
        int baselineEnvSize = CollectionUtils.size(baselineEnvs);
        int comparisonEnvSize = CollectionUtils.size(comparisonEnvs);
        int envSize = Math.min(baselineEnvSize, comparisonEnvSize);
        for (int i = 0; i < envSize; i++) {
            String baselineEnv = baselineEnvs.pollLast();
            String comparisonEnv = comparisonEnvs.pollLast();
            Set<String> baselineInstances = baselineInstanceMatrix.get(baselineEnv);
            Set<String> comparisonInstances = comparisonInstanceMatrix.get(comparisonEnv);
            List<RouteWebFormVo> routeList = generateRoutes(request, baselineEnv, baselineInstances, comparisonEnv, comparisonInstances, baselineZoneMatrixSupplier, comparisonZoneMatrixSupplier);
            finalRoutes.add(routeList);
        }
        List<RouteWebFormVo> batchRoutes = finalRoutes.stream().flatMap(Collection::stream).toList();
        // fillingSelectors(request, batchRoutes); // no need
        log.info("batchRoutes:{}", JacksonUtils.toJsonString(batchRoutes));
        return batchRoutes;
        // PromptCombinationResponseVo routeForm = new PromptCombinationResponseVo();
        // routeForm.setBatch(batchRoutes);
        // return routeForm;
    }


    protected void fillingSelectors(PromptCombinationRequest request,
                                    List<RouteWebFormVo> batchRoutes) {
        if (CollectionUtils.isEmpty(batchRoutes)) {
            log.warn("WebRoutes is empty");
            return;
        }
        String baselineRelease = request.getBaselineRelease();
        String comparisonRelease = request.getComparisonRelease();
        log.info("fillingSelectors :{} and {}", baselineRelease, comparisonRelease);
        // get projectSelector
        Supplier<List<SelectedOptionVo>> projectSuppliers = Suppliers.memoize(() -> dictService.findByCondition(new DictListReqVO("idb-modules")));

        // get releaseSelector
        WalkPathInfo projectSelector = new WalkPathInfo();
        projectSelector.setProjectName(request.getProjectName());
        projectSelector.setDictType(WalkPathTypeEnum.COMMON.name());
        Supplier<List<SelectedOptionVo>> releaseSuppliers = Suppliers.memoize(() -> dictV2Service.pathWalk(projectSelector));
        // get CountrySelector
        WalkPathInfo countrySelector = new WalkPathInfo();
        countrySelector.setProjectName(request.getProjectName());
        countrySelector.setReleaseName(baselineRelease);
        countrySelector.setDictType(WalkPathTypeEnum.COMMON.name());
        Supplier<List<SelectedOptionVo>> countrySuppliers = Suppliers.memoize(() -> dictV2Service.pathWalk(countrySelector));
        // get ApplicationSelector
        WalkPathInfo appSelector = new WalkPathInfo();
        appSelector.setProjectName(request.getProjectName());
        appSelector.setReleaseName(baselineRelease);
        appSelector.setCountryCode(request.getCountryCode());
        appSelector.setDictType(WalkPathTypeEnum.APP.name());
        Supplier<List<SelectedOptionVo>> appSuppliers = Suppliers.memoize(() -> dictV2Service.pathWalk(appSelector));
        // get EnvironmentSelector
        WalkPathInfo envSelector = new WalkPathInfo();
        envSelector.setProjectName(request.getProjectName());
        envSelector.setReleaseName(baselineRelease);
        envSelector.setCountryCode(request.getCountryCode());
        envSelector.setApp(request.getApp());
        envSelector.setDictType(WalkPathTypeEnum.ENVIRONMENT.name());
        Supplier<List<SelectedOptionVo>> envSuppliers = Suppliers.memoize(() -> dictV2Service.pathWalk(envSelector));
        // get InstanceSelector
        AtomicLong index = new AtomicLong(1);
        Supplier<List<SelectedOptionVo>> baselineInstSupplier = Suppliers.memoize(() -> request.getBaselineInstanceMatrix().values().stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList());
        index.setRelease(1);
        Supplier<List<SelectedOptionVo>> comparisonInstSupplier = Suppliers.memoize(() -> request.getComparisonInstanceMatrix().values().stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList());
       /*  // get ZoneSelector
        index.setRelease(1);
        List<SelectedOptionVo> baselineZoneSelector = Optional.ofNullable(baselineZoneMatrixSupplier.get()).orElse(Map.of())
                .values().stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList();
        index.setRelease(1);
        List<SelectedOptionVo> comparisonZoneSelector = Optional.ofNullable(comparisonZoneMatrixSupplier.get()).orElse(Map.of())
                .values().stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList(); */
        for (RouteWebFormVo routeWebForm : batchRoutes) {
            List<RouteInfoWebVo> routes = routeWebForm.getRoutes();
            if (routes.size() != 2) {
                log.warn("routeWebForm is a risk");
                continue;
            }
            RouteInfoWebVo baselineRouteInfoWeb = routes.get(0);
            baselineRouteInfoWeb.setProjectNameSelector(projectSuppliers.get());
            baselineRouteInfoWeb.setReleaseSelector(releaseSuppliers.get());
            baselineRouteInfoWeb.setCountrySelector(countrySuppliers.get());
            baselineRouteInfoWeb.setAppSelector(appSuppliers.get());
            baselineRouteInfoWeb.setEnvironmentSelector(envSuppliers.get());
            baselineRouteInfoWeb.setInstanceSelector(baselineInstSupplier.get());
            // baselineRouteInfoWeb.setZoneSelector(baselineZoneSelector);

            RouteInfoWebVo comparisonRouteInfoWeb = routes.get(1);
            comparisonRouteInfoWeb.setProjectNameSelector(projectSuppliers.get());
            comparisonRouteInfoWeb.setReleaseSelector(releaseSuppliers.get());
            comparisonRouteInfoWeb.setCountrySelector(countrySuppliers.get());
            comparisonRouteInfoWeb.setAppSelector(appSuppliers.get());
            comparisonRouteInfoWeb.setEnvironmentSelector(envSuppliers.get());
            comparisonRouteInfoWeb.setInstanceSelector(comparisonInstSupplier.get());
            // comparisonRouteInfoWeb.setZoneSelector(comparisonZoneSelector);
        }
    }

    protected void fillingZoneSelectors(String baselineEnv, String comparisonEnv,
                                        Supplier<Map<String, Set<String>>> baselineZoneMatrixSupplier,
                                        Supplier<Map<String, Set<String>>> comparisonZoneMatrixSupplier,
                                        RouteWebFormVo routeWebForm) {

        AtomicLong index = new AtomicLong(1);
        // get ZoneSelector
        index.setRelease(1);
        List<SelectedOptionVo> baselineZoneSelector = Optional.ofNullable(baselineZoneMatrixSupplier.get())
                .map(m -> m.getOrDefault(baselineEnv, Set.of()))
                .stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList();
        index.setRelease(1);
        List<SelectedOptionVo> comparisonZoneSelector = Optional.ofNullable(comparisonZoneMatrixSupplier.get())
                .map(m -> m.getOrDefault(comparisonEnv, Set.of()))
                .stream()
                .flatMap(Collection::stream)
                .map(val -> new SelectedOptionVo(index.getAndIncrement(), val, val, Boolean.FALSE))
                .toList();
        List<RouteInfoWebVo> routes = routeWebForm.getRoutes();
        if (routes.size() != 2) {
            log.warn("routeWebForm is a risk");
           return;
        }
        RouteInfoWebVo baselineRouteInfoWeb = routes.get(0);
        baselineRouteInfoWeb.setZoneSelector(baselineZoneSelector);

        RouteInfoWebVo comparisonRouteInfoWeb = routes.get(1);
        comparisonRouteInfoWeb.setZoneSelector(comparisonZoneSelector);
    }

    protected List<RouteWebFormVo> generateRoutes(PromptCombinationRequest request,
                                                  String baselineEnv, Set<String> baselineInstances,
                                                  String comparisonEnv, Set<String> comparisonInstances,
                                                  Supplier<Map<String, Set<String>>> baselineZoneMatrixSupplier, Supplier<Map<String, Set<String>>> comparisonZoneMatrixSupplier) {
        log.info("generateRoutes baselineEnv:{} comparisonEnv:{} baselineInstances:{} comparisonInstances:{}", baselineEnv, comparisonEnv, baselineInstances, comparisonInstances);
        // baselineEnv = comparisonEnv ,generate line by line

        List<Pair<String, String>> combination = doMatrix(baselineInstances, comparisonInstances);
        List<RouteWebFormVo> results = new ArrayList<>();
        for (Pair<String, String> instance : combination) {
            RouteWebFormVo routeWebFormVo = new RouteWebFormVo();
            RouteInfoWebVo baselineRouteInfo = doGetRouteInfoWeb(
                    () -> DictConvert.INSTANCE.convert2WebRouteInfoBaseLine(request),
                    baselineEnv,
                    instance.getLeft(),
                    baselineZoneMatrixSupplier
            );
            RouteInfoWebVo comparisonRouteInfo = doGetRouteInfoWeb(
                    () -> DictConvert.INSTANCE.convert2WebRouteInfoComparison(request),
                    comparisonEnv,
                    instance.getRight(),
                    comparisonZoneMatrixSupplier
            );

            routeWebFormVo.setRoutes(Arrays.asList(baselineRouteInfo, comparisonRouteInfo));
            // fillingZoneSelectors
            // fillingZoneSelectors(baselineEnv, comparisonEnv, baselineZoneMatrixSupplier, comparisonZoneMatrixSupplier, routeWebFormVo)
            results.add(routeWebFormVo);

        }
        return results;
    }
    /**
     * description PromptCombinationGenerativeService
     * @author EthanCao
     * @since 8/24/25
     * 根据 左右较小的长度为步长，进行错位生成组合数据
     * ["QUA177", "QUA178"] ["PDN0051","PDN0061", "PDN0771", "PDN0781", "PDN1051", "PDN1061","PDN1771", "PDN1781"]
     */
    protected List<Pair<String, String>> doMatrix(Set<String> baselineInstances, Set<String> comparisonInstances) {
        String[] baselineInsts = baselineInstances.stream().sorted(Comparator.naturalOrder()).toArray(String[]::new);
        String[] comparisonInsts = comparisonInstances.stream().sorted(Comparator.naturalOrder()).toArray(String[]::new);
        List<Pair<String, String>> combination = new ArrayList<>();
        //group by shorter length
        boolean leftBaseLine = baselineInsts.length <= comparisonInsts.length;
        String[] shorter = leftBaseLine ? baselineInsts : comparisonInsts;
        String[] longer = leftBaseLine ? comparisonInsts : baselineInsts;

        List<String[]> groups = new ArrayList<>();
        int groupSize = shorter.length;
        for (int i = 0; i < longer.length; i += groupSize) {
            int endIndex = Math.min(i + groupSize, longer.length);
            String[] group = Arrays.copyOfRange(longer, i, endIndex);
            groups.add(group);
        }
        // combine pair
        for (String[] group : groups) {
            int pairCount = Math.min(baselineInsts.length, group.length);
            for (int i = 0; i < pairCount; i++) {
                if (leftBaseLine) {
                    combination.add(Pair.of(shorter[i], group[i]));
                } else {
                    combination.add(Pair.of(group[i], shorter[i]));
                }
            }
        }
        return combination;
    }

    RouteInfoWebVo doGetRouteInfoWeb(Supplier<RouteInfoWebVo> routeInfoSupplier, String env, String instance, Supplier<Map<String, Set<String>>> zoneMatrixSupplier) {
        RouteInfoWebVo routeInfo = routeInfoSupplier.get();
        routeInfo.setEnvironment(env);
        routeInfo.setInstance(instance);
        List<String> baselineZone = getZoneList(routeInfo, zoneMatrixSupplier);
        routeInfo.setZone(baselineZone);
        return routeInfo;
    }

    protected List<String> getZoneList(RouteInfoWebVo routeInfo, Supplier<Map<String, Set<String>>> zoneMatrixSupplier) {
        log.info("getZoneList:{}", JacksonUtils.toJsonString(routeInfo));

        //MS and CCS has respective places for zone
        Map<String, Set<String>> zoneMatrix = zoneMatrixSupplier.get();
        AppCodeEnum appCode = AppCodeEnum.toAppEnum(routeInfo.getApp());

        Set<String> propertyZonesFromEnv;
        Set<String> propertyZonesFromInstance;
        if (AppCodeEnum.MS.equals(appCode)) {
            propertyZonesFromEnv = zoneMatrix.getOrDefault(routeInfo.getEnvironment(), Set.of());
            propertyZonesFromInstance = zoneMatrix.getOrDefault(routeInfo.getInstance(), Set.of());
        } else {
            propertyZonesFromEnv = zoneMatrix.getOrDefault(routeInfo.getEnvironment(), Set.of());
            propertyZonesFromInstance = zoneMatrix.getOrDefault(routeInfo.getInstance(), Set.of());
        }
        Set<String> propertyZones = new HashSet<>(propertyZonesFromEnv);
        propertyZones.addAll(propertyZonesFromInstance);
        log.info("All appCode:{} propertyZones::{}", appCode, propertyZones);

        // if not PDN, simply use all
        List<String> result;
        List<String> finalZones = new ArrayList<>();
        if (propertyZones.size() > 1) {
            propertyZones.stream().filter(name -> !EXCLUDE_LIST.contains(name)).forEach(finalZones::add);
            Boolean isAllLowerCase = propertyZones.stream()
                    .map(str -> str.replaceAll("[^A-Za-z]", ""))
                    .map(StringUtils::isAllLowerCase)
                    .reduce((o, n) -> o && n)
                    .orElse(Boolean.FALSE);

            String identifyZone = findByZone(routeInfo.getInstance());
            if (Boolean.TRUE.equals(isAllLowerCase)) {
                identifyZone = StringUtils.lowerCase(identifyZone);
            }
            log.info("identifyZone:{}", identifyZone);
            if (propertyZones.contains(identifyZone)) {
                finalZones.add(identifyZone);
            }
        } else {
            finalZones.addAll(propertyZones);
        }

        AppCodeEnum appEnum = AppCodeEnum.toAppEnum(routeInfo.getApp());
        if (!AppCodeEnum.MS.equals(appEnum)) {
            String identifyNxg = findByNxg(routeInfo.getEnvironment());
            // add nxg
            if (propertyZones.contains(identifyNxg)) {
                finalZones.add(identifyNxg);
            }
            if (AppCodeEnum.CCS_LC.equals(appEnum) || AppCodeEnum.CCS_LC_AGGREGATOR.equals(appEnum)) {
                if (propertyZones.contains("lc")) {
                    finalZones.add("lc");
                }
            }
        }

        result = finalZones;

        log.info("calculated zones:{}", result);
        return result;
    }

    protected Map<String, String> findByNxgNo(Set<String> keys) {
        List<Pair<String, String>> pairs = keys.stream().map(key -> {
            Matcher matcher = NXG_PATTERN.matcher(key);
            if (matcher.matches()) {
                String nxgNo = matcher.group("nxgNo");
                return Pair.of(nxgNo, key);
            }
            return null;
        })
        .filter(Objects::nonNull).toList();
        return pairs.stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
    }

    protected String findByZone(String instance) {
        Matcher matcher = ZONE_PATTERN.matcher(instance);
        if (matcher.matches()) {
            String nxgNo = matcher.group("zoneNo");
            return StringUtils.equals("0", nxgNo) ? "ADC" : "TDC";
        }
        return "";
    }
    protected String findByNxg(String environment) {
        Matcher matcher = NXG_PATTERN.matcher(environment);
        String nxgNo = "1";
        if (matcher.matches()) {
            nxgNo = matcher.group("nxgNo");
        }
        log.info("findByNxg environment:{} identifyNxg:{}", environment, nxgNo);
        return StringUtils.equals("1", nxgNo) ? "nxg" : "nxg2";
    }


    public List<ConfluenceContentVO> listReports() {
        ConfluenceContentVO parentContent = confluenceRepository.getContentById(ancestorId);
        List<ConfluenceContentVO> childPages = Optional.ofNullable(parentContent)
                .map(ConfluenceContentVO::getChildren)
                .map(ChildrenVO::getPage)
                .map(PageVO::getResults)
                .orElse(Collections.emptyList());
        for (ConfluenceContentVO childPage : childPages) {
            ContentPropertyVO<AttachmentPropertyVO> contentPropertyByKey = confluenceRepository.getContentPropertyByKey(childPage.getId(), ROUTE_FORM_KEY);
            String createdDate = Optional.ofNullable(contentPropertyByKey)
                    .map(ContentPropertyVO::getValue)
                    .map(AttachmentPropertyVO::getCreatedDate)
                    .orElse(StringUtils.EMPTY);
            log.info("page:{} contains createdDate:{}", childPage.getId(), createdDate);
            childPage.setCreatedDate(createdDate);
        }
        return childPages;
    }

    public PromptMergeResponse mergeReports(PromptMergeRequest request) {
        List<String> mergeIds = request.getMergeIds();
        List<Pair<String, String>> illegalList = new ArrayList<>();
        List<Pair<String, String>> legalList = new ArrayList<>();
        // 1. validate the page contains legal metadata info
        for (String mergeId : mergeIds) {
            ContentPropertyVO<AttachmentPropertyVO> contentPropertyByKey = confluenceRepository.getContentPropertyByKey(mergeId, ROUTE_FORM_KEY);
            String metadataId = Optional.ofNullable(contentPropertyByKey)
                    .map(ContentPropertyVO::getValue)
                    .map(AttachmentPropertyVO::getMetadataId)
                    .orElse(StringUtils.EMPTY);
            if (StringUtils.isNotBlank(metadataId)) {
                legalList.add(Pair.of(mergeId, metadataId));
            } else {
                illegalList.add(Pair.of(mergeId, metadataId));
            }
        }
        log.info("legalList:{} illegalList:{}", legalList, illegalList);
        if (CollectionUtils.size(legalList) <= 1) {
            // fall back
            return new PromptMergeResponse(legalList.stream().map(Pair::getLeft).toList(), illegalList.stream().map(Pair::getLeft).toList());
        }
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("moveAttachmentsConfluence");
        // 2. generate draft confluence page
        String fileName = DateTimeUtil.getTimeStr() + " Configs Report";
        ConfluenceContentVO draftConfluencePage = confluenceRepository.generateDraftConfluencePage(projectId, ancestorId, fileName, "<p>Draft page data loading.</p>");
        String draftPageId = Optional.ofNullable(draftConfluencePage)
                .map(ConfluenceContentVO::getId).orElseThrow(() -> new ServiceException(500, "Generate draft page failed"));
        // 3. list all attachments and move attachments into new page
        List<RouteFormVo> routeFormAll = new ArrayList<>();
        for (Pair<String, String> pagePair : legalList) {
            byte[] metadata = confluenceRepository.downloadAttachment(pagePair.getLeft(), ROUTE_FORM_KEY + ".json");
            BatchFormVo batchFormMetadata = JacksonUtils.parseObject(metadata, BatchFormVo.class);
            routeFormAll.addAll(batchFormMetadata.getBatch());

            AttachmentContentVO attachments = confluenceRepository.getAllAttachments(pagePair.getLeft());
            Optional.ofNullable(attachments)
                    .map(AttachmentContentVO::getResults)
                    .orElse(Collections.emptyList())
                    .stream()
                    .map(ResultsVO::getId)
                    .filter(attachId -> !StringUtils.equals(attachId, pagePair.getRight())) // filter out RouteFormMetadata
                    .forEach(result -> {
                        boolean success = confluenceRepository.moveAttachment(pagePair.getLeft(), result, draftPageId);
                        log.info("moveAttachment:{} success:{}", result, success);
                    });
        }
        stopWatch.stop();
        stopWatch.start("combineHtmlContent");
        // 3. combine html content
        stopWatch.stop();

        List<RouteInfoGroupingVO> routeInfoGrouping = reportGenerationService.groupingForTemplate(routeFormAll);
        // update confluence page content 移动之后需要知道attachment的title即可
        String htmlTemplate = reportGenerationService.renderHtmlTemplate(routeInfoGrouping);

        // ContentProperty 存在大小限制，需要转为json元数据 然后再合并
        // RouteFormMetaData.json
        BatchFormVo batchFormVo = new BatchFormVo();
        batchFormVo.setBatch(routeFormAll);
        SimpleAttachmentContentVO simpleAttachmentContent = reportGenerationService.uploadFile2Cloud(draftPageId, new ByteArrayResource(JacksonUtils.toJsonByte(batchFormVo)) {
            @Override
            public String getFilename() {
                return ROUTE_FORM_KEY + ".json";
            }
        });
        ContentPropertyVO<AttachmentPropertyVO> contentProperty = ConfluenceConvert.INSTANCE.buildContentProperty(draftPageId, ROUTE_FORM_KEY, simpleAttachmentContent.getId(), DateTimeUtil.getTimeStr());
        confluenceRepository.createContentProperty(draftPageId, contentProperty);
        ConfluenceContentVO finalConfluenceContent = confluenceRepository.updateDraftConfluencePage(draftConfluencePage, htmlTemplate);
        String generatedLink = Optional.ofNullable(finalConfluenceContent)
                .map(ConfluenceContentVO::getLinks)
                .map(link -> link.getBase() + link.getWebui()).orElse(StringUtils.EMPTY);
        log.info(">>combine confluence page:{}", generatedLink);
        stopWatch.start("removeOldPages");
        // 4. remove the existing pages
        for (Pair<String, String> pagePair : legalList) {
            boolean success = confluenceRepository.deleteContent(pagePair.getLeft());
            log.info("removeOldPages:{} success:{}", pagePair.getLeft(), success);
        }
        stopWatch.stop();
        log.info(">>stopWatch for Confluence Merge<<{}", stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        return new PromptMergeResponse(legalList.stream().map(Pair::getLeft).toList(), illegalList.stream().map(Pair::getLeft).toList());
    }

}
