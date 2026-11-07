package com.creed.parent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 8/28/24
 */
@Configuration
@ConfigurationProperties(prefix = "matrix-metadata")
@Data
public class MetadataConfigConfig {
    private List<VoidInstance> voidList;
}
