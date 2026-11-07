package com.creed.parent.client.vo.confluence.attachment;

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
 * @date 2025-11-14T19
 */
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttachmentContentVO {
    private List<ResultsVO> results;
    private int start;
    private int limit;
    private int size;
    @JsonProperty("_links")
    private LinksVO links;
}
