package com.creed.resource.veracode.client.jira;

import java.util.List;

public record JiraSearchRequest(String jql, int startAt, int maxResults, List<String> fields) {
}
