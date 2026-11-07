package com.creed.parent.config;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-30T10
 */

import lombok.Data;

@Data
public class InstanceProperties {
    private String serverName;
    private String serverHost;
    private Integer serverPort;
}
