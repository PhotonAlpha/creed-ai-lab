package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

/**
 * @param title           {@code cve_id|cwe_id}, "Unknown" for a missing part - the page's title column
 * @param cveId           the report's {@code cve_id} (a CVE or an advisory id); {@code null} = Unknown
 * @param cve             {@code cveId} when it is a CVE - the page links it to NVD
 * @param fileName        the component's {@code file_name}: with the CVE, what the Jira issue is for
 * @param description     the full {@code cve_summary}
 * @param affectedVersion the report's version (or the page's override): shown, never searched or filed under -
 *                        Jira is read and written by the Jira versions chosen on the page
 * @param needsLabel      the issue exists (another application filed it) but lacks this application's label:
 *                        creating adds the label instead of a second issue
 */
public record FindingView(String id, String application, String title, String cveId, String cve, String cwe,
                          String fileName, List<String> components, String severity, Double cvss,
                          String affectedVersion, String description, List<String> locations,
                          FindingStatus status, List<IssueRef> issues, boolean needsLabel) {

    public static FindingView of(Finding f, FindingStatus status, List<IssueRef> issues, boolean needsLabel) {
        return new FindingView(f.id(), f.application(), f.title(), f.cveId(), f.cve(), f.cwe(), f.fileName(),
                f.components(), f.severity(), f.cvss(), f.affectedVersion(), f.description(),
                f.locations(), status, List.copyOf(issues), needsLabel);
    }
}
