package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.api.dto.SastIssueView;
import com.creed.resource.veracode.config.ConfluenceProperties;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.Severity;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import com.creed.resource.veracode.service.VeracodeAnalysisService.UploadedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * An analysis as a Confluence page under a parent the user names: the layout of {@code sample/confluence-template.png}
 * (SCA / SAST summary by severity, the SCA findings, the SAST issues), rendered from
 * {@code templates/veracode/confluence-report.html} into Confluence's storage format and created with the user's own
 * Confluence token. The analysis is a fresh one, as for the Excel export: the page states what Jira holds now.
 * The report files the analysis read (as uploaded, or as downloaded from Confluence) are attached to the page and
 * linked from its last line.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfluenceExportService {

    static final String TEMPLATE = "veracode/confluence-report";
    /** The template's columns, always shown; the other levels only when something has them. */
    private static final List<String> BASE_LEVELS = List.of("Very High", "High", "Medium", "Low");
    private static final List<String> OTHER_LEVELS = List.of("Very Low", "Informational");
    static final String NOT_IN_JIRA = "Not in Jira";

    private final VeracodeAnalysisService analysis;
    private final ConfluenceService confluence;
    private final ConfluenceProperties props;
    private final ITemplateEngine templates;
    private final Clock clock;

    public record Level(String severity, int total) {
    }

    public record SummaryRow(String label, int total, List<Integer> counts) {
    }

    /**
     * @param description  the Jira summary - the issue's, else the one it would be filed under
     * @param repositories the Veracode applications: one row per Jira issue, so a CVE + jar that several
     *                     repositories share in one issue is one row naming them all (one per line)
     */
    public record ScaRow(int no, List<IssueRef> issues, String description, String severity,
                         List<String> repositories, String status) {
    }

    /** @param count the issue's open flaws */
    public record SastRow(int no, List<IssueRef> issues, String details, String severity, int count,
                          String repository, String status) {
    }

    /**
     * What the template renders.
     *
     * @param files  the report files, attached to the page and linked by name from the source line
     * @param source the rest of that line: the Jira projects searched, when
     */
    public record Report(List<Level> levels, List<SummaryRow> summary, List<ScaRow> sca, List<SastRow> sast,
                         List<String> files, String source) {
    }

    /**
     * @param project      {@code {project}} in the title; blank = the reports' Veracode application(s)
     * @param version      {@code {version}}; blank = the reports' version(s)
     * @param jiraVersions the SCA Jira versions to look up in, as for the analysis
     */
    public ConfluenceService.CreatedPage export(String uploadId, String parentPageId, String project, String version,
                                                List<String> jiraVersions, JiraToken jiraToken,
                                                ConfluenceToken confluenceToken) {
        confluenceToken.require();
        AnalysisResponse result = analysis.analyze(uploadId, false, Map.of(), jiraVersions, jiraToken);
        List<UploadedFile> files = attachments(analysis.sourceFiles(uploadId));
        String title = title(result, project, version);
        String storage = render(result, files.stream().map(UploadedFile::filename).toList());
        ConfluenceService.CreatedPage page = confluence.createChildPage(parentPageId, title, storage, confluenceToken);
        // the page is there either way: a failed upload is reported with it, not as the export failing
        try {
            return page.withAttachments(confluence.attach(page.id(), files, confluenceToken), null);
        } catch (RuntimeException e) {
            log.warn("Confluence page {} created, attaching {} failed: {}", page.id(),
                    files.stream().map(UploadedFile::filename).toList(), e.getMessage());
            return page.withAttachments(List.of(), e.getMessage());
        }
    }

    /**
     * The upload's files, each under a name of its own: Confluence makes a second file of the same name a new
     * version of the first, which would leave the page one report short.
     */
    static List<UploadedFile> attachments(List<UploadedReportStore.SourceFile> files) {
        Set<String> taken = new HashSet<>();
        List<UploadedFile> named = new ArrayList<>();
        for (UploadedReportStore.SourceFile f : files) {
            String name = blank(f.filename()) ? "veracode-report" : f.filename().replaceAll(".*[/\\\\]", "");
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            String unique = name;
            for (int n = 2; !taken.add(unique.toLowerCase(Locale.ROOT)); n++) {
                unique = stem + " (" + n + ")" + ext;
            }
            named.add(new UploadedFile(unique, f.content()));
        }
        return named;
    }

    /** {@code export-title} filled in; the page's values win over the reports'. */
    String title(AnalysisResponse result, String project, String version) {
        String projects = String.join(", ", distinct(result.reports().stream().map(r -> r.appName()).toList()));
        String versions = String.join(", ", distinct(result.reports().stream().map(r -> r.affectedVersion()).toList()));
        String timestamp = DateTimeFormatter.ofPattern(props.exportTimestamp()).withZone(props.exportZone())
                .format(clock.instant());
        return props.exportTitle()
                .replace("{project}", blank(project) ? projects : project.trim())
                .replace("{version}", blank(version) ? versions : version.trim())
                .replace("{timestamp}", timestamp)
                .replaceAll("\\s+", " ").trim();
    }

    String render(AnalysisResponse result, List<String> files) {
        Context context = new Context();
        context.setVariable("report", report(result, files));
        return templates.process(TEMPLATE, context);
    }

    Report report(AnalysisResponse result, List<String> files) {
        List<FindingView> findings = result.findings().stream()
                .sorted(Comparator.comparingInt((FindingView f) -> -Severity.rank(f.severity()))
                        .thenComparing(f -> Objects.requireNonNullElse(f.application(), ""))
                        .thenComparing(FindingView::id))
                .toList();
        List<SastIssueView> sastIssues = result.sast() == null ? List.of() : result.sast().issues();
        List<SastFlaw> openFlaws = sastIssues.stream().flatMap(i -> i.flaws().stream()).toList();

        List<String> levels = new ArrayList<>(BASE_LEVELS);
        for (String other : OTHER_LEVELS) {
            if (count(findings, FindingView::severity, other) + count(openFlaws, SastFlaw::severity, other) > 0) {
                levels.add(other);
            }
        }
        List<Integer> sca = levels.stream().map(l -> count(findings, FindingView::severity, l)).toList();
        List<Integer> sast = levels.stream().map(l -> count(openFlaws, SastFlaw::severity, l)).toList();
        List<Level> header = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            header.add(new Level(levels.get(i), sca.get(i) + sast.get(i)));
        }

        // findings in the same Jira issue(s) are one row - the issue is shared by the repositories; findings not in
        // Jira stay a row each. In severity order, so a merged row sits where its first finding did.
        Map<Object, List<FindingView>> byIssue = new LinkedHashMap<>();
        for (FindingView f : findings) {
            Object key = f.issues().isEmpty() ? f : f.issues().stream().map(IssueRef::key).sorted().toList();
            byIssue.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        List<ScaRow> scaRows = new ArrayList<>();
        for (List<FindingView> group : byIssue.values()) {
            FindingView first = group.getFirst();
            String description = first.issues().isEmpty() ? first.summary() : first.issues().getFirst().summary();
            scaRows.add(new ScaRow(scaRows.size() + 1, first.issues(), description, first.severity(),
                    distinct(group.stream().map(FindingView::application).toList()), status(first.issues())));
        }
        boolean sastRead = result.sast() != null && result.sast().error() == null;
        List<SastRow> sastRows = new ArrayList<>();
        for (SastIssueView i : sastIssues) {
            String details = i.issues().isEmpty() ? i.summary() : i.issues().getFirst().summary();
            sastRows.add(new SastRow(sastRows.size() + 1, i.issues(), details, i.severity(), i.flaws().size(),
                    String.join(", ", i.applications()), sastRead ? status(i.issues()) : "—"));
        }

        String source = "Jira " + result.projectKey() + " (SCA" + (result.selectedVersions().isEmpty() ? ", any version"
                : ", versions " + String.join(", ", result.selectedVersions())) + ")"
                + (result.sast() == null ? "" : ", " + result.sast().projectKey() + " (SAST)")
                + ". Generated " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(props.exportZone())
                        .format(result.analyzedAt()) + ".";
        return new Report(header, List.of(new SummaryRow("SCA", findings.size(), sca),
                new SummaryRow("SAST", openFlaws.size(), sast)), scaRows, sastRows, files, source);
    }

    /** Jira's statuses of the issues, or {@value #NOT_IN_JIRA}. */
    private static String status(List<IssueRef> issues) {
        if (issues.isEmpty()) {
            return NOT_IN_JIRA;
        }
        List<String> statuses = distinct(issues.stream().map(IssueRef::status).toList());
        return statuses.isEmpty() ? "—" : String.join(", ", statuses);
    }

    private static <T> int count(List<T> items, Function<T, String> severity, String level) {
        int rank = Severity.rank(level);
        return (int) items.stream().filter(i -> Severity.rank(severity.apply(i)) == rank).count();
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(v -> !blank(v)).toList()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
