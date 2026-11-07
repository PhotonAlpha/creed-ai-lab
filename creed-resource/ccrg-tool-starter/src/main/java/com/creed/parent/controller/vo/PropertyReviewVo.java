package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.ccrg.SourceData;
import com.creed.parent.model.ReportDetails;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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
public class PropertyReviewVo {
    private String filePath;
    private String contentOrigin;
    private String content;
    private ReportDetails reportDetails;
    private List<PropertiesVo> missingProperties;
    // get all change files
    private List<String> changeFiles;
    @Deprecated
    private Map<String, SourceData> metaData;
}
