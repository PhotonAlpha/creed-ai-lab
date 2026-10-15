package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Jira's error envelope: {@code {"errorMessages": [...], "errors": {"field": "message"}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraErrorBody(List<String> errorMessages, Map<String, String> errors) {

    public String describe() {
        List<String> parts = new ArrayList<>();
        if (errorMessages != null) {
            parts.addAll(errorMessages);
        }
        if (errors != null) {
            errors.forEach((field, message) -> parts.add(field + ": " + message));
        }
        return String.join("; ", parts);
    }
}
