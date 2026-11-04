package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param baseUrl        everything before {@code /rest/api/2}
 * @param projectKey     the project findings are looked up in and created in
 * @param issueType      issue type name for created issues; must exist in the project's scheme
 * @param summaryTemplate a created issue's summary: {@code {project}} (the report's Veracode {@code app_name}; the
 *                       Jira project key when it has none), {@code {jiraProject}}, {@code {component}}
 *                       (the finding's components, comma-separated), {@code {cve}} (the CVE, or the title for
 *                       a finding without one), {@code {title}}, {@code {version}}. Matching never reads it:
 *                       an issue is found by the CVE (or title) anywhere in its summary
 * @param requireUserToken creating issues needs the user's own token from the page ({@code X-Jira-Token});
 *                       {@code false} falls back to {@code auth}
 * @param labels         added to every created issue (the CVE id is added as well)
 * @param browseUrl      template for issue links, {@code {key}} is replaced
 * @param jqlBatchSize   findings per search; each adds one {@code summary ~} clause, and a long JQL
 *                       is slow on Jira's side and can exceed its URL/body limits
 * @param pageSize       {@code maxResults} per search page (Jira caps it, usually at 100)
 */
@Validated
@ConfigurationProperties("veracode.jira")
public record JiraProperties(
        @NotBlank String baseUrl,
        @NotBlank String projectKey,
        @DefaultValue("Bug") String issueType,
        @DefaultValue("{project} {component} {cve}") String summaryTemplate,
        @DefaultValue("true") boolean requireUserToken,
        @DefaultValue("veracode") List<String> labels,
        @NotBlank String browseUrl,
        @DefaultValue("40") @Min(1) @Max(200) int jqlBatchSize,
        @DefaultValue("100") @Min(1) @Max(1000) int pageSize,
        @DefaultValue RemoteAuth auth,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout) {

    public String browse(String key) {
        return browseUrl.replace("{key}", key);
    }
}
