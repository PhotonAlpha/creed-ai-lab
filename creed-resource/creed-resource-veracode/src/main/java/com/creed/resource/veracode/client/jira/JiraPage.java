package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The paged shape of the {@code /issue/createmeta/...} resources. Data Center puts the items in {@code values};
 * Jira Cloud names them after what they are - {@code issueTypes} for the issue types, {@code fields} for a type's
 * fields - and sends no {@code isLast}. Without the aliases a Cloud project reads as "no issue type creatable".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraPage<T>(int startAt, int maxResults, Integer total, Boolean isLast,
                          @JsonAlias({"issueTypes", "fields"}) List<T> values) {

    public List<T> valuesOrEmpty() {
        return values == null ? List.of() : values;
    }
}
