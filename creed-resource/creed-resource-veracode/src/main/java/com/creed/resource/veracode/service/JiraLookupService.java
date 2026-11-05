package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraIssueType;
import com.creed.resource.veracode.client.jira.JiraPage;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
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
 * <p>Only the issue resource is called ({@link JiraClient}): a finding's candidates come from the issue
 * picker (one call per finding, its JQL narrowed to the project, the version and the CVE / title), each
 * candidate is read with {@code GET /issue/{key}}, and the match is decided here on what Jira returned. The
 * project's versions are the {@code versions} field of the issue type's create metadata.
 *
 * <p>Every call carries the user's {@link JiraToken} when the page sent one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraLookupService {

    static final String JIRA = "Jira";
    private static final String FIELDS = "summary,status,versions,labels,priority,assignee,project";
    private static final Pattern CVE_PARTS = Pattern.compile("CVE-(\\d{4})-(\\d+)", Pattern.CASE_INSENSITIVE);

    private final JiraClient jira;
    private final JiraProperties props;

    /** @param assignee display name; {@code null} = unassigned */
    public record IssueRef(String key, String summary, String status, String priority, String assignee,
                           List<String> versions, String url) {
    }

    /**
     * The page's "test connection", on the create metadata: whether the token is accepted and may create the
     * configured issue type in the project, and how many versions it can be filed against. No user name - the
     * issue resource has no "who am I".
     *
     * @param tokenAccepted Jira took the token (it answered, and not 401) - {@code ok} can still be false
     * @param status        Jira's HTTP status on failure, {@code 0} when Jira was not reachable
     */
    public record Connection(boolean ok, String baseUrl, String projectKey, String issueType, boolean tokenAccepted,
                             boolean canCreate, int versions, int status, String message) {
    }

    /** The configured issue type as Jira knows it, and the versions its create screen offers. */
    public record CreateMeta(String issueTypeId, String issueType, Map<String, String> versions) {
    }

    public Connection connection(JiraToken token) {
        if (!token.present()) {
            throw new JiraToken.RequiredException();
        }
        try {
            CreateMeta meta = createMeta(token);
            return new Connection(true, props.baseUrl(), props.projectKey(), meta.issueType(), true, true,
                    meta.versions().size(), 200, "can create " + meta.issueType() + " in " + props.projectKey() + ", "
                    + meta.versions().size() + " version(s) to file against");
        } catch (UpstreamException e) {
            // 401: the token itself; anything else Jira answered means it read the token and said no to the project
            boolean accepted = e.status() != 0 && e.status() != 401;
            return new Connection(false, props.baseUrl(), props.projectKey(), props.issueType(), accepted, false, 0,
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
        return createMeta(token).versions();
    }

    /**
     * Two paged calls: the creatable issue types, then the configured one's fields. Archived versions are
     * not in {@code allowedValues} - nothing can be filed against them, so they count as "not in Jira".
     */
    public CreateMeta createMeta(JiraToken token) {
        String project = props.projectKey();
        List<JiraIssueType> types = pages(from -> jira.createIssueTypes(token.authorization(), project, from,
                props.pageSize()));
        JiraIssueType type = types.stream()
                .filter(t -> props.issueType().equalsIgnoreCase(t.name()))
                .findFirst()
                .orElseThrow(() -> UpstreamException.setup(JIRA, types.isEmpty()
                        ? "no issue type can be created in project " + project + " with this token"
                        : "issue type '" + props.issueType() + "' cannot be created in project " + project
                                + " (creatable: " + String.join(", ", types.stream().map(JiraIssueType::name).toList()) + ")"));
        List<JiraFieldMeta> fields = pages(from -> jira.createFields(token.authorization(), project, type.id(), from,
                props.pageSize()));
        JiraFieldMeta versions = fields.stream()
                .filter(f -> JiraFieldMeta.VERSIONS.equals(f.fieldId()))
                .findFirst()
                .orElseThrow(() -> UpstreamException.setup(JIRA, "Affects Version/s is not on the create screen of "
                        + type.name() + " in project " + project));
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (JsonNode v : versions.allowedValues() == null ? List.<JsonNode>of() : versions.allowedValues()) {
            String name = v.path("name").asString(null);
            if (name != null) {
                byLowerCase.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), name);
            }
        }
        return new CreateMeta(type.id(), type.name(), byLowerCase);
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
        // findings share candidates (two CVEs in one issue's summary): each issue is read once per lookup
        Map<String, JiraIssue> read = new HashMap<>();
        for (Finding f : findings) {
            String version = jiraVersion(projectVersions, f);
            if (version == null) {
                continue;
            }
            List<IssueRef> covering = new ArrayList<>();
            result.put(f.id(), covering);
            String jql = Jql.lookup(props.projectKey(), version, f);
            if (jql == null) {
                continue;
            }
            List<String> keys = UpstreamException.call(JIRA,
                    () -> jira.picker(token.authorization(), Jql.pickerQuery(f), jql, true)).keys();
            for (String key : keys) {
                JiraIssue issue = read.containsKey(key) ? read.get(key) : read(key, token);
                read.put(key, issue);
                // the history section is not bound to currentJQL: the project is checked here too
                if (issue != null && props.projectKey().equalsIgnoreCase(issue.projectKey()) && covers(issue, f, version)) {
                    covering.add(toRef(issue));
                }
            }
        }
        return result;
    }

    /** {@code null} when it is gone (or hidden from the token) between the picker and the read. */
    private JiraIssue read(String key, JiraToken token) {
        try {
            return UpstreamException.call(JIRA, () -> jira.issue(token.authorization(), key, FIELDS));
        } catch (UpstreamException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
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

    /** Every page: until an empty one, {@code isLast}, or {@code total} reached - whichever Jira sends. */
    private static <T> List<T> pages(IntFunction<JiraPage<T>> page) {
        List<T> all = new ArrayList<>();
        int startAt = 0;
        while (true) {
            int from = startAt;
            JiraPage<T> p = UpstreamException.call(JIRA, () -> page.apply(from));
            all.addAll(p.valuesOrEmpty());
            startAt += p.valuesOrEmpty().size();
            if (p.valuesOrEmpty().isEmpty() || Boolean.TRUE.equals(p.isLast())
                    || p.total() != null && startAt >= p.total()) {
                return all;
            }
        }
    }

    private IssueRef toRef(JiraIssue issue) {
        return new IssueRef(issue.key(), issue.summary(), issue.statusName(), issue.priorityName(),
                issue.assigneeName(), issue.versionNames(), props.browse(issue.key()));
    }
}
