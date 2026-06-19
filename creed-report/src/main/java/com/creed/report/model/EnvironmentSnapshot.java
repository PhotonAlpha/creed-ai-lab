package com.creed.report.model;

import java.util.List;

/**
 * The full result of inspecting a manually assembled {@code ConfigurableEnvironment}.
 *
 * @param activeProfiles  the profiles Spring Boot activated
 * @param defaultProfiles the default profiles (when no active profile is set)
 * @param propertySources every property source, ordered from highest to lowest precedence
 * @param effective       the effective property view (first source to define a key wins)
 */
public record EnvironmentSnapshot(List<String> activeProfiles,
                                  List<String> defaultProfiles,
                                  List<PropertySourceView> propertySources,
                                  List<PropertyEntry> effective) {
}
