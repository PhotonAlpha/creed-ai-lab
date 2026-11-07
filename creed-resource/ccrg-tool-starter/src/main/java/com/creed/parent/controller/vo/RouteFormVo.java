package com.creed.parent.controller.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.client.vo.confluence.attachment.SimpleAttachmentContentVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/2/24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteFormVo {
    private List<RouteInfoVo> routes;
    private String baseRelease; // Root  Release18.xxx
    private Boolean compareAll;

    private SimpleAttachmentContentVO simpleAttachmentContent;

    public RouteFormVo(List<RouteInfoVo> routes, String baseRelease) {
        this.routes = routes;
        this.baseRelease = baseRelease;
    }
}
