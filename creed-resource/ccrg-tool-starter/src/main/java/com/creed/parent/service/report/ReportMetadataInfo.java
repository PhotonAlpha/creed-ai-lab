package com.creed.parent.service.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.controller.vo.RouteFormVo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-01-27T23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportMetadataInfo {
    private String connectionId;
    private RouteFormVo routeFormVo;
}
