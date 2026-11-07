package com.creed.parent.service.report.impl;

import com.creed.parent.ccrg.SourceData;
import com.creed.parent.constant.ReportTypeEnum;
import com.creed.parent.controller.vo.EnvironmentConfigurationVo;
import com.creed.parent.controller.vo.PropertiesVo;
import com.creed.parent.controller.vo.RouteFormVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import com.creed.parent.convert.PropertySourceConvert;
import com.creed.parent.definition.RouteDefinition;
import com.creed.parent.exception.ServiceException;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.model.ReportDetails;
import com.creed.parent.service.ColorfulCellListener;
import com.creed.parent.service.PropertiesService;
import com.creed.parent.service.report.ReportGenerator;
import com.creed.parent.service.report.ReportMetadataInfo;
import com.creed.parent.utils.StrUtils;
import com.creed.parent.utils.json.JacksonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jxls.area.Area;
import org.jxls.builder.xls.XlsCommentAreaBuilder;
import org.jxls.common.AreaRef;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T18
 */
@Service("xlsxReportGenerator")
@Slf4j
public class XlsxReportGeneratorImpl implements ReportGenerator {
    @Resource
    @Lazy
    private PropertiesService propertiesService;

    @Resource
    ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void generateReport(List<EnvironmentConfigurationVo> environmentConfiguration, ReportTypeEnum reportType, Locale locale, ReportMetadataInfo metadata, OutputStream os) {
        if (CollectionUtils.isEmpty(environmentConfiguration)) {
            log.warn("!!environmentConfiguration is empty!!");
            return;
        }
        String connectionId = metadata.getConnectionId();
        applicationEventPublisher.publishEvent(new EmitterEvent(this, connectionId, EmitterEventEnum.PROPERTIES_COUNTING));

        List<SourceData> sourceDataList = Optional.of(environmentConfiguration).orElse(Collections.emptyList())
                .stream().map(EnvironmentConfigurationVo::getProperties)
                .map(PropertySourceConvert.INSTANCE::convert)
                .flatMap(Collection::stream)
                .toList();

        Map<Integer, List<SourceData>> activeSources = sourceDataList.stream().filter(SourceData::isActive)
                .collect(Collectors.groupingBy(SourceData::getSource, Collectors.mapping(Function.identity(), Collectors.toList())));
        Map<String, SourceData> source1Mapping = activeSources.get(1).stream().collect(Collectors.toMap(entry -> entry.getKey() + ":" + entry.getValue(), Function.identity()));
        Map<String, SourceData> source2Mapping = activeSources.get(2).stream().collect(Collectors.toMap(entry -> entry.getKey() + ":" + entry.getValue(), Function.identity()));
        Collection<String> matchingKeys = CollectionUtils.intersection(source1Mapping.keySet(), source2Mapping.keySet());
        Map<String, List<SourceData>> matchingDatamap = matchingKeys.stream().collect(Collectors.toMap(key -> StrUtils.substringBeforeLast(key, ":"), key -> Arrays.asList(source1Mapping.getOrDefault(key, new SourceData()), source2Mapping.getOrDefault(key, new SourceData()))));
        // rest is noneMatching values

        Map<String, SourceData> nonMatching4Source1 = activeSources.get(1).stream().filter(sd -> !matchingKeys.contains(sd.getKey() + ":" + sd.getValue()))
                .collect(Collectors.toMap(SourceData::getKey, Function.identity(), (o, n) -> o));// 16230 207 = 16437
        Map<String, SourceData> nonMatching4Source2 = activeSources.get(2).stream().filter(sd -> !matchingKeys.contains(sd.getKey() + ":" + sd.getValue()))
                .collect(Collectors.toMap(SourceData::getKey, Function.identity(), (o, n) -> o));// 16230 182 = 16412
        HashSet<String> nonMatchingKeys = new HashSet<>(nonMatching4Source1.keySet());
        nonMatchingKeys.addAll(nonMatching4Source2.keySet());
        Map<String, List<SourceData>> nonMatchingDatamap = nonMatchingKeys.stream().collect(Collectors.toMap(key -> key, key -> Arrays.asList(nonMatching4Source1.getOrDefault(key, new SourceData()), nonMatching4Source2.getOrDefault(key, new SourceData()))));

        String reference = Optional.of(metadata)
                .map(ReportMetadataInfo::getRouteFormVo)
                .filter(f -> Boolean.TRUE.equals(f.getCompareAll()))
                .map(RouteFormVo::getBaseRelease)
                .orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(reference)) {
            log.info("reference branch :{}", reference);
            List<RouteInfoVo> routes = Optional.of(metadata).map(ReportMetadataInfo::getRouteFormVo).map(RouteFormVo::getRoutes).orElse(Collections.emptyList());
            String projectName = routes.stream().map(RouteInfoVo::getProjectName).findAny().orElseThrow(() -> new RuntimeException("Failed to find projectName"));
            propertiesService.enrichWithMetadata(reference, projectName, matchingDatamap.values());
            propertiesService.enrichWithMetadata(reference, projectName, nonMatchingDatamap.values());
        }

