package com.creed.parent.config;

import lombok.Data;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-30T10
 */
@Data
public class DiscoveryProperties {
    private String environment;
    private String region;
    private List<InstanceProperties> instances;
}
