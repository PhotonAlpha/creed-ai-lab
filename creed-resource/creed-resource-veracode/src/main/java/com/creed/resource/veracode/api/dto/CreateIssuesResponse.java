package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

public record CreateIssuesResponse(List<Result> results) {

    public enum Outcome {
        CREATED,
        /** Someone (or an earlier click) created it since the screen was loaded. */
        ALREADY_EXISTS,
        /** The page no longer has this finding. */
        NOT_IN_REPORT,
        /** The project has no such version; Jira would reject the create. */
        VERSION_NOT_IN_JIRA,
        FAILED
    }

    public record Result(String findingId, Outcome outcome, List<IssueRef> issues, String message) {
    }
}
