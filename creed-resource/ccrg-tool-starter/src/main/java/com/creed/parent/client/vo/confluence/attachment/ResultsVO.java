package com.creed.parent.client.vo.confluence.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.creed.parent.client.vo.confluence.content.ExpandableVO;
import com.creed.parent.client.vo.confluence.content.ExtensionsVO;
import com.creed.parent.client.vo.confluence.content.LinksVO;
import com.creed.parent.client.vo.confluence.content.VersionVO;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2025-11-14T19
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResultsVO {
    private String id;
    private String type;
    private String status;
    private String title;
    private VersionVO version;
    private int position;
    private ContainerVO container;
    private MetadataVO metadata;
    private ExtensionsVO extensions;
    @JsonProperty("_links")
    private LinksVO links;
    @JsonProperty("_expandable")
    private ExpandableVO expandable;
}
