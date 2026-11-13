package com.creed.resource.veracode.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Strategy;

import java.util.List;

/**
 * The Jira / Confluence clients' Logbook ({@code RemoteClientsConfiguration}) logs JSON bodies only. Logbook's
 * auto-configuration backs off its own {@code Strategy} for this one ({@code @ConditionalOnMissingBean}), so the
 * {@code logbook.strategy} property no longer applies.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class LogbookConfiguration {

    @Bean
    Strategy logbookStrategy(@Value("${veracode.logbook.allowed-content-types:application/json}") List<String> allowed) {
        log.info("Logbook logs request / response bodies only of {}", allowed.isEmpty() ? "(any type)" : allowed);
        return new JsonBodyOnlyStrategy(allowed);
    }
}
