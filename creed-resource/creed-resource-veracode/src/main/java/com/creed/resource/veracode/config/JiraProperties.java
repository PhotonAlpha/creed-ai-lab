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
 * @param projectName    {@code {project}} in the summary, and a label of every created issue - a name of this
 *                       service's own, not the Veracode application (that is {@code {application}}). Blank = the
 *                       Jira project key
 * @param summaryTemplate a created issue's summary: {@code {project}} ({@code projectName}), {@code {cve}} (the
 *                       {@code cve_id}, "Unknown" without one), {@code {cwe}} ({@code cwe_id}, "Unknown" without
 *                       one), {@code {file}} (the component's {@code file_name}), {@code {application}} (the
 *                       Veracode {@code app_name}), {@code {component}}, {@code {jiraProject}}, {@code {version}}.
 *                       Matching reads which of {@code {file}} / {@code {component}} it holds: an issue must name
 *                       those, plus the CVE (or Unknown + CWE)
 * @param labels         added to every created issue, with {@code projectName} and the application label (not the CVE)
 * @param applicationLabel a regex on the Veracode application whose group 1 is the application's label: the default
 *                       drops everything up to the second {@code -} ({@code AAA-BBB-CCC-DDD} -> {@code CCC-DDD});
 *                       no match = the whole name. Another application reporting the same issue adds its label
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
        String projectName,
        @DefaultValue("{project} - {cve}|{cwe} - {file}") String summaryTemplate,
        @DefaultValue("veracode") List<String> labels,
        @DefaultValue("^[^-]*-[^-]*-(.+)$") String applicationLabel,
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

    /** {@code {project}}: the configured name, else the Jira project key. */
    public String project() {
        return projectName == null || projectName.isBlank() ? projectKey : projectName.trim();
    }

    /** The label an application's findings carry ({@link #applicationLabel}); {@code null} without an application. */
    public String applicationLabel(String application) {
        if (application == null || application.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(applicationLabel).matcher(application.trim());
        return m.matches() && m.groupCount() >= 1 && m.group(1) != null && !m.group(1).isBlank()
                ? m.group(1) : application.trim();
    }

    public String browse(String key) {
        return browseUrl.replace("{key}", key);
    }
}