        // log.debug("matchingSourceData with Metadata [{}]: {}", matchingDatamap.values().stream().flatMap(Collection::stream).filter(data -> Objects.nonNull(data.getMetadata())).collect(Collectors.toSet()),
        //         matchingDatamap.values().stream().flatMap(Collection::stream).filter(data -> Objects.nonNull(data.getMetadata())).collect(Collectors.toSet()).size());
        // log.debug("nonMatchingSourceData with Metadata [{}]: {}", nonMatchingDatamap.values().stream().flatMap(Collection::stream).filter(data -> Objects.nonNull(data.getMetadata())).collect(Collectors.toSet()),
        //         matchingDatamap.values().stream().flatMap(Collection::stream).filter(data -> Objects.nonNull(data.getMetadata())).collect(Collectors.toSet()).size());

        log.debug("matchingDatamap: {}", JacksonUtils.toJsonString(matchingDatamap));
        log.debug("nonMatchingDatamap: {}", JacksonUtils.toJsonString(nonMatchingDatamap));

        try {
            Map<String, Object> context = new HashMap<>();
            List<SourceData> matchingElementHeader = matchingDatamap.entrySet().stream().findFirst().map(Map.Entry::getValue).orElse(Collections.emptyList());
            context.put("matchingDatamapHeader", matchingElementHeader);
            context.put("matchingDatamap", matchingDatamap);
            context.put("matchingDataStatus", getStatusTranslation(matchingDatamap));
            List<SourceData> noneMatchingElementHeader = nonMatchingDatamap.entrySet().stream().findFirst().map(Map.Entry::getValue).orElse(Collections.emptyList());
            context.put("nonMatchingDatamapHeader", noneMatchingElementHeader);
            context.put("nonMatchingDatamap", nonMatchingDatamap);
            context.put("nonMatchingDataStatus", getStatusTranslation(nonMatchingDatamap));

            List<ReportDetails> reportDetails = builReportDetails(environmentConfiguration);
            context.put("reportDetails", reportDetails);

            try (InputStream templateStream = new ClassPathResource("templates/report_generation_template_3.xlsx").getInputStream()) {
                if (templateStream == null)
                    throw new IllegalArgumentException("Template file not found");

                JxlsPoiTemplateFillerBuilder.newInstance().withTemplate(templateStream)
                        .withAreaBuilder(new XlsCommentAreaBuilder() {
                            @Override
                            public List<Area> build(Transformer transformer, boolean ctc) {
                                List<Area> areas = super.build(transformer, ctc);
                                areas.stream().map(Area::getAreaRef)
                                        .map(AreaRef::getSheetName)
                                        .map("%s!A11:D11"::formatted)
                                        .forEach(refVal -> addAreaListener(new ColorfulCellListener(transformer), new AreaRef(refVal), areas));

                                // https://jxls.sourceforge.net/builder.html
                                return areas;
                            }
                        })
                        .build()
                        .fill(context, () -> os);
                // JxlsPoiTemplateFillerBuilder.newInstance().withTemplate(templateStream)
                //         .build()
                //         .fill(context, () -> outputStream);
            }
        } catch (IOException e) {
            log.error("xlsx generate failed", e);
            throw new ServiceException(500, ExceptionUtils.getMessage(e));
        }
    }

    private Map<String, String> getStatusTranslation(Map<String, List<SourceData>> nonMatchingDatamap) {
        var statusMap = new HashMap<String, String>();
        for (Map.Entry<String, List<SourceData>> entry : nonMatchingDatamap.entrySet()) {
            List<SourceData> sourceData = entry.getValue();
            String status = sourceData.stream().map(SourceData::getMetadata)
                    .map(meta -> ObjectUtils.defaultIfNull(meta, new SourceData.Metadata()))
                    .map(meta -> StringUtils.defaultString(meta.getStatus(), "EQUAL"))
                    .collect(Collectors.joining(":"));
            statusMap.put(entry.getKey(), status);
        }
        return statusMap;
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

    private List<String> listAllFiles(EnvironmentConfigurationVo vo) {
        return Optional.ofNullable(vo).map(EnvironmentConfigurationVo::getProperties).orElse(Collections.emptyList())
                .stream().map(PropertiesVo::getOrigin)
                .map(origin -> StringUtils.substringBetween(origin, "[file:", "]"))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public boolean supports(ReportTypeEnum reportType) {
        return ReportTypeEnum.XLSX.equals(reportType);
    }
}
