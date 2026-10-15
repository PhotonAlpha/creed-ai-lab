package com.creed.resource.veracode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Veracode report analysis: reads a Veracode report table from a Confluence page, looks each finding
 * up in Jira by CVE (or title) and affects version, and creates the issues that are missing.
 *
 * <p>Stateless — no database. Every answer is computed from Confluence and Jira at request time, so the
 * page and Jira stay the only sources of truth. Locally both are served by the in-process mocks under
 * {@code /mock/*} ({@code veracode.mock.enabled}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CreedResourceVeracodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedResourceVeracodeApplication.class, args);
    }
}
