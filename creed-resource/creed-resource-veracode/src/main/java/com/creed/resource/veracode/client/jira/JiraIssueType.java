package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An issue type the caller may create in the project ({@code GET /issue/createmeta/{project}/issuetypes}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraIssueType(String id, String name, Boolean subtask) {
}
