package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The paged shape of the {@code /issue/createmeta/...} resources. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraPage<T>(int startAt, int maxResults, Integer total, Boolean isLast, List<T> values) {

    public List<T> valuesOrEmpty() {
        return values == null ? List.of() : values;
    }
}
