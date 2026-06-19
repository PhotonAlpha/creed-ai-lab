package com.creed.report.model;

/**
 * A single property resolved from the environment.
 *
 * @param name       the property key
 * @param raw        the raw value as stored in the originating source (placeholders intact)
 * @param resolved   the value after placeholder resolution against the whole environment
 * @param source     the name of the property source that supplied this value
 * @param unresolved whether the value still contains an unresolved {@code ${...}} placeholder
 */
public record PropertyEntry(String name, String raw, String resolved, String source, boolean unresolved) {
}
