package com.creed.parent.controller.metrics.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-23T17
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerMetricsVo extends BasicServerInfoVo {
    private Long id;

    private Long totalMemory;

    private Long usedMemory;

    private Long freeMemory;

    private Double cpuUsage;

    private Long threadCount;

    private Long deamonThreadCount;

    private Long heapUsed;

    private Long heapMax;

    private Boolean status;

    private Instant checkTime;

}
