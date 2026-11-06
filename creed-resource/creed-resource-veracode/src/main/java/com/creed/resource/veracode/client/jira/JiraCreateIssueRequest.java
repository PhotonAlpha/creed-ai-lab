package com.creed.resource.veracode.client.jira;

import java.util.Map;

/**
 * {@code {"fields": {...}}}. A map, not a record: besides the fields this service always sets (project, issue
 * type, summary, description, versions, labels) it carries whatever the create screen requires - Component/s,
 * custom fields - in the JSON shape each one takes ({@code {"id": ...}}, a list of them, a string ...).
 */
public record JiraCreateIssueRequest(Map<String, Object> fields) {
}
