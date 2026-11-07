package com.creed.parent.client.vo.confluence.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.creed.parent.client.vo.confluence.content.ConfluenceContentVO;
import com.creed.parent.client.vo.confluence.content.ExpandableVO;
import com.creed.parent.client.vo.confluence.content.LinksVO;
import com.creed.parent.client.vo.confluence.content.VersionVO;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-15T15
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentPropertyVO<T> {
    private String id;
    private String key;
    private T value;
    private ConfluenceContentVO content;
    private VersionVO version;
    @JsonProperty("_links")
    private LinksVO links;
    @JsonProperty("_expandable")
    private ExpandableVO expandable;
}
