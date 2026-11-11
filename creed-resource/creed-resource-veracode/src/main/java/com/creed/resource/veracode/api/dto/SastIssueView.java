package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.config.ApplicationProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One SAST Jira issue as the page shows it: what it is for, its summary (what is searched and what a create files),
 * and what Jira holds.
 *
 * @param summary {@code {project} - CWE-89 - {category}}: found by it, created with it
 * @param status  {@code null} when Jira could not be read for SAST
 * @param flaws   its open flaws
 * @param missingLabels the application labels its Jira issue lacks - an application whose flaws the issue already
 *                      covers is labelled there, not filed again (as SCA); {@code needsLabel} = some are missing
 * @param byApplication the issue per application, as the page and the Confluence export show it (asked for,
 *                      2026-11-10) - display only, the Jira summary stays {@code summary}
 * @param recreatable   its issues are all finished ({@code closed-statuses}): it may be filed again, confirmed
 */
public record SastIssueView(String id, String summary, String cwe, String cweName, String category, String severity,
                            List<SastFlaw> flaws, List<String> applications, FindingStatus status,
                            List<IssueRef> issues, boolean needsLabel, List<String> missingLabels,
                            List<ApplicationBlock> byApplication, boolean recreatable) {

    /**
     * Three lines per application:
     * <pre>
     * Creed-CCS: IDB VERACODE STSA - CRLF Injection
     * CWE 117 Improper Output Neutralization for Logs
     * CWE 117 (Flaw IDs: 257, 258)
     * </pre>
     *
     * @param name    the application's mapped name ({@code veracode.applications}), else its {@code app_name}
     * @param url     its link; {@code null} without a mapping
     * @param heading what follows {@code name: } - {@code {sast project-name} - {category}}
     * @param cwe     {@code CWE 117 Improper Output Neutralization for Logs}
     * @param flawIds {@code CWE 117 (Flaw IDs: 257, 258)}: the application's open flaws of the issue
     */
    public record ApplicationBlock(String appName, String name, String url, String heading, String cwe,
                                   String flawIds) {
    }

    public static SastIssueView of(SastIssue i, String summary, FindingStatus status, List<IssueRef> issues,
                                   List<String> missingLabels, String project, ApplicationProperties apps,
                                   boolean recreatable) {
        return new SastIssueView(i.id(), summary, i.cwe(), i.cweName(), i.category(), i.severity(), i.flaws(),
                i.applications(), status, List.copyOf(issues), !missingLabels.isEmpty(), List.copyOf(missingLabels),
                blocks(i, project, apps), recreatable);
    }

    /** Also the head of a created SAST issue's description ({@code JiraDescription.ofSast}): the page's column, as filed. */
    public static List<ApplicationBlock> blocks(SastIssue i, String project, ApplicationProperties apps) {
        // "CWE-117" reads "CWE 117" here, as asked
        String cwe = i.cwe() == null ? Finding.UNKNOWN : i.cwe().replaceFirst("(?i)^CWE-", "CWE ");
        String cweLine = i.cweName() == null || i.cweName().isBlank() ? cwe : cwe + " " + i.cweName().trim();
        Map<String, List<SastFlaw>> byApp = new LinkedHashMap<>();
        i.flaws().forEach(f -> byApp.computeIfAbsent(Objects.requireNonNullElse(f.application(), ""),
                k -> new ArrayList<>()).add(f));
        return byApp.entrySet().stream().map(e -> {
            String appName = e.getKey().isEmpty() ? null : e.getKey();
            String name = Objects.requireNonNullElse(apps.name(appName), Finding.UNKNOWN);
            List<String> ids = e.getValue().stream().map(SastFlaw::issueId).filter(Objects::nonNull).distinct()
                    .sorted(Comparator.comparingLong(SastIssueView::number).thenComparing(Comparator.naturalOrder()))
                    .toList();
            return new ApplicationBlock(appName, name, apps.url(appName), project + " - " + i.categoryText(), cweLine,
                    cwe + " (Flaw IDs: " + (ids.isEmpty() ? "—" : String.join(", ", ids)) + ")");
        }).sorted(Comparator.comparing(b -> b.name().toLowerCase(Locale.ROOT))).toList();
    }

    /** Flaw ids are numbers: 9 before 10. */
    private static long number(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
