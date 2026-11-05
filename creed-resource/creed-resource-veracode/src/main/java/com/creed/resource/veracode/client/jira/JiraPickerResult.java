package com.creed.resource.veracode.client.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * {@code GET /issue/picker}: issues in sections - {@code hs} (the caller's history) and {@code cs} (the
 * current search, i.e. {@code currentJQL}). The same issue can be in both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraPickerResult(List<Section> sections) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String id, String label, List<Item> issues) {
    }

    /** {@code summary} is HTML (the matched words in {@code <b>}); {@code summaryText} is plain. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String key, String summaryText) {
    }

    public List<String> keys() {
        if (sections == null) {
            return List.of();
        }
        return sections.stream()
                .filter(s -> s.issues() != null)
                .flatMap(s -> s.issues().stream())
                .map(Item::key)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
    }
}
