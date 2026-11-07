package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/30/24
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApplicationSettingsVo {
    private String id;
    private String profiles;
    private String countryCode;
    private String environment;
    private String instance;
    private String zone;
    private String application;
}
