package com.creed.parent.controller.prompt.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

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
public class PromptCombinationRequest {
    private Boolean compareAll;
    private String baseRelease;
    @NotNull
    private Map<String, Set<String>> baselineInstanceMatrix;
    @NotNull
    private Map<String, Set<String>> comparisonInstanceMatrix;
    @NotNull
    private String projectName;
    @NotNull
    private String baselineRelease;
    @NotNull
    private String comparisonRelease;
    @NotNull
    private String countryCode;
    @NotNull
    private String app;
    private String baselineValue;
    private String comparisonValue;
}
