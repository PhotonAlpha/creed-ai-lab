package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
 * @param labels         added to every created issue, with {@code projectName} and the application's label - its
 *                       alias (application aliases), else its {@code app_name}; not the CVE. Another
 *                       application reporting the same issue adds its label
 * @param browseUrl      template for issue links, {@code {key}} is replaced
 * @param pageSize       {@code maxResults} per page of the paged calls (create metadata, searches)
 * @param searchBatchSize findings per lookup search: their CVEs are OR'ed into one JQL. Bounded by the URL length
 *                       (the JQL is a query parameter) - 50 CVE phrases are about 3 KB encoded
 * @param maxConnections pooled connections to Jira, shared by every request: keep it above
 *                       {@code searchConcurrency} x 2 (an analysis searches SCA and SAST side by side)
 * @param defaults       what the page preselects once the connection test has read Jira - only values Jira offers
 * @param sast           where SAST (static-analysis) issues go - their own project, issue type, labels and summary
 * @param closedStatuses statuses (any case) of a finished issue: a finding whose issues are all in one may be filed
 *                       again, one at a time and after the page's confirmation (asked for, 2026-11-10)
 * @param countryField   the SCA issue type's Country field, by id ({@code customfield_10300}) or name, any case: its
 *                       options are the page's Country select, and the ones chosen narrow the lookup
 *                       ({@code cf[10300] in (...)}; asked for, 2026-11-11). Not on the create screen = no select
 * @param metaCacheTtl   how long an analysis reuses a create screen it read (issue types + fields, per token, project
 *                       and issue type): a lookup-condition change then costs one Jira search, not three calls. Issues
 *                       themselves are never cached; the connection test always reads afresh. {@code 0} = off
 * @param searchConcurrency lookup searches (batches of {@code searchBatchSize}) one analysis runs at once, for SCA and
 *                       for SAST each - they do not depend on each other. {@code 1} = one after another
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
        @NotBlank String browseUrl,
        @DefaultValue("50") @Min(1) @Max(1000) int pageSize,
        @DefaultValue("50") @Min(1) @Max(100) int searchBatchSize,
        @DefaultValue("20") @Min(1) @Max(200) int maxConnections,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        @DefaultValue CreateDefaults defaults,
        @DefaultValue Sast sast,
        @DefaultValue({"Closed", "Done"}) List<String> closedStatuses,
        @DefaultValue("Country") String countryField,
        @DefaultValue("60s") Duration metaCacheTtl,
        @DefaultValue("4") @Min(1) @Max(50) int searchConcurrency) {

    /** Every issue is finished ({@code closed-statuses}); {@code false} for none - a missing one is just created. */
    public boolean allClosed(List<String> statuses) {
        return !statuses.isEmpty() && statuses.stream().allMatch(s -> s != null
                && closedStatuses.stream().anyMatch(c -> c.trim().equalsIgnoreCase(s.trim())));
    }

    /**
     * SAST issues: one per CWE + flaw category ({@code categoryname}), looked up and created by their summary alone -
     * no Affects Version/s clause in the search (asked for, 2026-11-08). Blank values fall back to the SCA ones.
     *
     * @param projectKey      the project SAST issues are looked up in and created in
     * @param issueType       their issue type
     * @param projectName     {@code {project}} in the summary, and a label - like the SCA {@code project-name}
     * @param summaryTemplate {@code {project}}, {@code {cwe}} ({@code CWE-89}), {@code {category}} (the flaw's
     *                        {@code categoryname}), {@code {jiraProject}}. An issue matches when its summary names the
     *                        CWE and the category, so both must stay in it
     * @param labels          added to every created SAST issue, with {@code projectName}
     * @param defaults        preselected create-screen values of the SAST issue type (Affects Version/s, fields)
     */
    public record Sast(String projectKey, String issueType, String projectName,
                       @DefaultValue("{project} - {cwe} - {category}") String summaryTemplate,
                       List<String> labels, @DefaultValue CreateDefaults defaults) {

        public Sast {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    /** The project SAST issues go to: {@code sast.project-key}, else the SCA one. */
    public String sastProjectKey() {
        return sast.projectKey() == null || sast.projectKey().isBlank() ? projectKey : sast.projectKey().trim();
    }

    public String sastIssueType() {
        return sast.issueType() == null || sast.issueType().isBlank() ? issueType : sast.issueType().trim();
    }

    /** SAST's {@code {project}}: {@code sast.project-name}, else its project key. */
    public String sastProject() {
        return sast.projectName() == null || sast.projectName().isBlank() ? sastProjectKey() : sast.projectName().trim();
    }

    /**
     * Preselections for the page, not values forced on Jira: one Jira does not offer (a version not released yet,
     * an option renamed) is dropped, and the page then keeps what it had - so one config serves several projects.
     *
     * @param affectsVersions the Jira versions selected (Affects Version/s: what is looked up in and filed under),
     *                        matched case-insensitively against the issue type's versions
     * @param fields          create-screen fields by field id ({@code components}, {@code customfield_10100}) or
     *                        name ({@code Environment Found}), any case: option names (or ids) for a choice, the
     *                        value itself for text / number / user. Replaces Jira's own default when one matches
     */
    public record CreateDefaults(List<String> affectsVersions, Map<String, List<String>> fields) {

        public CreateDefaults {
            affectsVersions = affectsVersions == null ? List.of() : List.copyOf(affectsVersions);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        /**
         * The configured values of a field, by id first, then by name; empty when it has none. Compared without case,
         * spaces or punctuation: relaxed binding drops them from a map key ({@code Environment Found} arrives as
         * {@code EnvironmentFound} unless written {@code "[Environment Found]"}).
         */
        public List<String> of(String fieldId, String name) {
            for (String wanted : new String[] {fieldId, name}) {
                for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                    if (wanted != null && key(e.getKey()).equals(key(wanted))) {
                        return e.getValue() == null ? List.of() : e.getValue();
                    }
                }
            }
            return List.of();
        }

        private static String key(String text) {
            return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        }
    }

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

    public String browse(String key) {
        return browseUrl.replace("{key}", key);
    }
}
