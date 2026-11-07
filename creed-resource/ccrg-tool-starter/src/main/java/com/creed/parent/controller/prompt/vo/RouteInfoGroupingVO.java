package com.creed.parent.controller.prompt.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.controller.vo.RouteFormVo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.TreeMap;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-11-19T16
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
public class RouteInfoGroupingVO {
    private String projectName;
    private String sourceArtifactoryUrl;
    private String comparisonArtifactoryUrl;
    private String sourceReleaseName;
    private String comparisonReleaseName;
    private TreeMap<String, TreeMap<String, List<RouteFormVo>>> routeFormGrouping;

    public RouteInfoGroupingVO(String projectName, String sourceReleaseName, String comparisonReleaseName, TreeMap<String, TreeMap<String, List<RouteFormVo>>> routeFormGrouping) {
        this.projectName = projectName;
        this.sourceReleaseName = sourceReleaseName;
        this.comparisonReleaseName = comparisonReleaseName;
        this.routeFormGrouping = routeFormGrouping;
    }
}
