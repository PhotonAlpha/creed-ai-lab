package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JiraVersion(String id, String name, Boolean released, Boolean archived) {

    /** The shape a create request references a version by. */
    public static JiraVersion named(String name) {
        return new JiraVersion(null, name, null, null);
    }
}
