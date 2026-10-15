package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.service.ReportSourceRef;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Findings are named by id, not sent: the server re-reads the page and re-checks Jira before creating,
 * so a stale screen or a second click cannot create a duplicate.
 */
public record CreateIssuesRequest(String pageId, String uploadId,
                                  @NotEmpty @Size(max = 500) List<String> findingIds) {

    public ReportSourceRef source() {
        return new ReportSourceRef(pageId, uploadId);
    }
}
