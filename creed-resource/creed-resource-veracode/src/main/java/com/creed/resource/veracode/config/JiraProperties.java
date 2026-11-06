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
 * Where Jira is and what to create there. No credentials: every call carries the token the user typed on the
 * page ({@code X-Jira-Token}), so each user reads and files as themselves.
 *
 * @param baseUrl        everything before {@code /rest/api/2/issue}
 * @param projectKey     the project findings are looked up in and created in
 * @param issueType      issue type name for created issues; must exist in the project's scheme
 * @param summaryTemplate a created issue's summary: {@code {project}} (the report's Veracode {@code app_name}; the
 *                       Jira project key when it has none), {@code {jiraProject}}, {@code {component}}
 *                       (the finding's components, comma-separated), {@code {cve}} (the CVE, or the title for
 *                       a finding without one), {@code {title}}, {@code {version}}. Matching never reads it:
 *                       an issue is found by the CVE (or title) anywhere in its summary
 * @param labels         added to every created issue (the CVE id is added as well)
 * @param browseUrl      template for issue links, {@code {key}} is replaced
 * @param pageSize       {@code maxResults} per page of the paged calls (create metadata, searches)
 * @param searchBatchSize findings per lookup search: their CVEs are OR'ed into one JQL. Bounded by the URL length
 *                       (the JQL is a query parameter) - 50 CVE phrases are about 3 KB encoded
 * @param maxConnections pooled connections to Jira (lookups run one after another per request; this bounds
 *                       concurrent requests)
 */
@Validated
@ConfigurationProperties("veracode.jira")
public record JiraProperties(
        @NotBlank String baseUrl,
        @NotBlank String projectKey,
        @DefaultValue("Bug") String issueType,
        @DefaultValue("{project} {component} {cve}") String summaryTemplate,
        @DefaultValue("veracode") List<String> labels,
        @NotBlank String browseUrl,
        @DefaultValue("50") @Min(1) @Max(1000) int pageSize,
        @DefaultValue("50") @Min(1) @Max(100) int searchBatchSize,
        @DefaultValue("20") @Min(1) @Max(200) int maxConnections,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout) {

    /**
     * Only the page's preselected deployment: the user picks Cloud or Data Center with the token, and that choice
     * is what the calls use ({@code JiraToken.cloud()}).
     */
    public boolean looksLikeCloud() {
        String host = java.net.URI.create(baseUrl).getHost();
        return host != null && host.toLowerCase(java.util.Locale.ROOT).endsWith(".atlassian.net");
    }

    public String browse(String key) {
        return browseUrl.replace("{key}", key);
    }
}
