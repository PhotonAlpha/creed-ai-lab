package com.creed.parent.client.vo.confluence.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.creed.parent.client.vo.confluence.content.LinksVO;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-15T15
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentPropertyResourcesVO<T> {
    private List<ContentPropertyVO<T>> results;
    private int start;
    private int limit;
    private int size;
    @JsonProperty("_links")
    private LinksVO links;
}
