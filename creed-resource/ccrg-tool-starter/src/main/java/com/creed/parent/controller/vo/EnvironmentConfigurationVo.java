package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.definition.RouteDefinition;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/27/24
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentConfigurationVo {
    private String activeProfile;
    private Integer source;
    private RouteDefinition routeDefinition;
    private List<PropertiesVo> properties;
    private Exception exception;

    public EnvironmentConfigurationVo(String activeProfile, List<PropertiesVo> properties) {
        this.activeProfile = activeProfile;
        this.properties = properties;
    }
}
