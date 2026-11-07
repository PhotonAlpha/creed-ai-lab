package com.creed.parent.client.vo.confluence.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-11-14T18
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExpandableVO {
    private String container;
    private String metadata;
    private String operations;
    private String children;
    private String restrictions;
    private String ancestors;
    private String body;
    private String descendants;
    private String icon;
    private String description;
    private String retentionPolicy;
    private String homepage;
    private String status;
    private String content;
}
