package com.creed.parent.convert;


import com.creed.parent.ccrg.SourceData;
import com.creed.parent.constant.AppCodeEnum;
import com.creed.parent.controller.vo.PropertiesVo;
import com.creed.parent.controller.vo.RouteFormVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import com.creed.parent.controller.vo.SelectedOptionVo;
import com.creed.parent.controller.vo.snapshot.PropertyEntry;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.definition.RouteDefinition;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/27/24
 */
@Mapper
public interface PropertySourceConvert {
    PropertySourceConvert INSTANCE = Mappers.getMapper(PropertySourceConvert.class);

    @Mapping(source = "descriptor.value", target = "propertyValue")
    @Mapping(source = "descriptor.origin", target = "origin", defaultExpression = "java(sourceName)")
    @Mapping(source = "propertyName", target = "propertyName")
    @Mapping(source = "sourceName", target = "sourceName")
    @Mapping(source = "source", target = "source")
    PropertiesVo convert(EnvironmentEndpoint.PropertyValueDescriptor descriptor, String propertyName, String sourceName, int source);

    @Mapping(source = "propertyEntry.resolved", target = "propertyValue")
    @Mapping(source = "propertyEntry.origin", target = "origin", defaultExpression = "java(propertyEntry.source())")
    @Mapping(source = "propertyEntry.name", target = "propertyName")
    @Mapping(source = "propertyEntry.source", target = "sourceName")
    @Mapping(source = "source", target = "source")
    @Mapping(target = "active", expression = "java(java.lang.Boolean.TRUE)")
    PropertiesVo convert(PropertyEntry propertyEntry, int source);


    @Mapping(source = "propertyName", target = "key")
    @Mapping(expression = "java(String.valueOf(vo.getPropertyValue()))", target = "value")
    @Mapping(source = "source", target = "source")
    @Mapping(source = "origin", target = "origin")
    @Mapping(source = "active", target = "active")
    SourceData convert(PropertiesVo vo);
    List<SourceData> convert(List<PropertiesVo> list);


    @Mapping(source = "module", target = "application")
    @Mapping(source = "module", target = "module")
    @Mapping(source = "releaseName", target = "releaseName")
    RouteDefinition convert(RouteInfoVo vo);

    @AfterMapping
    default void afterMapping(RouteInfoVo vo, @MappingTarget RouteDefinition target) {
        String envProfileStr;
        if (StringUtils.containsAnyIgnoreCase(vo.getEnvironment(), "PDN")) {
            envProfileStr = "prod";
        } else if(StringUtils.containsAnyIgnoreCase(vo.getEnvironment(), "NFT")) {
            envProfileStr = "nft";
        } else if(StringUtils.containsAnyIgnoreCase(vo.getEnvironment(), "dr")) {
            envProfileStr = "prod-dr";
        } else if(StringUtils.containsAnyIgnoreCase(vo.getEnvironment(), "ITG")) {
            envProfileStr = "sit";
        } else {
            envProfileStr = "uat";
        }
        if (StringUtils.equalsIgnoreCase(vo.getProjectName(), "idbproxy")) {//TODO
            target.setProfiles(envProfileStr);
            return;
        }
        if (StringUtils.containsIgnoreCase(vo.getApp(), AppCodeEnum.MS.name())) {
            target.setProfiles(envProfileStr);
        } else if (StringUtils.equalsIgnoreCase(vo.getApp(), AppCodeEnum.CCS.name())) {
            target.setProfiles("%s,bau,%s,%s".formatted(envProfileStr, StringUtils.lowerCase(vo.getZone()), StringUtils.lowerCase(vo.getNode())));
        } else if (StringUtils.equalsIgnoreCase(vo.getApp(), AppCodeEnum.CCS_AGGREGATOR.name())) {
            target.setProfiles("%s,bau,aggregator,%s,%s".formatted(envProfileStr, StringUtils.lowerCase(vo.getZone()), StringUtils.lowerCase(vo.getNode())));
        } else if (StringUtils.equalsIgnoreCase(vo.getApp(), AppCodeEnum.CCS_LC.name())) {
            target.setProfiles("%s,bau,%s,lc,%s".formatted(envProfileStr, StringUtils.lowerCase(vo.getZone()), StringUtils.lowerCase(vo.getNode())));
        } else if (StringUtils.equalsIgnoreCase(vo.getApp(), AppCodeEnum.CCS_LC_AGGREGATOR.name())) {
            target.setProfiles("lc,lc-aggregator,%s,bau,%s,%s".formatted(envProfileStr, StringUtils.lowerCase(vo.getZone()), StringUtils.lowerCase(vo.getNode())));
        } else {
            target.setProfiles(envProfileStr);
        }
        //distinct
        String finalProfiles = String.join(",", Stream.of(StringUtils.split(target.getProfiles(), ","))
                .collect(Collectors.toSet()));
        target.setProfiles(finalProfiles);

    }

    @Mapping(source = "id", target = "id")
    @Mapping(source = "name", target = "name")
    SelectedOptionVo convert0(SystemDicts systemDicts);
    List<SelectedOptionVo> convert0(List<SystemDicts> systemDicts);
    RouteInfoVo convert1(RouteInfoVo vo);

    SystemDicts copy(SystemDicts dict);
    List<SystemDicts> copy(List<SystemDicts> dict);

    RouteFormVo copy0(RouteFormVo info);
    List<RouteInfoVo> convert1(List<RouteInfoVo> infos);
}
