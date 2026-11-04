package com.creed.resource.veracode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Map;

/**
 * Veracode SCA report analysis: reads an uploaded Veracode detailed report (XML), looks each finding up in
 * Jira by CVE (or advisory id) and affects version, and creates the issues that are missing.
 *
 * <p>No database. Uploads are held in memory for a while; Jira is read at request time, so it stays the
 * only source of truth for what exists. Locally Jira is the in-process mock under {@code /mock/jira}
 * ({@code veracode.mock.enabled}).
 *
 * <p>On top of the packaged {@code application*.yml}, an {@code application.yml} (and
 * {@code application-<profile>.yml}) in {@code CREED_VERACODE_CONFIG_DIR}, default
 * {@code /etc/veracode/config/} - where OpenShift mounts the ConfigMap {@code veracode-app-config} - is
 * read at startup and wins over the packaged files, so the ConfigMap only carries the keys it overrides.
 * Read once: an edited ConfigMap needs a restart.
 *
 * <p>Set as a default property, not in {@code application.yml}: {@code spring.config.additional-location}
 * is consulted before any config file is read. Its files rank above every packaged file, the
 * profile-specific {@code application-ssl.yml} included. As a default property it has the lowest
 * precedence itself, so {@code SPRING_CONFIG_ADDITIONAL_LOCATION} still replaces it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedResourceVeracodeApplication {

    static final String CONFIG_DIR = "optional:file:${CREED_VERACODE_CONFIG_DIR:/etc/veracode/config/}";

    public static void main(String[] args) {
        application().run(args);
    }

    /** Also used by tests: {@code @SpringBootTest} never runs {@code main}, so it would miss the default. */
    static SpringApplication application() {
        SpringApplication application = new SpringApplication(CreedResourceVeracodeApplication.class);
        application.setDefaultProperties(Map.of("spring.config.additional-location", CONFIG_DIR));
        return application;
    }
}
