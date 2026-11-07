package com.creed.parent.definition;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/28/24
 */
@Data
public class RouteDefinition implements Serializable {
    private String projectName;
    private String releaseName;
    private String profiles;
    private String countryCode;
    private String app;
    private String environment;
    private String instance;
    private String zone;
    private String node;
    private String module;
    @Deprecated
    private String application;
    private List<String> springProfilesInclude;
}
