package com.creed.parent.controller.metrics.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.creed.parent.constant.ServerMetricsType;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-23T17
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@RequiredArgsConstructor(staticName = "of")
public class BasicServerInfoVo {
    @NonNull
    private String clusterName;
    @NonNull
    private String serverName;
    @NonNull
    private String serverHost;
    @NonNull
    private Integer serverPort;
    @NonNull
    private String environment;
    @NonNull
    private String region;
    @NonNull
    private ServerMetricsType type;

    private Boolean enabled;

    private Instant createdAt;
}
