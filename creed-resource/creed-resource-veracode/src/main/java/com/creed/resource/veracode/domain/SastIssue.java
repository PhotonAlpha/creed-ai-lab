package com.creed.resource.veracode.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one SAST Jira issue is for: a CWE and a flaw category ({@code categoryname}), across files, lines and
 * applications - asked for (2026-11-08): the issue is found and filed by {@code {project} - CWE-89 - {category}}
 * alone. Only open flaws (neither fixed nor mitigated) make one: the others are never filed.
 *
 * @param id       stable across requests ({@code SAST|CWE-89|<category>}), so a create request names issues by id
 *                 and the server re-reads them
 * @param severity the highest of its flaws
 * @param flaws    its open flaws, the most severe first
 */
public record SastIssue(String id, String cwe, String cweName, String category, String severity,
                        List<SastFlaw> flaws, List<String> applications) {

    public String cweText() {
        return cwe != null ? cwe : Finding.UNKNOWN;
    }

    public String categoryText() {
        return category != null ? category : Finding.UNKNOWN;
    }

    /**
     * The issue a flaw belongs to, or {@code null} for one that is not filed (fixed or mitigated). Case and
     * separators of the category do not split an issue: the summary match ignores them too.
     */
    public static String idOf(SastFlaw flaw) {
        if (!flaw.open()) {
            return null;
        }
        String category = flaw.category() != null ? flaw.category().trim() : Finding.UNKNOWN;
        return "SAST|" + (flaw.cwe() != null ? flaw.cwe() : Finding.UNKNOWN) + "|" + Finding.normalise(category);
    }

    /** The open flaws grouped into issues, the most severe first. */
    public static List<SastIssue> group(List<SastFlaw> flaws) {
        Map<String, List<SastFlaw>> byId = new LinkedHashMap<>();
        for (SastFlaw flaw : flaws) {
            String id = idOf(flaw);
            if (id != null) {
                byId.computeIfAbsent(id, k -> new ArrayList<>()).add(flaw);
            }
        }
        Comparator<SastFlaw> bySeverity = Comparator.comparingInt((SastFlaw f) -> Severity.rank(f.severity())).reversed();
        return byId.entrySet().stream()
                .map(e -> {
                    List<SastFlaw> members = e.getValue().stream().sorted(bySeverity).toList();
                    SastFlaw first = members.getFirst();
                    Set<String> applications = new LinkedHashSet<>();
                    members.forEach(f -> {
                        if (f.application() != null) {
                            applications.add(f.application());
                        }
                    });
                    return new SastIssue(e.getKey(), first.cwe(), first.cweName(),
                            first.category() == null ? null : first.category().trim(), first.severity(),
                            members, List.copyOf(applications));
                })
                .sorted(Comparator.comparingInt((SastIssue i) -> Severity.rank(i.severity())).reversed()
                        .thenComparing(SastIssue::id))
                .toList();
    }
}
