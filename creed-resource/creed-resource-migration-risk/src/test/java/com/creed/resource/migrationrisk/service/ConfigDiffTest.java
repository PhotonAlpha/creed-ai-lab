package com.creed.resource.migrationrisk.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigDiffTest {

    @Test
    void recognisesSpringConfigFiles() {
        assertThat(ConfigDiff.isSpringConfig("src/main/resources/application.yml")).isTrue();
        assertThat(ConfigDiff.isSpringConfig("a/application-cloud.yaml")).isTrue();
        assertThat(ConfigDiff.isSpringConfig("bootstrap.properties")).isTrue();
        assertThat(ConfigDiff.isSpringConfig("src/main/resources/logback-spring.xml")).isFalse();
        assertThat(ConfigDiff.isSpringConfig("docs/application-notes.md")).isFalse();
        assertThat(ConfigDiff.isPom("module/pom.xml")).isTrue();
    }

    @Test
    void yamlKeysListsAndProfileDocuments() {
        String yaml = """
                a:
                  b: 1
                  list: [x, y]
                ---
                spring.config.activate.on-profile: cloud
                a:
                  b: 2
                """;
        assertThat(ConfigDiff.globalKeys("application.yml", yaml, false)).containsExactly(
                java.util.Map.entry("a.b", "1"),
                java.util.Map.entry("a.list[0]", "x"),
                java.util.Map.entry("a.list[1]", "y"),
                java.util.Map.entry("[cloud] spring.config.activate.on-profile", "cloud"),
                java.util.Map.entry("[cloud] a.b", "2"));
    }

    @Test
    void configFilesBeyondSpringFollowThePattern() {
        var pattern = java.util.regex.Pattern.compile(
                com.creed.resource.migrationrisk.config.MigrationRiskProperties.DEFAULT_CONFIG_FILES);
        assertThat(ConfigDiff.isConfigFile("svc/src/main/resources/messages.properties", pattern)).isTrue();
        assertThat(ConfigDiff.isConfigFile("config/redis.yml", pattern)).isTrue();
        assertThat(ConfigDiff.isConfigFile("application.yml", pattern)).isTrue();
        assertThat(ConfigDiff.isConfigFile(".github/workflows/build.yml", pattern)).isFalse();
        assertThat(ConfigDiff.isConfigFile("charts/shop/values.yaml", pattern)).isFalse();
    }

    @Test
    void pomDependenciesManagedVersionsAndScopes() {
        String before = """
                <project><dependencies>
                  <dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>
                </dependencies></project>
                """;
        String after = """
                <project><dependencies>
                  <dependency><groupId>g</groupId><artifactId>a</artifactId><version>2</version></dependency>
                  <dependency><groupId>g</groupId><artifactId>t</artifactId><version>1</version><scope>test</scope></dependency>
                </dependencies></project>
                """;
        assertThat(ConfigDiff.pomLines(before, after, before)).containsExactly(
                "~ dependency g:a: managed -> 2  (repo2: managed)",
                "+ dependency g:t = 1 (test)  (repo2: missing)");
    }

    @Test
    void pomWithADoctypeIsRefused() {
        assertThatThrownBy(() -> ConfigDiff.pomLines(
                "<!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project>&x;</project>", "<project/>", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void globalKeysIgnoreTheFileButKeepProfileAndScope() {
        // the same key in application.yml and application.properties has the same global name
        assertThat(ConfigDiff.globalKeys("a/src/main/resources/application.yml", "shop:\n  max: 1\n", false))
                .containsExactly(java.util.Map.entry("shop.max", "1"));
        assertThat(ConfigDiff.globalKeys("b/config/application.properties", "shop.max=1\n", false))
                .containsExactly(java.util.Map.entry("shop.max", "1"));
        // profile from the file name or the yml document; test resources scoped apart; other files by name
        assertThat(ConfigDiff.globalKeys("application-dev.yml", "x: 1\n---\nspring.config.activate.on-profile: k8s\nx: 2\n", false))
                .containsEntry("[dev] x", "1").containsEntry("[k8s] x", "2");
        assertThat(ConfigDiff.globalKeys("src/test/resources/application.yml", "x: 1\n", true)).containsOnlyKeys("[test] x");
        assertThat(ConfigDiff.globalKeys("src/main/resources/messages_zh.properties", "hello=你好\n", false))
                .containsOnlyKeys("[messages_zh.properties] hello");
    }
}
