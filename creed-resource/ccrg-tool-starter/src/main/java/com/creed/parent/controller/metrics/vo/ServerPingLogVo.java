package com.creed.parent.controller.metrics.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/3/24
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerPingLogVo extends BasicServerInfoVo {
    private Long id;
    private Boolean status;
    private Long latencyMs;
    private Double packetLoss;
    private Instant checkTime = Instant.now();
}
