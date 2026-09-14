package com.creed.simple.modcluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The on/off switch for the {@code mod_cluster} node registration ({@code creed.mod-cluster.*}).
 *
 * <p>{@code creed.mod-cluster.enabled} is <strong>off by default</strong>: a local run has no Apache
 * HTTP Server in front of it, and an enabled-by-default listener would only produce a failing banner
 * and a retry loop. The disabled case still logs one line ({@link DisabledLogger}) — silence would be
 * indistinguishable from the feature not existing.
 *
 * <p>Everything else lives in {@link ModClusterListenerConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModClusterProperties.class)
public class ModClusterConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "creed.mod-cluster", name = "enabled", havingValue = "true")
    @Import(ModClusterListenerConfiguration.class)
    static class EnabledConfiguration {
    }

    /** The "nothing happened, and here is why" half of the startup print. */
    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "creed.mod-cluster", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    static class DisabledLogger {

        @Bean
        ApplicationListener<ApplicationReadyEvent> modClusterDisabledLogger() {
            return event -> log.info("mod_cluster registration is DISABLED "
                    + "(creed.mod-cluster.enabled=false) — this node is not advertised to any "
                    + "Apache HTTP Server proxy.");
        }
    }
}
