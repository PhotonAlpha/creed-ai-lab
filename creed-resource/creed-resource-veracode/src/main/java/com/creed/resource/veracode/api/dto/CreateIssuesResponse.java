package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.List;

public record CreateIssuesResponse(List<Result> results) {

    public enum Outcome {
        CREATED,
        /** Someone (or an earlier click) created it since the screen was loaded. */
        ALREADY_EXISTS,
        /**
         * The issue exists - another application's report (or another finding of this request) filed it: this
         * finding's application label was added to it instead of creating a second issue.
         */
        LABELED,
        /** The report has no such finding. */
        NOT_IN_REPORT,
        FAILED
    }

    public record Result(String findingId, Outcome outcome, List<IssueRef> issues, String message) {
    }
}
