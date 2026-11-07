package com.creed.parent.monitoring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @author EthanCao
 * @description idbccs
 * @date 8/2/25
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "thread-pool-monitoring.toggles")
public class ThreadPoolMonitoringConfiguration {
    private Boolean enabled = Boolean.TRUE;
    private Duration interval = Duration.ofMinutes(3L);
}
