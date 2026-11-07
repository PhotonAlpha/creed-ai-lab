package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/27/24
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PropertiesVo {
    private String propertyName;
    private Object propertyValue;
    private String origin;
    private Integer source = 0;
    private String sourceName;
    private Boolean active = Boolean.FALSE;
}
