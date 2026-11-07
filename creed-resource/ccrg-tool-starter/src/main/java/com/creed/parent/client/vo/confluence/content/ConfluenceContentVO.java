package com.creed.parent.client.vo.confluence.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-11-14T18
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfluenceContentVO {

    private String id;
    private String type;
    private String status;
    private String title;
    private String createdDate;
    private SpaceVO space;
    // private HistoryVO history
    private List<ConfluenceContentVO> ancestors;
    private VersionVO version;
    private int position;
    private ExtensionsVO extensions;
    @JsonProperty("_links")
    private LinksVO links;
    @JsonProperty("_expandable")
    private ExpandableVO expandable;
    private BodyVO body;
    private ChildrenVO children;
}
