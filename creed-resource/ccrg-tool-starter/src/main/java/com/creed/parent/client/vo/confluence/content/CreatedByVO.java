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
public class CreatedByVO {
    private String type;
    private String username;
    private String userKey;
    private ProfilePictureVO profilePicture;
    private String displayName;
    @JsonProperty("_links")
    private LinksVO links;
    @JsonProperty("_expandable")
    private SpaceVO expandable;
}
