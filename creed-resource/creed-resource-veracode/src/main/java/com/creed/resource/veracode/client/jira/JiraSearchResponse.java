package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraSearchResponse(int startAt, int maxResults, int total, List<JiraIssue> issues) {

    public List<JiraIssue> issuesOrEmpty() {
        return issues == null ? List.of() : issues;
    }
}
