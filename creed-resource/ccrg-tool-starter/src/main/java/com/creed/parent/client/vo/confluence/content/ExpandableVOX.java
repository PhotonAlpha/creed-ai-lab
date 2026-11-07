package com.creed.parent.client.vo.confluence.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description ccrg-tool
 * @author EthanCao
 * @date 2025-11-14T18
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExpandableVOX {
    private String editor;
    private String view;
    @JsonProperty("export_view")
    private String exportView;
    @JsonProperty("styled_view")
    private String styledView;
    @JsonProperty("anonymous_export_view")
    private String anonymousExportView;
}
