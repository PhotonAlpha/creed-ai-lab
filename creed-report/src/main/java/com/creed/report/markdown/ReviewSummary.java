package com.creed.report.markdown;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two-column summary table of a review document ({@code | Item | Result |}), in document order.
 *
 * @param heading the heading the table sits under ({@code 1. Manager Summary}); null when the table
 *                was found without one
 * @param items   item → result as plain text (Markdown emphasis, links and code stripped), keys as written
 */
public record ReviewSummary(String heading, Map<String, String> items) {

    /** The result for {@code item}, matched case-insensitively and ignoring surrounding spaces. */
    public Optional<String> get(String item) {
        String wanted = item.strip().toLowerCase(Locale.ROOT);
        return items.entrySet().stream()
                .filter(e -> e.getKey().strip().toLowerCase(Locale.ROOT).equals(wanted))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public Optional<Verdict> verdict() {
        return get("Verdict").map(Verdict::of);
    }
}
