package com.creed.resource.veracode.api.dto;

/**
 * What the page shows about where it reads from and writes to. No credentials.
 *
 * @param requireUserToken creating Jira issues needs the user's token ({@code X-Jira-Token})
 */
public record VeracodeSettings(String projectKey, String issueType, String jiraBaseUrl, boolean mock,
                               String summaryTemplate, boolean requireUserToken) {
}
