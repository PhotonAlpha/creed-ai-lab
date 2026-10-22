package com.creed.metrics.config;

import com.creed.metrics.command.web.CommandMappingPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Auto-configuration rather than a scanned @Configuration, so it also applies in apps that do not scan
 * {@code com.creed}. Apps that do scan it don't load it twice: @SpringBootApplication's scan skips
 * classes listed in AutoConfiguration.imports.
 */
@AutoConfiguration
public class CommandMappingAutoConfiguration {

    // static: a BeanPostProcessor must not force its declaring configuration to be created early.
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static CommandMappingPostProcessor commandMappingPostProcessor() {
        return new CommandMappingPostProcessor();
    }
}
