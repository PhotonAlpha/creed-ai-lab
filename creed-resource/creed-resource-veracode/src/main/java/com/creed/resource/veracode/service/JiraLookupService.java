package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraSearchRequest;
import com.creed.resource.veracode.client.jira.JiraSearchResponse;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the Jira issues that already cover a finding, and creates the ones that do not exist.
 *
 * <p>An issue covers a finding when its Affects Version/s include the finding's affected version
 * <em>and</em> its summary names the finding: the CVE id as a whole token, or - for a finding without a
 * CVE - the title as a whole phrase (case and punctuation ignored). The same CVE fixed in 2.2.1 is
 * still open for 2.3.0, which is the reason the version is part of the key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraLookupService {

    static final String JIRA = "Jira";
    private static final List<String> FIELDS = List.of("summary", "status", "versions", "labels");

    private final JiraClient jira;
    private final JiraProperties props;

    public record IssueRef(String key, String summary, String status, List<String> versions, String url) {
    }

    /**
     * The project's version names keyed by their lower-case form. Version lookups go through this map so
     * a report saying {@code v2.3.0 } or {@code 2.3.0-RC} vs Jira's {@code 2.3.0} is visible as "not in
     * Jira" rather than silently matching nothing - and so JQL and create requests carry Jira's own
     * spelling.
     */
    public Map<String, String> projectVersions() {
        List<JiraVersion> versions = UpstreamException.call(JIRA, () -> jira.projectVersions(props.projectKey()));
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (JiraVersion v : versions == null ? List.<JiraVersion>of() : versions) {
            if (v.name() != null) {
                byLowerCase.putIfAbsent(v.name().trim().toLowerCase(Locale.ROOT), v.name());
            }
        }
        return byLowerCase;
    }

    public static String jiraVersion(Map<String, String> projectVersions, Finding finding) {
        return projectVersions.get(finding.affectedVersion().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @return finding id -> covering issues, for every finding whose version exists in Jira (absent
     * from the map: version unknown to Jira, so nothing was searched)
     */
    public Map<String, List<IssueRef>> find(List<Finding> findings, Map<String, String> projectVersions) {
        Map<String, List<IssueRef>> result = new LinkedHashMap<>();
        List<Finding> searchable = new ArrayList<>();
        for (Finding f : findings) {
            if (jiraVersion(projectVersions, f) != null) {
                searchable.add(f);
                result.put(f.id(), new ArrayList<>());
            }
        }
        for (int from = 0; from < searchable.size(); from += props.jqlBatchSize()) {
            List<Finding> batch = searchable.subList(from, Math.min(from + props.jqlBatchSize(), searchable.size()));
            Set<String> versions = new LinkedHashSet<>();
            batch.forEach(f -> versions.add(jiraVersion(projectVersions, f)));
            List<JiraIssue> candidates = searchAll(Jql.lookup(props.projectKey(), versions, batch));
            for (Finding f : batch) {
                String version = jiraVersion(projectVersions, f);
                for (JiraIssue issue : candidates) {
                    if (covers(issue, f, version)) {
                        result.get(f.id()).add(toRef(issue));
                    }
                }
            }
        }
        return result;
    }

    public IssueRef create(Finding finding, String jiraVersion, ReportPage page) {
        List<String> labels = new ArrayList<>(props.labels());
        if (finding.cve() != null) {
            labels.add(finding.cve());
        }
        JiraCreateIssueRequest request = new JiraCreateIssueRequest(new JiraCreateIssueRequest.Fields(
                new JiraCreateIssueRequest.Key(props.projectKey()),
                new JiraCreateIssueRequest.Name(props.issueType()),
                summary(finding),
                JiraDescription.of(finding, page),
                List.of(JiraVersion.named(jiraVersion)),
                labels));
        JiraCreatedIssue created = UpstreamException.call(JIRA, () -> jira.createIssue(request));
        log.info("created {} for {} ({})", created.key(), finding.id(), page.id());
        return new IssueRef(created.key(), request.fields().summary(), null, List.of(jiraVersion),
                props.browse(created.key()));
    }

    /** Jira limits a summary to 255 characters; a long title is cut, the CVE at the front survives. */
    String summary(Finding finding) {
        StringBuilder s = new StringBuilder();
        if (!props.summaryPrefix().isBlank()) {
            s.append(props.summaryPrefix().trim()).append(' ');
        }
        if (finding.cve() != null) {
            s.append(finding.cve()).append(' ');
        }
        if (finding.title() != null && !finding.title().equals(finding.cve())) {
            s.append(finding.title());
        }
        String summary = s.toString().trim();
        return summary.length() <= 255 ? summary : summary.substring(0, 254) + "…";
    }

    static boolean covers(JiraIssue issue, Finding finding, String jiraVersion) {
        boolean versionMatches = issue.versionNames().stream().anyMatch(v -> v.equalsIgnoreCase(jiraVersion));
        return versionMatches && summaryNames(issue.summary(), finding);
    }

    /**
     * {@code CVE-2021-4422} must not match a summary about {@code CVE-2021-44228}, hence the
     * look-arounds instead of {@code contains}.
     */
    static boolean summaryNames(String summary, Finding finding) {
        if (finding.cve() != null) {
            return Pattern.compile("(?<![A-Za-z0-9-])" + Pattern.quote(finding.cve()) + "(?![0-9])",
                    Pattern.CASE_INSENSITIVE).matcher(summary).find();
        }
        String title = Finding.normalise(finding.title());
        return !title.isEmpty() && (" " + Finding.normalise(summary) + " ").contains(" " + title + " ");
    }

    private List<JiraIssue> searchAll(String jql) {
        List<JiraIssue> issues = new ArrayList<>();
        int startAt = 0;
        while (true) {
            int from = startAt;
            JiraSearchResponse page = UpstreamException.call(JIRA,
                    () -> jira.search(new JiraSearchRequest(jql, from, props.pageSize(), FIELDS)));
            issues.addAll(page.issuesOrEmpty());
            startAt += page.issuesOrEmpty().size();
            // An empty page ends the loop even if `total` says otherwise - Jira can shrink the result
            // between pages, and trusting `total` alone would spin.
            if (page.issuesOrEmpty().isEmpty() || startAt >= page.total()) {
                return issues;
            }
        }
    }

    private IssueRef toRef(JiraIssue issue) {
        return new IssueRef(issue.key(), issue.summary(), issue.statusName(), issue.versionNames(),
                props.browse(issue.key()));
    }
}
