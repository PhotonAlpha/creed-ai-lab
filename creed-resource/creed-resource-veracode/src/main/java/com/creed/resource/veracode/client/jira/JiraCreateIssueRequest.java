package com.creed.resource.veracode.client.jira;

import java.util.List;

/** {@code {"fields": {...}}} - only the fields this service sets. */
public record JiraCreateIssueRequest(Fields fields) {

    public record Fields(Key project, Name issuetype, String summary, String description,
                         List<JiraVersion> versions, List<String> labels) {
    }

    public record Key(String key) {
    }

    public record Name(String name) {
    }
}
