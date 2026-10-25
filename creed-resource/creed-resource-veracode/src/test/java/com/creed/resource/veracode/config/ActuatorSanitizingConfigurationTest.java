package com.creed.resource.veracode.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorSanitizingConfigurationTest {

    private final SanitizingFunction function = new ActuatorSanitizingConfiguration().maskSecrets();

    @ParameterizedTest
    @ValueSource(strings = {
            "CREED_JIRA_TOKEN", "CREED_CONFLUENCE_TOKEN", "CREED_VERACODE_SSL_PASSWORD",
            "veracode.jira.auth.token", "veracode.ssl.password",
            "spring.ssl.bundle.jks.veracode.keystore.password", "sun.java.command"})
    void masksSecrets(String key) {
        assertThat(sanitize(key)).isEqualTo(SanitizableData.SANITIZED_VALUE);
    }

    // The keys ifLikelySensitive() would have masked too: they are what the env endpoint is read for.
    @ParameterizedTest
    @ValueSource(strings = {
            "veracode.jira.project-key", "CREED_JIRA_PROJECT", "veracode.jira.base-url", "CREED_JIRA_URL",
            "veracode.jira.ssl-bundle", "veracode.jira.auth.username"})
    void keepsTheRest(String key) {
        assertThat(sanitize(key)).isEqualTo("value");
    }

    private Object sanitize(String key) {
        return function.applyUnlessFiltered(new SanitizableData(null, key, "value")).getValue();
    }
}
