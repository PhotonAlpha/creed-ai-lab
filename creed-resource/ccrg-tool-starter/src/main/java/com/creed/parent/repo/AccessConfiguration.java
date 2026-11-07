package com.creed.parent.repo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/22/25
 */
 @ConfigurationProperties(prefix = "access.arti")
 @Configuration
@Data
public class AccessConfiguration {
    private String tokenKey;
    private String tokenValue;
    private String bearerToken;
}
