package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A field of an issue type's create screen ({@code GET /issue/createmeta/{project}/issuetypes/{id}}).
 * {@code allowedValues} differ per field (versions, components, custom options with {@code value}), hence
 * untyped; so is {@code defaultValue}.
 *
 * @param hasDefaultValue Jira fills a required field itself when the request leaves it out
 * @param schema          what the value is: {@code type} {@code array} = several, {@code items} / {@code type}
 *                        {@code user}, {@code number}, {@code option}, {@code component} ...
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraFieldMeta(String fieldId, String name, boolean required, boolean hasDefaultValue, Schema schema,
                            List<JsonNode> allowedValues, JsonNode defaultValue) {

    public static final String VERSIONS = "versions";
    public static final String COMPONENTS = "components";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schema(String type, String items, String system, String custom) {
    }

    /** The {@code name} of every allowed value that has one (versions, components). */
    public List<String> allowedNames() {
        if (allowedValues == null) {
            return List.of();
        }
        return allowedValues.stream()
                .map(v -> v.path("name").asString(null))
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }
}
