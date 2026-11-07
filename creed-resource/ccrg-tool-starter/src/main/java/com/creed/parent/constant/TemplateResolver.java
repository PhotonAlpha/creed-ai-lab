package com.creed.parent.constant;

import org.apache.commons.lang3.StringUtils;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/28/24
 */
@FunctionalInterface
public interface TemplateResolver<T, R, V> {
    String CONFIG_LOCATION_PATTERN = "fragments/%s/%s/spring_config_location_fragment";
    String CONFIG_ADDITIONAL_LOCATION_PATTERN = "fragments/%s/%s/spring_config_additional_location_fragment";
    String CONFIG_PROFILES_INCLUDE_PATTERN = "fragments/%s/%s/spring_profiles_include_fragment";
    V apply(T t, R r);

    static TemplateResolver<String, String, String> resolveConfigLocation() {
        return (project, appCode) -> {
            String path = StringUtils.lowerCase(appCode);
            return CONFIG_LOCATION_PATTERN.formatted(project, path);
        };
    }
    static TemplateResolver<String, String, String> resolveConfigAdditionalLocation() {
        return (project, appCode) -> {
            String path = StringUtils.lowerCase(appCode);
            return CONFIG_ADDITIONAL_LOCATION_PATTERN.formatted(project, path);
        };
    }
    static TemplateResolver<String, String, String> resolveProfilesInclude() {
        return (project, appCode) -> {
            String path = StringUtils.lowerCase(appCode);
            return CONFIG_PROFILES_INCLUDE_PATTERN.formatted(project, path);
        };
    }
}
