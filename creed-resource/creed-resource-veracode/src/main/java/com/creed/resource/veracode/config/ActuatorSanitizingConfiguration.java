package com.creed.resource.veracode.config;

import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code /actuator/env} and {@code /actuator/configprops} run with {@code show-values: always} so the
 * OpenShift course can trace where each value came from - and {@code always} masks nothing on its own:
 * the Jira token and the keystore password came back in plain text. This masks the secrets and
 * leaves everything else readable.
 *
 * <p>Not {@code ifLikelySensitive()}: its {@code key} suffix also masks {@code veracode.jira.project-key},
 * and its {@code url}/{@code address} suffixes mask every base URL - the values one actually debugs.
 * Matching is case-insensitive, so {@code CREED_JIRA_TOKEN} (systemEnvironment) is caught as well as
 * {@code veracode.jira.auth.token} (configprops) and the configtree file {@code veracode.ssl.password}.
 */
@Configuration(proxyBeanMethods = false)
public class ActuatorSanitizingConfiguration {

    @Bean
    SanitizingFunction maskSecrets() {
        return SanitizingFunction.sanitizeValue()
                .ifKeyEndsWith("password", "secret", "token")
                .ifKeyContains("credentials")
                // sun.java.command (may carry --x.password=...) and spring.application.json
                .ifLikelySensitiveProperty();
    }
}
