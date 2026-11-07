package com.creed.parent.controller.prompt.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.controller.vo.SelectedOptionVo;
import lombok.Data;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/19/25
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteInfoWebVo {
    private String projectName;
    private List<SelectedOptionVo> projectNameSelector;
    private String releaseName;
    private List<SelectedOptionVo> releaseSelector;
    private String countryCode;
    private List<SelectedOptionVo> countrySelector;
    private String app;
    private List<SelectedOptionVo> appSelector;
    private String environment;
    private List<SelectedOptionVo> environmentSelector;
    private String instance;
    private List<SelectedOptionVo> instanceSelector;
    private List<String> zone;
    private List<SelectedOptionVo> zoneSelector;
}
