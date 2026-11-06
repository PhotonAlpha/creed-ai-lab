package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A page of a JQL search, issues with their fields inline. Data Center ({@code /rest/api/2/search}) pages by
 * {@code startAt} / {@code total}; Jira Cloud ({@code /rest/api/3/search/jql}) by {@code nextPageToken} /
 * {@code isLast} and sends no {@code total}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraSearchResult(Integer startAt, Integer total, Boolean isLast, String nextPageToken,
                               List<JiraIssue> issues) {

    public List<JiraIssue> issuesOrEmpty() {
        return issues == null ? List.of() : issues;
    }
}
