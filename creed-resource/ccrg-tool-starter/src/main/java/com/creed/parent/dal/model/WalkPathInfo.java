
package com.creed.parent.dal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
public class WalkPathInfo {
    private String dictType;
    private String releaseName;
    private String projectName;
    private String countryCode;
    private String app;
    private String environment;
    private String instance;
    private String node;
    private String zone;
    private String module;
}
