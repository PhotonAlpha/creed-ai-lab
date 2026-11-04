package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraSearchRequest;
import com.creed.resource.veracode.client.jira.JiraSearchResponse;
import com.creed.resource.veracode.client.jira.JiraUser;
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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Jira issues that already cover a finding, and creates the ones that do not exist.
 *
 * <p>An issue covers a finding when its Affects Version/s include the finding's affected version
 * <em>and</em> its summary (the issue's title) names the finding, matched loosely: the CVE id anywhere in
 * it, case and separators ignored ({@code cve 2021 44228}, {@code CVE_2021_44228}), but never as part of a
 * longer id; for a finding without a CVE, every word of the title, in any order. The same CVE fixed in 2.2.1
 * is still open for 2.3.0, which is the reason the version is part of the key.
 *
 * <p>Every call carries the user's {@link JiraToken} when the page sent one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraLookupService {

    static final String JIRA = "Jira";
    private static final List<String> FIELDS = List.of("summary", "status", "versions", "labels", "priority", "assignee");
    private static final Pattern CVE_PARTS = Pattern.compile("CVE-(\\d{4})-(\\d+)", Pattern.CASE_INSENSITIVE);

    private final JiraClient jira;
    private final JiraProperties props;

    /** @param assignee display name; {@code null} = unassigned */
    public record IssueRef(String key, String summary, String status, String priority, String assignee,
                           List<String> versions, String url) {
    }

    /**
     * The page's "test connection": whose token it is, and whether it can see the project.
     *
     * @param status Jira's HTTP status on failure, {@code 0} when Jira was not reachable
     */
    public record Connection(boolean ok, String baseUrl, String projectKey, String user, String displayName,
                             boolean projectAccess, int status, String message) {
    }

    public Connection connection(JiraToken token) {
        if (!token.present()) {
            throw new JiraToken.RequiredException();
        }
        JiraUser user;
        try {
            user = UpstreamException.call(JIRA, () -> jira.myself(token.authorization()));
        } catch (UpstreamException e) {
            return new Connection(false, props.baseUrl(), props.projectKey(), null, null, false, e.status(), e.getMessage());
        }
        String login = user.name() != null ? user.name() : user.key();
        try {
            int versions = projectVersions(token).size();
            return new Connection(true, props.baseUrl(), props.projectKey(), login, user.displayName(), true, 200,
                    "project " + props.projectKey() + ": " + versions + " version(s)");
        } catch (UpstreamException e) {
            // The token works but cannot see the project: lookups would find nothing and creates would fail.
            return new Connection(false, props.baseUrl(), props.projectKey(), login, user.displayName(), false,
                    e.status(), e.getMessage());
        }
    }

    /**
     * The project's version names keyed by their lower-case form. Version lookups go through this map so
     * a report saying {@code v2.3.0 } or {@code 2.3.0-RC} vs Jira's {@code 2.3.0} is visible as "not in
     * Jira" rather than silently matching nothing - and so JQL and create requests carry Jira's own
     * spelling.
     */
    public Map<String, String> projectVersions(JiraToken token) {
        List<JiraVersion> versions = UpstreamException.call(JIRA,
                () -> jira.projectVersions(token.authorization(), props.projectKey()));
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
    public Map<String, List<IssueRef>> find(List<Finding> findings, Map<String, String> projectVersions,
                                            JiraToken token) {
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
            List<JiraIssue> candidates = searchAll(Jql.lookup(props.projectKey(), versions, batch), token);
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

    public IssueRef create(Finding finding, String jiraVersion, ReportView report, JiraToken token) {
        List<String> labels = new ArrayList<>(props.labels());
        if (finding.cve() != null) {
            labels.add(finding.cve());
        }
        JiraCreateIssueRequest request = new JiraCreateIssueRequest(new JiraCreateIssueRequest.Fields(
                new JiraCreateIssueRequest.Key(props.projectKey()),
                new JiraCreateIssueRequest.Name(props.issueType()),
                summary(finding, report),
                JiraDescription.of(finding, report),
                List.of(JiraVersion.named(jiraVersion)),
                labels));
        JiraCreatedIssue created = UpstreamException.call(JIRA, () -> jira.createIssue(token.authorization(), request));
        log.info("created {} for {} (upload {})", created.key(), finding.id(), report.id());
        return new IssueRef(created.key(), request.fields().summary(), null, null, null, List.of(jiraVersion),
                props.browse(created.key()));
    }

    /**
     * {@code summary-template} filled in, whitespace collapsed; Jira limits a summary to 255 characters, so a
     * long one is cut.
     */
    String summary(Finding finding, ReportView report) {
        String subject = finding.cve() != null ? finding.cve() : Objects.requireNonNullElse(finding.title(), "");
        String project = report.appName() != null ? report.appName() : props.projectKey();
        String summary = props.summaryTemplate()
                .replace("{project}", project)
                .replace("{jiraProject}", props.projectKey())
                .replace("{component}", String.join(", ", finding.components()))
                .replace("{cve}", subject)
                .replace("{title}", Objects.requireNonNullElse(finding.title(), ""))
                .replace("{version}", finding.affectedVersion())
                .replaceAll("\\s+", " ").trim();
        return summary.length() <= 255 ? summary : summary.substring(0, 254) + "…";
    }

    static boolean covers(JiraIssue issue, Finding finding, String jiraVersion) {
        boolean versionMatches = issue.versionNames().stream().anyMatch(v -> v.equalsIgnoreCase(jiraVersion));
        return versionMatches && summaryNames(issue.summary(), finding);
    }

    /**
     * The fuzzy title match. A CVE: its year and number with any run of space / {@code -} / {@code _} (or
     * nothing) between the parts, case ignored - but not inside a longer id, so {@code CVE-2021-4422} does not
     * match a summary about {@code CVE-2021-44228}. A title: all its words appear in the summary as words, in
     * any order ({@code ReportQueryBuilder} does not match {@code ReportQueryBuilderV2}).
     */
    static boolean summaryNames(String summary, Finding finding) {
        if (finding.cve() != null) {
            Matcher cve = CVE_PARTS.matcher(finding.cve());
            String pattern = cve.matches()
                    ? "CVE[\\s_-]*" + cve.group(1) + "[\\s_-]*" + cve.group(2)
                    : Pattern.quote(finding.cve());
            return Pattern.compile("(?<![A-Za-z0-9])" + pattern + "(?![0-9])", Pattern.CASE_INSENSITIVE)
                    .matcher(summary).find();
        }
        Set<String> words = new LinkedHashSet<>(List.of(Finding.normalise(summary).split(" ")));
        List<String> title = List.of(Finding.normalise(finding.title()).split(" "));
        return !title.getFirst().isEmpty() && words.containsAll(title);
    }

    private List<JiraIssue> searchAll(String jql, JiraToken token) {
        List<JiraIssue> issues = new ArrayList<>();
        int startAt = 0;
        while (true) {
            int from = startAt;
            JiraSearchResponse page = UpstreamException.call(JIRA,
                    () -> jira.search(token.authorization(), new JiraSearchRequest(jql, from, props.pageSize(), FIELDS)));
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
        return new IssueRef(issue.key(), issue.summary(), issue.statusName(), issue.priorityName(),
                issue.assigneeName(), issue.versionNames(), props.browse(issue.key()));
    }
}
