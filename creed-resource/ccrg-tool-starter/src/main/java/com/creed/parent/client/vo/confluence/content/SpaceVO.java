package com.creed.parent.client.vo.confluence.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class SpaceVO {
    private int id;
    private String key;
    private String name;
    private String status;
    private String type;
    @JsonProperty("_links")
    private LinksVO links;
    @JsonProperty("_expandable")
    private ExpandableVO expandable;
}
