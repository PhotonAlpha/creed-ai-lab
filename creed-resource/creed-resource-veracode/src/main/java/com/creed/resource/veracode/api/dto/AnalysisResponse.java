package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.service.CreateField;
import com.creed.resource.veracode.service.ReportView;

import java.time.Instant;
import java.util.List;

/**
 * @param uploadId         what re-analyse, create and export name the upload by
 * @param reports          the upload's reports, one per file (several Veracode applications, possibly)
 * @param jiraVersions     every version the issue type can be filed against - the page's Jira version list
 * @param selectedVersions the ones chosen on the page, Jira's spelling: searched ({@code affectedVersion in});
 *                         empty = any version
 * @param createVersion    what a created issue is filed under: the one selected version, {@code null} with none
 *                         or several selected (creating then answers {@code jira_version_required})
 * @param createFields the create-screen fields the page asks for before creating (read with the user's token)
 * @param warnings     report rows that were skipped or read partially, and creations that failed during an
 *                     {@code autoCreate} run
 * @param sastFlaws    every report's static-analysis flaws, as listed (fixed and mitigated included)
 * @param sast         the open flaws as SAST Jira issues (one per CWE + category) in the SAST project
 * @param applications the reports' Veracode applications under their mapped names and links
 */
public record AnalysisResponse(String uploadId, List<ReportView> reports, String projectKey, List<String> jiraVersions,
                               List<String> selectedVersions, String createVersion,
                               List<CreateField> createFields,
                               List<FindingView> findings, List<String> warnings, Counts counts,
                               List<SastFlaw> sastFlaws, SastJira sast, List<Application> applications,
                               Instant analyzedAt) {

    /**
     * A Veracode application as the page shows it ({@code veracode.applications}).
     *
     * @param name its mapped name, else {@code appName}
     * @param url  where the name links to; {@code null} without a mapping
     */
    public record Application(String appName, String name, String url) {
    }

    /**
     * @param projectKey      the SAST project ({@code veracode.jira.sast.project-key})
     * @param projectName     {@code {project}} of the SAST summaries ({@code sast.project-name})
     * @param jiraVersions    the versions its issue type can be filed against (empty without Affects Version/s)
     * @param versionRequired creating needs exactly one of them
     * @param defaultVersions {@code sast.defaults.affects-versions} among {@code jiraVersions}: the page preselects one
     * @param createFields    the SAST create screen's fields the page asks for
     * @param error           why Jira could not be read for SAST; the issues are then listed without a status
     */
    public record SastJira(String projectKey, String projectName, String issueType, List<String> jiraVersions, boolean affectsVersion,
                           boolean versionRequired, List<String> defaultVersions, List<CreateField> createFields, List<SastIssueView> issues,
                           Counts counts, String error) {
    }

    /** @param needsLabel existing issues still without the finding's application label */
    public record Counts(int total, int exists, int missing, int created, int needsLabel) {

        public static Counts of(List<FindingView> findings) {
            int exists = 0, missing = 0, created = 0, needsLabel = 0;
            for (FindingView f : findings) {
                switch (f.status()) {
                    case EXISTS -> exists++;
                    case MISSING -> missing++;
                    case CREATED -> created++;
                }
                if (f.needsLabel()) {
                    needsLabel++;
                }
            }
            return new Counts(findings.size(), exists, missing, created, needsLabel);
        }
    }
}
