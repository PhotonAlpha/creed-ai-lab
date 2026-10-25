package com.creed.resource.veracode;

import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.config.UploadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ConfigMap mount: an application.yml in CREED_VERACODE_CONFIG_DIR overrides only the keys it
 * names. Runs the real {@code application()} because {@code @SpringBootTest} would skip the default
 * property that adds the directory.
 */
class ExternalConfigDirTest {

    @TempDir
    Path configDir;

    @Test
    void configDirOverridesOnlyTheKeysItNames() throws IOException {
        Files.writeString(configDir.resolve("application.yml"), """
                veracode:
                  jira:
                    issue-type: Task
                    jql-batch-size: 20
                  upload:
                    max-entries: 20
                """);

        try (ConfigurableApplicationContext context = run("--CREED_VERACODE_CONFIG_DIR=" + configDir + "/")) {
            JiraProperties jira = context.getBean(JiraProperties.class);
            assertThat(jira.issueType()).isEqualTo("Task");
            assertThat(jira.jqlBatchSize()).isEqualTo(20);
            assertThat(context.getBean(UploadProperties.class).maxEntries()).isEqualTo(20);
            // not in the file: still the packaged value (application-test.yml here)
            assertThat(jira.pageSize()).isEqualTo(2);
            assertThat(jira.projectKey()).isEqualTo("CREED");
        }
    }

    @Test
    void missingConfigDirIsNotAnError() {
        try (ConfigurableApplicationContext context = run("--CREED_VERACODE_CONFIG_DIR=" + configDir.resolve("absent") + "/")) {
            assertThat(context.getBean(JiraProperties.class).issueType()).isEqualTo("Bug");
        }
    }

    private static ConfigurableApplicationContext run(String configDirArg) {
        return CreedResourceVeracodeApplication.application()
                .run("--spring.profiles.active=test", configDirArg);
    }
}
