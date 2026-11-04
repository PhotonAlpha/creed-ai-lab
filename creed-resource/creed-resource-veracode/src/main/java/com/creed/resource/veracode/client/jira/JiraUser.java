package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /myself} - who a token belongs to. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraUser(String name, String key, String displayName, String emailAddress, Boolean active) {
}
