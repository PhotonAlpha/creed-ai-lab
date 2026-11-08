package com.creed.resource.veracode.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * @param parentPageId the page the report page is created under (an id, or the page's link - the page parses it)
 * @param project      {@code {project}} in the title; blank = the reports' Veracode application(s)
 * @param version      {@code {version}} in the title; blank = the reports' version(s)
 * @param jiraVersions the SCA Jira versions to look up in, as on the page
 */
public record ConfluenceExportRequest(@NotBlank String uploadId, @NotBlank String parentPageId, String project,
                                      String version, List<String> jiraVersions) {
}
