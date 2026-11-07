package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/2/24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteInfoVo {
    private String releaseName;
    private String projectName;
    private String countryCode;
    private String app;
    private String environment;
    private String instance;
    @Deprecated
    private String node = StringUtils.EMPTY;
    private String zone;
    private String module;

    public RouteInfoVo(String releaseName, String countryCode) {
        this.releaseName = releaseName;
        this.countryCode = countryCode;
    }
}
