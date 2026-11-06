package com.creed.resource.veracode.api.dto;

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
 */
public record AnalysisResponse(String uploadId, List<ReportView> reports, String projectKey, List<String> jiraVersions,
                               List<String> selectedVersions, String createVersion,
                               List<CreateField> createFields,
                               List<FindingView> findings, List<String> warnings, Counts counts,
                               Instant analyzedAt) {

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
