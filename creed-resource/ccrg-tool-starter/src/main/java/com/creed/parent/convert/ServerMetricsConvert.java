package com.creed.parent.convert;

import com.creed.parent.config.InstanceProperties;
import com.creed.parent.constant.ServerMetricsType;
import com.creed.parent.controller.metrics.vo.BasicServerInfoVo;
import com.creed.parent.controller.metrics.vo.ServerMetricsVo;
import com.creed.parent.controller.metrics.vo.ServerPingLogVo;
import com.creed.parent.dal.entity.ServerInfo;
import com.creed.parent.dal.entity.ServerMetrics;
import com.creed.parent.dal.entity.ServerPingLog;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-23T17
 */
@Mapper
public interface ServerMetricsConvert {
    ServerMetricsConvert INSTANCE = Mappers.getMapper(ServerMetricsConvert.class);

    @Mapping(ignore = true, target = "createdAt")
    @Mapping(ignore = true, target = "enabled")
    @Mapping(source = "clusterName", target = "clusterName")
    @Mapping(source = "environment", target = "environment")
    @Mapping(source = "region", target = "region")
    @Mapping(source = "properties.serverName", target = "serverName")
    @Mapping(source = "properties.serverHost", target = "serverHost")
    @Mapping(source = "properties.serverPort", target = "serverPort")
    @Mapping(source = "type", target = "type")
    BasicServerInfoVo convertBasicServerInfoVo(String clusterName, String environment, String region, ServerMetricsType type, InstanceProperties properties);

    @Mapping(ignore = true, target = "createdAt")
    @Mapping(ignore = true, target = "enabled")
    ServerInfo convert(BasicServerInfoVo serverInfo);

    @Mapping(ignore = true, target = "createdAt")
    @Mapping(ignore = true, target = "enabled")
    void updateServerInfo(BasicServerInfoVo vo, @MappingTarget ServerInfo serverInfo);

    BasicServerInfoVo convert0(ServerInfo serverInfo);
    List<BasicServerInfoVo> convert0(List<ServerInfo> serverInfo);

    @Mapping(source = "serverInfo.clusterName", target = "clusterName")
    @Mapping(source = "serverInfo.serverName", target = "serverName")
    @Mapping(source = "serverInfo.serverHost", target = "serverHost")
    @Mapping(source = "serverInfo.serverPort", target = "serverPort")
    @Mapping(source = "serverInfo.environment", target = "environment")
    @Mapping(source = "serverInfo.region", target = "region")
    @Mapping(source = "serverInfo.enabled", target = "enabled")
    @Mapping(source = "serverInfo.createdAt", target = "createdAt")
    ServerMetricsVo convert(ServerMetrics serverMetrics);

    List<ServerMetricsVo> convert(List<ServerMetrics> serverMetrics);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "status", target = "status")
    @Mapping(source = "latencyMs", target = "latencyMs")
    @Mapping(source = "serverInfo", target = "serverInfo")
    ServerPingLog buildServerPing(Boolean status, Long latencyMs, ServerInfo serverInfo);

    @Mapping(source = "serverInfo.clusterName", target = "clusterName")
    @Mapping(source = "serverInfo.serverName", target = "serverName")
    @Mapping(source = "serverInfo.serverHost", target = "serverHost")
    @Mapping(source = "serverInfo.serverPort", target = "serverPort")
    @Mapping(source = "serverInfo.environment", target = "environment")
    @Mapping(source = "serverInfo.region", target = "region")
    @Mapping(source = "serverInfo.enabled", target = "enabled")
    @Mapping(source = "serverInfo.createdAt", target = "createdAt")
    ServerPingLogVo convert1(ServerPingLog pingLog);

    List<ServerPingLogVo> convert1(List<ServerPingLog> pingLog);
}
