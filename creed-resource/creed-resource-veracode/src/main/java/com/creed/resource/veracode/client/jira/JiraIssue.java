package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraIssue(String id, String key, Fields fields) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fields(String summary, Status status, List<JiraVersion> versions, List<String> labels,
                         Named priority, Person assignee, Project project) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Named(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(String name, String displayName) {
    }

    public String summary() {
        return fields == null || fields.summary() == null ? "" : fields.summary();
    }

    public String projectKey() {
        return fields == null || fields.project() == null ? null : fields.project().key();
    }

    public String statusName() {
        return fields == null || fields.status() == null ? null : fields.status().name();
    }

    public String priorityName() {
        return fields == null || fields.priority() == null ? null : fields.priority().name();
    }

    /** Display name, else login; {@code null} = unassigned. */
    public String assigneeName() {
        if (fields == null || fields.assignee() == null) {
            return null;
        }
        Person p = fields.assignee();
        return p.displayName() != null && !p.displayName().isBlank() ? p.displayName() : p.name();
    }

    public List<String> versionNames() {
        if (fields == null || fields.versions() == null) {
            return List.of();
        }
        return fields.versions().stream().map(JiraVersion::name).toList();
    }
}
