package com.creed.report.model;

import java.util.List;

/**
 * The active (effective) properties of an inspected environment, sorted alphabetically by
 * key and rendered into two textual forms.
 *
 * @param activeProfiles  the profiles Spring Boot activated
 * @param effective       the effective properties, sorted alphabetically by key
 * @param propertiesText  the same properties rendered in {@code key=value} (.properties) form
 * @param yamlText        the same properties rendered as nested YAML
 */
public record RenderedEnvironment(List<String> activeProfiles,
                                  List<PropertyEntry> effective,
                                  String propertiesText,
                                  String yamlText) {
}
