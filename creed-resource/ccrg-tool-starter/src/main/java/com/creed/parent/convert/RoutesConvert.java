package com.creed.parent.convert;

import com.creed.parent.controller.prompt.vo.RouteInfoWebVo;
import com.creed.parent.controller.prompt.vo.RouteWebFormVo;
import com.creed.parent.controller.vo.RouteFormVo;
import com.creed.parent.controller.vo.RouteInfoVo;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-12-19T11
 */
@Mapper
public interface RoutesConvert {
    Logger log = LoggerFactory.getLogger(RoutesConvert.class);
    RoutesConvert INSTANCE = Mappers.getMapper(RoutesConvert.class);
    @Mapping(target = "zone", ignore = true)
    RouteInfoVo copy0(RouteInfoWebVo info);
    @AfterMapping
    default void afterMapping(@MappingTarget RouteInfoVo routeInfoVo, RouteInfoWebVo info) {
        String zonesArrayAppending = info.getZone().stream().distinct().collect(Collectors.joining(","));
        if (StringUtils.isNotBlank(zonesArrayAppending)) {
            log.warn("zonesArrayAppending:{}", zonesArrayAppending);
            routeInfoVo.setZone(zonesArrayAppending);
        } else {
            log.warn("zonesArrayAppending is empty:{}", zonesArrayAppending);
        }
    }

    List<RouteInfoVo> copy0(List<RouteInfoWebVo> info);

    RouteFormVo copy(RouteWebFormVo info);

}
