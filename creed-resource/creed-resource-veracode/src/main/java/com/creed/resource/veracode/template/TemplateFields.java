package com.creed.resource.veracode.template;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.api.dto.SastIssueView;
import com.creed.resource.veracode.domain.ApplicationAliases;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import com.creed.resource.veracode.service.ReportView;
import com.creed.resource.veracode.service.SastSummary;
import com.creed.resource.veracode.service.ScaSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a template cell can show, and the values at export: the rows of the page's two tables - SCA findings
 * ({@value #SCA}) and SAST Jira issues ({@value #SAST}) - the per-report SCA / SAST statistics ({@value #SCA_STATS},
 * {@value #SAST_STATS}: the page's two statistics tables, one row per report) and one {@value #SUMMARY} of the analysis. The keys are the
 * JXLS expressions' property names ({@code ${r.cveId}}, {@code ${summary.projectKey}}): renaming one breaks every saved
 * mapping that uses it.
 */
public final class TemplateFields {

    public static final String SCA = "sca";
    public static final String SAST = "sast";
    public static final String SCA_STATS = "scaStats";
    public static final String SAST_STATS = "sastStats";
    public static final String SUMMARY = "summary";

    /** The datasets a data row can repeat: every one but {@value #SUMMARY}. */
    public static boolean isList(String dataset) {
        return dataset != null && !SUMMARY.equals(dataset) && CATALOG.containsKey(dataset);
    }

    /** @param label English; the page translates the keys it knows */
    public record Field(String key, String label) {
    }

    /** Every dataset's fields, in the order the page offers them. */
    public static final Map<String, List<Field>> CATALOG = catalog();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TemplateFields() {
    }

    private static Map<String, List<Field>> catalog() {
        Map<String, List<Field>> catalog = new LinkedHashMap<>();
        catalog.put(SCA, List.of(
                new Field("no", "No. (1, 2, ...)"),
                new Field("application", "Veracode application"),
                new Field("title", "Title (CVE|CWE)"),
                new Field("cveId", "CVE"),
                new Field("cwe", "CWE"),
                new Field("fileName", "Library file"),
                new Field("components", "Components"),
                new Field("severity", "Severity"),
                new Field("cvss", "CVSS"),
                new Field("affectedVersion", "Affected version"),
                new Field("description", "Description"),
                new Field("status", "Status"),
                new Field("jiraSummary", "Jira summary"),
                new Field("jiraKeys", "Jira keys"),
                new Field("jiraStatuses", "Jira statuses"),
                new Field("jiraAssignees", "Jira assignees"),
                new Field("jiraUrls", "Jira links")));
        catalog.put(SAST, List.of(
                new Field("no", "No. (1, 2, ...)"),
                new Field("summary", "Jira summary"),
                new Field("details", "Details (per application, as on the page)"),
                new Field("cwe", "CWE"),
                new Field("cweName", "CWE name"),
                new Field("category", "Category"),
                new Field("severity", "Severity"),
                new Field("flawCount", "Open flaws"),
                new Field("flawIds", "Flaw IDs"),
                new Field("applications", "Veracode applications"),
                new Field("status", "Status"),
                new Field("jiraKeys", "Jira keys"),
                new Field("jiraStatuses", "Jira statuses"),
                new Field("jiraUrls", "Jira links")));
        catalog.put(SCA_STATS, statsFields(List.of(
                new Field("thirdPartyComponents", "Third-party components"),
                new Field("violatesPolicy", "Violates policy"),
                new Field("componentsViolatedPolicy", "Components violating policy"),
                new Field("blacklistedComponents", "Blacklisted components"),
                new Field("vulnerableComponents", "Vulnerable components"),
                new Field("vulnerabilities", "Vulnerabilities"),
                new Field("mitigated", "Mitigated")), List.of(new Field("maxCvss", "Highest CVSS"))));
        catalog.put(SAST_STATS, statsFields(List.of(
                new Field("rating", "Rating"),
                new Field("score", "Score"),
                new Field("totalFlaws", "Total flaws (Veracode)"),
                new Field("flawsNotMitigated", "Flaws not mitigated (Veracode)"),
                new Field("flaws", "Flaws listed"),
                new Field("open", "Open"),
                new Field("mitigated", "Mitigated"),
                new Field("fixed", "Fixed"),
                new Field("affectsPolicy", "Open, affecting policy")), List.of()));
        catalog.put(SUMMARY, List.of(
                new Field("projectKey", "Jira project"),
                new Field("jiraVersions", "Jira versions searched"),
                new Field("countries", "Countries searched"),
                new Field("applications", "Veracode applications"),
                new Field("reports", "Report files"),
                new Field("versions", "Affected versions"),
                new Field("analyzedAt", "Analysed at"),
                new Field("exportedAt", "Exported at"),
                new Field("scaTotal", "SCA findings"),
                new Field("scaExists", "SCA in Jira"),
                new Field("scaMissing", "SCA missing"),
                new Field("scaVeryHigh", "SCA Very High"),
                new Field("scaHigh", "SCA High"),
                new Field("scaMedium", "SCA Medium"),
                new Field("scaLow", "SCA Low"),
                new Field("sastTotal", "SAST issues"),
                new Field("sastExists", "SAST in Jira"),
                new Field("sastMissing", "SAST missing")));
        return java.util.Collections.unmodifiableMap(catalog);
    }

    /** A statistics row: which report, its own fields, the six severity counts, then {@code tail}. */
    private static List<Field> statsFields(List<Field> own, List<Field> tail) {
        List<Field> fields = new ArrayList<>(List.of(
                new Field("no", "No. (1, 2, ...)"),
                new Field("application", "Veracode application"),
                new Field("report", "Report file"),
                new Field("scanVersion", "Scan version")));
        fields.addAll(own);
        for (String level : ScaSummary.LEVELS) {
            fields.add(new Field(severityKey(level), level));
        }
        fields.add(new Field("unknownSeverity", "Unknown severity"));
        fields.addAll(tail);
        return List.copyOf(fields);
    }

    /** {@code Very High} -> {@code veryHigh}. */
    static String severityKey(String level) {
        String camel = java.util.Arrays.stream(level.toLowerCase(java.util.Locale.ROOT).split(" "))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining());
        return Character.toLowerCase(camel.charAt(0)) + camel.substring(1);
    }

    public static boolean known(String dataset, String field) {
        return CATALOG.getOrDefault(dataset, List.of()).stream().anyMatch(f -> f.key().equals(field));
    }

    /**
     * The JXLS context: {@code sca} and {@code sast} lists of rows, {@code summary} one map. Plain maps - JEXL reads
     * {@code r.cveId} off a map as off a bean - with every catalog key present ({@code ""} rather than missing, so an
     * empty value is an empty cell, not the expression left in it).
     */
    public static Map<String, Object> data(AnalysisResponse a, ApplicationAliases apps, Instant now, ZoneId zone) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(SCA, numbered(a.findings().stream().map(f -> sca(f, apps)).toList()));
        List<SastIssueView> sastIssues = a.sast() == null ? List.of() : a.sast().issues();
        data.put(SAST, numbered(sastIssues.stream().map(i -> sast(i, apps)).toList()));
        data.put(SCA_STATS, numbered(a.reports().stream().map(r -> scaStats(r, apps)).toList()));
        data.put(SAST_STATS, numbered(a.reports().stream().map(r -> sastStats(r, apps)).toList()));
        data.put(SUMMARY, summary(a, sastIssues, apps, now, zone));
        return data;
    }

    /** {@code no}: the row's position, from 1 - a template's "No." column. */
    private static List<Map<String, Object>> numbered(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", i + 1);
            row.putAll(rows.get(i));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> sca(FindingView f, ApplicationAliases apps) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("application", text(f.application() == null ? null : apps.name(f.application())));
        row.put("title", text(f.title()));
        row.put("cveId", text(f.cveId()));
        row.put("cwe", text(f.cwe()));
        row.put("fileName", text(f.fileName()));
        row.put("components", String.join(", ", f.components()));
        row.put("severity", text(f.severity()));
        row.put("cvss", f.cvss() == null ? "" : f.cvss());
        row.put("affectedVersion", text(f.affectedVersion()));
        row.put("description", text(f.description()));
        row.put("status", f.status() == null ? "" : f.status().name());
        row.put("jiraSummary", text(f.summary()));
        putIssues(row, f.issues(), true);
        return row;
    }

    private static Map<String, Object> sast(SastIssueView i, ApplicationAliases apps) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("summary", text(i.summary()));
        // the page's "Jira summary" column: three lines per application, a blank line between applications
        row.put("details", i.byApplication().stream()
                .map(b -> b.name() + ": " + b.heading() + "\n" + b.cwe() + "\n" + b.flawIds())
                .collect(Collectors.joining("\n\n")));
        row.put("cwe", text(i.cwe()));
        row.put("cweName", text(i.cweName()));
        row.put("category", text(i.category()));
        row.put("severity", text(i.severity()));
        row.put("flawCount", i.flaws().size());
        row.put("flawIds", i.flaws().stream().map(SastFlaw::issueId).filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparingLong(TemplateFields::number).thenComparing(Function.identity()))
                .collect(Collectors.joining(", ")));
        row.put("applications", i.applications().stream().map(apps::name).filter(Objects::nonNull)
                .collect(Collectors.joining(", ")));
        row.put("status", i.status() == null ? "" : i.status().name());
        putIssues(row, i.issues(), false);
        return row;
    }

    private static Map<String, Object> report(ReportView r, ApplicationAliases apps) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("application", text(r.appName() == null ? null : apps.name(r.appName())));
        row.put("report", text(r.fileName()));
        row.put("scanVersion", text(r.scanVersion()));
        return row;
    }

    /**
     * A report without an SCA section has every stated value {@code ""}, not 0 - 0 would claim SCA found nothing
     * (as on the page).
     */
    private static Map<String, Object> scaStats(ReportView r, ApplicationAliases apps) {
        Map<String, Object> row = report(r, apps);
        ScaSummary s = r.sca();
        row.put("thirdPartyComponents", value(s == null ? null : s.thirdPartyComponents()));
        row.put("violatesPolicy", s == null || s.violatesPolicy() == null ? "" : s.violatesPolicy() ? "Yes" : "No");
        row.put("componentsViolatedPolicy", value(s == null ? null : s.componentsViolatedPolicy()));
        row.put("blacklistedComponents", value(s == null ? null : s.blacklistedComponents()));
        row.put("vulnerableComponents", value(s == null ? null : s.vulnerableComponents()));
        row.put("vulnerabilities", value(s == null ? null : s.vulnerabilities()));
        row.put("mitigated", value(s == null ? null : s.mitigated()));
        putSeverities(row, s == null ? null : s.bySeverity());
        row.put("unknownSeverity", value(s == null ? null : s.unknownSeverity()));
        row.put("maxCvss", value(s == null ? null : s.maxCvss()));
        return row;
    }

    private static Map<String, Object> sastStats(ReportView r, ApplicationAliases apps) {
        Map<String, Object> row = report(r, apps);
        SastSummary s = r.sast();
        row.put("rating", value(s == null ? null : s.rating()));
        row.put("score", value(s == null ? null : s.score()));
        row.put("totalFlaws", value(s == null ? null : s.totalFlaws()));
        row.put("flawsNotMitigated", value(s == null ? null : s.flawsNotMitigated()));
        row.put("flaws", value(s == null ? null : s.flaws()));
        row.put("open", value(s == null ? null : s.open()));
        row.put("mitigated", value(s == null ? null : s.mitigated()));
        row.put("fixed", value(s == null ? null : s.fixed()));
        row.put("affectsPolicy", value(s == null ? null : s.affectsPolicy()));
        putSeverities(row, s == null ? null : s.bySeverity());
        row.put("unknownSeverity", value(s == null ? null : s.unknownSeverity()));
        return row;
    }

    private static void putSeverities(Map<String, Object> row, List<ScaSummary.SeverityCount> counts) {
        for (String level : ScaSummary.LEVELS) {
            row.put(severityKey(level), counts == null ? "" : counts.stream()
                    .filter(c -> level.equalsIgnoreCase(c.severity())).mapToInt(ScaSummary.SeverityCount::count).sum());
        }
    }

    /** A number stays a number (Excel can sum it); none is an empty cell. */
    private static Object value(Object value) {
        return value == null ? "" : value;
    }

    private static void putIssues(Map<String, Object> row, List<IssueRef> issues, boolean assignees) {
        row.put("jiraKeys", issues.stream().map(IssueRef::key).collect(Collectors.joining(", ")));
        row.put("jiraStatuses", issues.stream().map(IssueRef::status).filter(Objects::nonNull)
                .collect(Collectors.joining(", ")));
        if (assignees) {
            row.put("jiraAssignees", issues.stream().map(IssueRef::assignee).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(", ")));
        }
        row.put("jiraUrls", issues.stream().map(IssueRef::url).filter(Objects::nonNull)
                .collect(Collectors.joining("\n")));
    }

    private static Map<String, Object> summary(AnalysisResponse a, List<SastIssueView> sastIssues,
                                               ApplicationAliases apps, Instant now, ZoneId zone) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("projectKey", text(a.projectKey()));
        s.put("jiraVersions", a.selectedVersions().isEmpty() ? "(any)" : String.join(", ", a.selectedVersions()));
        List<String> countries = a.selectedCountries() == null ? List.of() : a.selectedCountries();
        s.put("countries", countries.isEmpty() ? "(any)" : String.join(", ", countries));
        s.put("applications", a.reports().stream().map(ReportView::appName).filter(Objects::nonNull).distinct()
                .map(apps::name).collect(Collectors.joining(", ")));
        s.put("reports", a.reports().stream().map(ReportView::fileName).collect(Collectors.joining(", ")));
        s.put("versions", a.reports().stream().map(ReportView::affectedVersion).filter(Objects::nonNull).distinct()
                .collect(Collectors.joining(", ")));
        s.put("analyzedAt", a.analyzedAt() == null ? "" : TIME.withZone(zone).format(a.analyzedAt()));
        s.put("exportedAt", TIME.withZone(zone).format(now));
        s.put("scaTotal", a.counts().total());
        s.put("scaExists", a.counts().exists());
        s.put("scaMissing", a.counts().missing());
        s.put("scaVeryHigh", severity(a.findings(), "Very High"));
        s.put("scaHigh", severity(a.findings(), "High"));
        s.put("scaMedium", severity(a.findings(), "Medium"));
        s.put("scaLow", severity(a.findings(), "Low"));
        List<SastIssueView> exists = new ArrayList<>(sastIssues);
        exists.removeIf(i -> i.issues().isEmpty());
        s.put("sastTotal", sastIssues.size());
        s.put("sastExists", exists.size());
        s.put("sastMissing", sastIssues.size() - exists.size());
        return s;
    }

    private static long severity(List<FindingView> findings, String severity) {
        return findings.stream().filter(f -> severity.equalsIgnoreCase(f.severity())).count();
    }

    private static String text(String value) {
        return value == null ? "" : value;
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
