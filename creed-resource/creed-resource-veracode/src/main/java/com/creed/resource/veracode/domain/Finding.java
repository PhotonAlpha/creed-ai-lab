package com.creed.resource.veracode.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A finding as the page shows it and Jira files it: one vulnerability ({@code cve_id}) in one component file
 * ({@code file_name}) of one Veracode application - asked for (2026-11-06): the same CVE in two jars is two issues.
 *
 * <p>{@link #issueKey} is what an issue is for, without the application: the same CVE in the same jar reported by
 * two applications is one Jira issue, labelled with both. A vulnerability without a {@code cve_id} is "Unknown"
 * and is told apart by its {@code cwe_id} instead.
 *
 * @param id          stable across requests ({@code creed-platform|CVE-2021-44228@log4j-core-2.14.1.jar}), so a
 *                    create request can name findings and the server re-reads them instead of trusting a copy
 * @param application the report's Veracode application ({@code app_name})
 * @param cveId       as the report gives it (a CVE or an advisory id), {@code null} = Unknown
 * @param cve         {@code cveId} when it is a CVE ({@code CVE-yyyy-n}) - NVD links and the fuzzy CVE match
 * @param description the full {@code cve_summary}
 */
public record Finding(String id, String application, String cveId, String cve, String cwe, String fileName,
                      List<String> components, String severity, Double cvss, String affectedVersion,
                      String description, List<String> locations) {

    /** What stands in for a missing {@code cve_id} / {@code cwe_id}, in the title and in created summaries. */
    public static final String UNKNOWN = "Unknown";
    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d{4,}", Pattern.CASE_INSENSITIVE);

    /** {@code CVE-2021-44228|CWE-502} - the page's title column, and the summary's middle part. */
    public String title() {
        return cveText() + "|" + cweText();
    }

    public String cveText() {
        return cveId != null ? cveId : UNKNOWN;
    }

    public String cweText() {
        return cwe != null ? cwe : UNKNOWN;
    }

    /** The Jira issue this finding belongs to, whichever application reported it. */
    public String issueKey() {
        return (cveId != null ? cveId : UNKNOWN + "|" + cweText()) + "@" + fileName;
    }

    public static List<Finding> group(List<ReportRow> rows) {
        Map<String, List<ReportRow>> byKey = new LinkedHashMap<>();
        for (ReportRow row : rows) {
            byKey.computeIfAbsent(idOf(row), k -> new ArrayList<>()).add(row);
        }
        return byKey.entrySet().stream()
                .map(e -> merge(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing((Finding f) -> f.application() == null ? "" : f.application())
                        .thenComparing(Comparator.comparingInt((Finding f) -> Severity.rank(f.severity())).reversed())
                        .thenComparing(f -> f.cvss() == null ? -1 : f.cvss(), Comparator.reverseOrder())
                        .thenComparing(Finding::id))
                .toList();
    }

    private static String idOf(ReportRow row) {
        String key = (row.cveId() != null ? row.cveId() : UNKNOWN + "|" + (row.cwe() != null ? row.cwe() : UNKNOWN))
                + "@" + row.fileName();
        return (row.application() == null ? "" : row.application()) + "|" + key;
    }

    /** Rows of one key: the same vulnerability listed twice for one jar (two paths) - one finding. */
    private static Finding merge(String id, List<ReportRow> rows) {
        ReportRow first = rows.getFirst();
        Set<String> components = new LinkedHashSet<>();
        List<String> locations = new ArrayList<>();
        String description = null;
        String severity = null;
        Double cvss = null;
        for (ReportRow row : rows) {
            addIfPresent(components, row.component());
            locations.add(row.location());
            description = description != null ? description : blankToNull(row.summary());
            if (blankToNull(row.severity()) != null
                    && (severity == null || Severity.rank(row.severity()) > Severity.rank(severity))) {
                severity = row.severity().trim();
            }
            if (row.cvss() != null && (cvss == null || row.cvss() > cvss)) {
                cvss = row.cvss();
            }
        }
        String cve = first.cveId() != null && CVE.matcher(first.cveId()).matches() ? first.cveId() : null;
        return new Finding(id, first.application(), first.cveId(), cve, first.cwe(), first.fileName(),
                List.copyOf(components), severity, cvss, first.affectedVersion(),
                description, List.copyOf(locations));
    }

    /** Lower case, every run of non-alphanumerics one space - how summaries are compared. */
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
