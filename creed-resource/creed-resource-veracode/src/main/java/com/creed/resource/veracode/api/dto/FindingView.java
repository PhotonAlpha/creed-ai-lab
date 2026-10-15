package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

/**
 * @param jiraVersion  Jira's spelling of {@code affectedVersion}; {@code null} when the project has no
 *                     such version - nothing was searched, and an issue cannot be created until it exists
 */
public record FindingView(String id, String cve, String title, List<String> components, String severity,
                          Double cvss, String affectedVersion, String fixedVersion, String description,
                          List<String> locations, FindingStatus status, String jiraVersion,
                          List<IssueRef> issues) {

    public static FindingView of(Finding f, FindingStatus status, String jiraVersion, List<IssueRef> issues) {
        return new FindingView(f.id(), f.cve(), f.title(), f.components(), f.severity(), f.cvss(),
                f.affectedVersion(), f.fixedVersion(), f.description(), f.locations(), status, jiraVersion,
                List.copyOf(issues));
    }
}
