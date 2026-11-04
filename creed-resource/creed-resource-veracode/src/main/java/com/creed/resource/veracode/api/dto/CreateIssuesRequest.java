package com.creed.resource.veracode.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Findings are named by id, not sent: the server re-reads the upload and re-checks Jira before creating,
 * so a stale screen or a second click cannot create a duplicate.
 */
public record CreateIssuesRequest(@NotBlank String uploadId, @NotEmpty @Size(max = 500) List<String> findingIds) {
}
