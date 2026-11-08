package com.creed.resource.veracode.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * SAST issues named by id ({@code SAST|CWE-89|<category>}), re-read by the server before anything is created.
 *
 * @param fields       the SAST create screen's other fields
 * @param jiraVersions the version chosen for SAST: one when its issue type requires Affects Version/s, else optional
 */
public record CreateSastIssuesRequest(@NotBlank String uploadId, @NotEmpty @Size(max = 500) List<String> issueIds,
                                      Map<String, Object> fields, List<String> jiraVersions) {
}
