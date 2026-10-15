package com.creed.resource.veracode.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A finding as it is matched against Jira: every report row with the same match key and affected
 * version, merged.
 *
 * <p>The unit is {@code (CVE or title, affected version)} because that is the Jira lookup key. One CVE
 * hitting two libraries is two report rows but one Jira issue; keeping them as separate findings would
 * mark both "exists" off one issue, or create two issues for one vulnerability.
 *
 * @param id  stable across requests ({@code CVE-2021-44228@2.3.0}), so a create request can name
 *            findings and the server re-reads them instead of trusting a client-supplied copy
 */
public record Finding(String id, String cve, String title, List<String> components, String severity,
                      Double cvss, String affectedVersion, String fixedVersion, String description,
                      List<String> locations) {

    /** The text a Jira summary must contain for an issue to be this finding's. */
    public String matchText() {
        return cve != null ? cve : title;
    }

    public static List<Finding> group(List<ReportRow> rows) {
        Map<String, List<ReportRow>> byKey = new LinkedHashMap<>();
        for (ReportRow row : rows) {
            byKey.computeIfAbsent(idOf(row), k -> new ArrayList<>()).add(row);
        }
        return byKey.entrySet().stream()
                .map(e -> merge(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt((Finding f) -> Severity.rank(f.severity())).reversed()
                        .thenComparing(f -> f.cvss() == null ? -1 : f.cvss(), Comparator.reverseOrder())
                        .thenComparing(Finding::id))
                .toList();
    }

    private static String idOf(ReportRow row) {
        String key = row.cve() != null ? row.cve() : "title:" + normalise(row.title());
        return key + "@" + row.affectedVersion();
    }

    private static Finding merge(String id, List<ReportRow> rows) {
        ReportRow first = rows.getFirst();
        Set<String> components = new LinkedHashSet<>();
        Set<String> fixed = new LinkedHashSet<>();
        List<String> locations = new ArrayList<>();
        String title = null;
        String description = null;
        String severity = null;
        Double cvss = null;
        for (ReportRow row : rows) {
            addIfPresent(components, row.component());
            addIfPresent(fixed, row.fixedVersion());
            locations.add(row.location());
            title = title != null ? title : blankToNull(row.title());
            description = description != null ? description : blankToNull(row.description());
            if (blankToNull(row.severity()) != null
                    && (severity == null || Severity.rank(row.severity()) > Severity.rank(severity))) {
                severity = row.severity().trim();
            }
            if (row.cvss() != null && (cvss == null || row.cvss() > cvss)) {
                cvss = row.cvss();
            }
        }
        return new Finding(id, first.cve(), Objects.requireNonNullElse(title, first.cve()),
                List.copyOf(components), severity, cvss, first.affectedVersion(),
                fixed.isEmpty() ? null : String.join(", ", fixed), description, List.copyOf(locations));
    }

    /** Lower case, every run of non-alphanumerics one space - how titles are compared. */
    public static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (blankToNull(value) != null) {
            set.add(value.trim());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
