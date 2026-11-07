package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 11/6/25
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
public class PropertyReviewOverviewVo {
    private List<PropertyReviewVo> nonMatchingProperties;
    private List<PropertyReviewVo> matchingProperties;
    private String customFilename;
}
