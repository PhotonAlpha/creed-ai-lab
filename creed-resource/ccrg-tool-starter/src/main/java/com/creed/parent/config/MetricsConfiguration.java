package com.creed.parent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-24T16
 */
@Component
@ConfigurationProperties(prefix = "access.metrics")
@Data
public class MetricsConfiguration {
    private Map<String, DiscoveryProperties> monitorDiscovery = new HashMap<>();
    private Map<String, DiscoveryProperties> pingDiscovery = new HashMap<>();
}
