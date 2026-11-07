package com.creed.parent.controller.prompt.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-12-31T22
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfluencePageInfo {
    private String id;
    private String type;
    private String title;
    private String base;
    private String tinyui;
    private String webui;
    private List<ConfluencePageInfo> children;
}
