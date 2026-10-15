package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraCreatedIssue(String id, String key, String self) {
}
