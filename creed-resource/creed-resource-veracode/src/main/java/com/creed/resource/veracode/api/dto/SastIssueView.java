package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

/**
 * One SAST Jira issue as the page shows it: what it is for, its summary (what is searched and what a create files),
 * and what Jira holds.
 *
 * @param summary {@code {project} - CWE-89 - {category}}: found by it, created with it
 * @param status  {@code null} when Jira could not be read for SAST
 * @param flaws   its open flaws
 */
public record SastIssueView(String id, String summary, String cwe, String cweName, String category, String severity,
                            List<SastFlaw> flaws, List<String> applications, FindingStatus status,
                            List<IssueRef> issues) {

    public static SastIssueView of(SastIssue i, String summary, FindingStatus status, List<IssueRef> issues) {
        return new SastIssueView(i.id(), summary, i.cwe(), i.cweName(), i.category(), i.severity(), i.flaws(),
                i.applications(), status, List.copyOf(issues));
    }
}
