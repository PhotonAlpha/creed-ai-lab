package com.creed.resource.veracode.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Findings are named by id, not sent: the server re-reads the upload and re-checks Jira before creating,
 * so a stale screen or a second click cannot create a duplicate.
 *
 * @param fields the create screen's other fields: fieldId -> value or values (an option by id or label), e.g.
 *               {@code {"components": ["Security"], "customfield_10100": "Production"}}
 * @param jiraVersions exactly one Jira version: the issues are filed under it
 * @param countries    the Country options the page looks up in: the finding is re-checked with them before creating
 * @param recreateClosed file the one finding named although it has issues, all of them finished
 *                       ({@code closed-statuses}) - the page asks the user first. Ignored while one is still open
 */
public record CreateIssuesRequest(@NotBlank String uploadId, @NotEmpty @Size(max = 500) List<String> findingIds,
                                  Map<String, Object> fields, List<String> jiraVersions, List<String> countries,
                                  boolean recreateClosed) {
}
