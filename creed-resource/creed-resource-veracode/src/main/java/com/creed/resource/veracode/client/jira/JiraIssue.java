package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraIssue(String id, String key, Fields fields) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fields(String summary, Status status, List<JiraVersion> versions, List<String> labels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String name) {
    }

    public String summary() {
        return fields == null || fields.summary() == null ? "" : fields.summary();
    }

    public String statusName() {
        return fields == null || fields.status() == null ? null : fields.status().name();
    }

    public List<String> versionNames() {
        if (fields == null || fields.versions() == null) {
            return List.of();
        }
        return fields.versions().stream().map(JiraVersion::name).toList();
    }
}
