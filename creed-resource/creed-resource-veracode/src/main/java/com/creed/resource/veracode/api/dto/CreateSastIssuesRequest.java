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
 * @param countries    the page's Countries: the issues are re-checked with them before creating
 * @param recreateClosed as {@link CreateIssuesRequest#recreateClosed()}: the one issue named, its issues all finished
 */
public record CreateSastIssuesRequest(@NotBlank String uploadId, @NotEmpty @Size(max = 500) List<String> issueIds,
                                      Map<String, Object> fields, List<String> jiraVersions,
                                      List<String> countries, boolean recreateClosed) {
}
