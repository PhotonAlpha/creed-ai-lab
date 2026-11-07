package com.creed.parent.convert;


import com.creed.parent.controller.dict.vo.DictConfigVO;
import com.creed.parent.controller.dict.vo.DictRespVO;
import com.creed.parent.controller.dict.vo.DictSaveReqVO;
import com.creed.parent.controller.dict.vo.DictSimpleRespVO;
import com.creed.parent.controller.dict.vo.ExtraTypeRespVO;
import com.creed.parent.controller.prompt.vo.PromptCombinationRequest;
import com.creed.parent.controller.prompt.vo.RouteInfoWebVo;
import com.creed.parent.dal.entity.SystemDicts;
import com.creed.parent.dal.entity.SystemExtraTypes;
import com.creed.parent.dal.model.WalkPathInfo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/27/24
 */
@Mapper
public interface DictConvert {
    DictConvert INSTANCE = Mappers.getMapper(DictConvert.class);

    @Mapping(source = "node.id", target = "nodeId")
    @Mapping(source = "zone.id", target = "zoneId")
    DictRespVO convert2WebRouteInfo(SystemDicts entity);
    SystemDicts convert2WebRouteInfo(DictSaveReqVO vo);
    List<DictRespVO> convert2WebRouteInfo(List<SystemDicts> list);
    DictSimpleRespVO convert0(SystemDicts entity);
    List<DictSimpleRespVO> convert0(List<SystemDicts> list);

    void update(DictSaveReqVO updateReqVO, @MappingTarget SystemDicts dict);

    ExtraTypeRespVO convertSystemExtraTypes(SystemExtraTypes extraTypes);
    List<ExtraTypeRespVO> convertSystemExtraTypes(List<SystemExtraTypes> list);

    DictSaveReqVO convertVo(DictConfigVO dictConfig);


    WalkPathInfo convert2WebRouteInfo(WalkPathInfo walkPathInfo);

    @Mapping(source = "baselineRelease", target = "releaseName")
    RouteInfoWebVo convert2WebRouteInfoBaseLine(PromptCombinationRequest request);

    @Mapping(source = "comparisonRelease", target = "releaseName")
    RouteInfoWebVo convert2WebRouteInfoComparison(PromptCombinationRequest request);

    @Mapping(source = "baselineRelease", target = "releaseName")
    WalkPathInfo convert2WalkPathBaseLine(PromptCombinationRequest request);

    @Mapping(source = "comparisonRelease", target = "releaseName")
    WalkPathInfo convert2WalkPathComparison(PromptCombinationRequest request);

    @Mapping(target = "zone", ignore = true)
    WalkPathInfo convertPath2WalkPath(RouteInfoWebVo pathInfo);

    @Mapping(source = "projectName", target = "projectName")
    @Mapping(source = "releaseName", target = "releaseName")
    WalkPathInfo build(String projectName, String releaseName);
}
