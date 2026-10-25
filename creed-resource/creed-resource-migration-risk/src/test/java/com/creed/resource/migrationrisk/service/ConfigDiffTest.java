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
        assertThat(ConfigDiff.flatten("application.yml", yaml)).containsExactly(
                java.util.Map.entry("a.b", "1"),
                java.util.Map.entry("a.list[0]", "x"),
                java.util.Map.entry("a.list[1]", "y"),
                java.util.Map.entry("[doc 1] spring.config.activate.on-profile", "cloud"),
                java.util.Map.entry("[doc 1] a.b", "2"));
    }

    @Test
    void propertiesDiffWithRepo2Presence() {
        assertThat(ConfigDiff.configLines("application.properties",
                "a=1\nb=2\n", "a=1\nb=3\nc=4\n", "b=9\n")).containsExactly(
                "~ b: 2 -> 3  (repo2: 9)",
                "+ c = 4  (repo2: missing)");
        assertThat(ConfigDiff.configLines("application.properties", "a=1\n", "", null))
                .containsExactly("- a  (repo2: no such file)");
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
}
