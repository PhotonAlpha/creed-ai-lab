package com.creed.resource.veracode.api.dto;

/** What the page shows about where it reads from and writes to. No credentials. */
public record VeracodeSettings(String defaultPageId, String projectKey, String issueType,
                               String confluenceBaseUrl, String jiraBaseUrl, boolean mock) {
}
