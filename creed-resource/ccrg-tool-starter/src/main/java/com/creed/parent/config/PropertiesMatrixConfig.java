package com.creed.parent.config;

import com.creed.parent.definition.RouteDefinition;
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
@ConfigurationProperties(prefix = "properties-matrix")
@Data
public class PropertiesMatrixConfig {
    private String rootPath = ".";
    List<RouteDefinition> routes;
}
