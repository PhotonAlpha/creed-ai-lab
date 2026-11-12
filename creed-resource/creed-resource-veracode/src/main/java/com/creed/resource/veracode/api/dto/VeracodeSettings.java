package com.creed.resource.veracode.api.dto;

/**
 * What the page shows about where it reads from and writes to. No credentials.
 *
 * Every Jira call needs the user's own token ({@code X-Jira-Token}); there is no service account to fall back on.
 *
 * @param cloud the deployment the token dialog preselects (Jira Cloud when the base URL is {@code *.atlassian.net});
 *              the user can switch it - Cloud sends the Atlassian e-mail too ({@code X-Jira-User}, Basic auth),
 *              Data Center a personal access token alone (Bearer)
 * @param confluenceBaseUrl {@code null} = no Confluence configured: the page offers uploads only
 * @param confluenceCloud   the Confluence token dialog's preselected deployment
 * @param confluenceExportTitle the exported page's title template ({@code {project}}, {@code {version}}, {@code {timestamp}})
 * @param confluenceParentPageId {@code veracode.confluence.parent-page-id}: whose child pages the page lists;
 *                               {@code null} = none configured
 */
public record VeracodeSettings(String projectKey, String issueType, String jiraBaseUrl, boolean mock,
                               String summaryTemplate, boolean cloud, String confluenceBaseUrl,
                               boolean confluenceCloud, String confluenceExportTitle, String confluenceParentPageId) {
}
