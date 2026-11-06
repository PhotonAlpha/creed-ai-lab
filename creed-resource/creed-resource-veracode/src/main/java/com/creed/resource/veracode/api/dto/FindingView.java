package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

/**
 * @param affectedVersion the report's version (or the page's override): shown, never searched or filed under -
 *                        Jira is read and written by the Jira versions chosen on the page
 */
public record FindingView(String id, String cve, String title, List<String> components, String severity,
                          Double cvss, String affectedVersion, String fixedVersion, String description,
                          List<String> locations, FindingStatus status, List<IssueRef> issues) {

    public static FindingView of(Finding f, FindingStatus status, List<IssueRef> issues) {
        return new FindingView(f.id(), f.cve(), f.title(), f.components(), f.severity(), f.cvss(),
                f.affectedVersion(), f.fixedVersion(), f.description(), f.locations(), status, List.copyOf(issues));
    }
}
