package com.creed.parent.controller.prompt.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/14/25
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptCombinationResponseVo {
    private List<RouteWebFormVo> batch;
    private Map<String, Map<String, Set<String>>> baselineInstanceMatrix;
    private Map<String, Map<String, Set<String>>> comparisonInstanceMatrix;
    private String baselineRelease;
    private String comparisonRelease;
}
