package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A field of an issue type's create screen ({@code GET /issue/createmeta/{project}/issuetypes/{id}}).
 * {@code allowedValues} differ per field (versions, options, plain strings), hence untyped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraFieldMeta(String fieldId, String name, boolean required, List<JsonNode> allowedValues) {

    public static final String VERSIONS = "versions";
}
