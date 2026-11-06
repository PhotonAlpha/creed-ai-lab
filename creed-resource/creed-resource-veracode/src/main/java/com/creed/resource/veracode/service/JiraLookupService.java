package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraIssueType;
import com.creed.resource.veracode.client.jira.JiraPage;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
     * The page's "test connection", on the create metadata, as a list of checks the page shows one by one: the
     * token, the issue type, its Affects Version/s, the project's versions. {@link #problem} names the first that
     * failed, so the page can say what to fix instead of one "cannot create". No user name - the issue resource
     * has no "who am I".
     *
     * @param issueType      the type tested: the page's choice, else the configured one
     * @param tokenAccepted  Jira took the token (it answered, and not 401) - {@code ok} can still be false
     * @param canCreate      the token may create {@code issueType} in the project
     * @param affectsVersion {@code issueType}'s create screen has Affects Version/s - without it nothing is looked up
     *                       or created (the version is half of what identifies a finding)
     * @param fields         what the page asks for before creating (Component/s, required fields without a default)
     * @param issueTypes     every type the token may create, each with whether it can be filed against a version -
     *                       the page's issue type choice
     * @param problem        the first failed check, {@code null} when {@code ok}
     * @param status         Jira's HTTP status on failure, {@code 0} when Jira was not reachable
     */
    public record Connection(boolean ok, String baseUrl, String projectKey, String issueType, boolean tokenAccepted,
                             boolean canCreate, boolean affectsVersion, int versions, List<CreateField> fields,
                             List<IssueTypeOption> issueTypes, Problem problem, int status, String message) {
    }

    /** @param versions the versions it can be filed against; 0 with {@code affectsVersion} false */
    public record IssueTypeOption(String id, String name, boolean affectsVersion, int versions) {
    }

    /** The connection test's checks, in the order they are made. */
    public enum Problem {
        /** Jira did not answer (network, TLS, base URL). */
        UNREACHABLE,
        /** 401: wrong or revoked token, wrong e-mail, or Cloud vs Data Center mixed up. */
        TOKEN_REJECTED,
        /** Jira took the token and refused the project: wrong key, or no Browse / Create permission. */
        PROJECT,
        /** The issue type does not exist in the project or the token may not create it. */
        ISSUE_TYPE,
        /** The issue type's create screen has no Affects Version/s. */
        AFFECTS_VERSION,
        /** Affects Version/s is there, but the project has no (unarchived) version to choose. */
        NO_VERSIONS
    }

    /**
     * The configured issue type as Jira knows it, and what its create screen asks for.
     *
     * @param versions Affects Version/s values keyed by lower case
     * @param fields   the fields the page fills (see {@link CreateField})
     * @param cloud    read with a Jira Cloud token: user fields then take an {@code accountId}, not a name
     */
    public record CreateMeta(String issueTypeId, String issueType, Map<String, String> versions,
                             List<CreateField> fields, boolean cloud) {
    }

    /** The fields every create request carries by itself; anything else required comes from the page. */
    private static final Set<String> FILLED = Set.of("project", "issuetype", "summary", "description",
            JiraFieldMeta.VERSIONS, "labels");

    public Connection connection(JiraToken token) {
        token.require();
        String project = props.projectKey();
        String wanted = token.issueTypeOr(props.issueType());
        List<JiraIssueType> types;
        Map<String, List<JiraFieldMeta>> screens = new LinkedHashMap<>();
        try {
            types = creatableTypes(token);
            // every type's screen, not just the wanted one: the page offers the types that would work
            for (JiraIssueType type : types) {
                screens.put(type.id(), fieldsOf(token, type));
            }
        } catch (UpstreamException e) {
            Problem problem = e.status() == 0 ? Problem.UNREACHABLE
                    : e.status() == 401 ? Problem.TOKEN_REJECTED : Problem.PROJECT;
            return new Connection(false, props.baseUrl(), project, wanted, problem != Problem.UNREACHABLE
                    && problem != Problem.TOKEN_REJECTED, false, false, 0, List.of(), List.of(), problem, e.status(),
                    e.getMessage());
        }
        List<IssueTypeOption> options = types.stream().map(t -> {
            JiraFieldMeta versions = versionsField(screens.get(t.id()));
            return new IssueTypeOption(t.id(), t.name(), versions != null,
                    versions == null ? 0 : versions.allowedNames().size());
        }).toList();
        String usable = String.join(", ", options.stream().filter(o -> o.affectsVersion() && o.versions() > 0)
                .map(IssueTypeOption::name).toList());
        String instead = usable.isEmpty() ? "" : "; issue types that have it: " + usable;

        JiraIssueType type = byName(types, wanted);
        if (type == null) {
            return new Connection(false, props.baseUrl(), project, wanted, true, false, false, 0, List.of(), options,
                    Problem.ISSUE_TYPE, 200, notCreatable(wanted, types));
        }
        List<JiraFieldMeta> fields = screens.get(type.id());
        if (versionsField(fields) == null) {
            return new Connection(false, props.baseUrl(), project, type.name(), true, true, false, 0, List.of(),
                    options, Problem.AFFECTS_VERSION, 200, noAffectsVersion(type) + instead);
        }
        CreateMeta meta = meta(type, fields, token);
        if (meta.versions().isEmpty()) {
            return new Connection(false, props.baseUrl(), project, type.name(), true, true, true, 0, meta.fields(),
                    options, Problem.NO_VERSIONS, 200, "project " + project + " has no version to file a "
                    + type.name() + " against: create the versions (releases) in Jira");
        }
        List<String> required = meta.fields().stream().filter(CreateField::required).map(CreateField::name).toList();
        String message = "can create " + meta.issueType() + " in " + project + ", "
                + meta.versions().size() + " version(s) to file against"
                + (required.isEmpty() ? "" : "; to choose on the page: " + String.join(", ", required));
        return new Connection(true, props.baseUrl(), project, meta.issueType(), true, true, true,
                meta.versions().size(), meta.fields(), options, null, 200, message);
    }

    /**
     * Two paged calls: the creatable issue types, then the chosen one's fields (the page's issue type, else the
     * configured one). Archived versions are not in {@code allowedValues} - nothing can be filed against them,
     * so they count as "not in Jira".
     *
     * <p>Required fields only matter to creating, and the page fills them: never a reason to fail here - an
     * analysis only reads. Only a missing Affects Version/s fails, because lookups need its versions.
     */
    public CreateMeta createMeta(JiraToken token) {
        token.require();
        String wanted = token.issueTypeOr(props.issueType());
        List<JiraIssueType> types = creatableTypes(token);
        JiraIssueType type = byName(types, wanted);
        if (type == null) {
            throw UpstreamException.setup(JIRA, notCreatable(wanted, types));
        }
        List<JiraFieldMeta> fields = fieldsOf(token, type);
        if (versionsField(fields) == null) {
            throw UpstreamException.setup(JIRA, noAffectsVersion(type) + "; test the connection on the page to see "
                    + "which issue types have it");
        }
        return meta(type, fields, token);
    }

    private CreateMeta meta(JiraIssueType type, List<JiraFieldMeta> fields, JiraToken token) {
        List<CreateField> asked = fields.stream()
                .filter(f -> JiraFieldMeta.COMPONENTS.equals(f.fieldId())
                        || f.required() && !f.hasDefaultValue() && !FILLED.contains(f.fieldId()))
                .map(CreateField::of)
                .toList();
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (String name : versionsField(fields).allowedNames()) {
            byLowerCase.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), name);
        }
        return new CreateMeta(type.id(), type.name(), byLowerCase, asked, token.cloud());
    }

    /** Sub-task types are left out: they cannot be created without a parent. */
    private List<JiraIssueType> creatableTypes(JiraToken token) {
        return pages(from -> jira.createIssueTypes(token.authorization(), props.projectKey(), from, props.pageSize()))
                .stream().filter(t -> !Boolean.TRUE.equals(t.subtask())).toList();
    }

    private List<JiraFieldMeta> fieldsOf(JiraToken token, JiraIssueType type) {
        return pages(from -> jira.createFields(token.authorization(), props.projectKey(), type.id(), from,
                props.pageSize()));
    }

    private static JiraFieldMeta versionsField(List<JiraFieldMeta> fields) {
        return fields.stream().filter(f -> JiraFieldMeta.VERSIONS.equals(f.fieldId())).findFirst().orElse(null);
    }

    private static JiraIssueType byName(List<JiraIssueType> types, String name) {
        return types.stream().filter(t -> name.equalsIgnoreCase(t.name())).findFirst().orElse(null);
    }

    private String notCreatable(String wanted, List<JiraIssueType> types) {
        return types.isEmpty()
                ? "no issue type can be created in project " + props.projectKey() + " with this token"
                : "issue type '" + wanted + "' cannot be created in project " + props.projectKey()
                        + " (creatable: " + String.join(", ", types.stream().map(JiraIssueType::name).toList()) + ")";
    }

    private String noAffectsVersion(JiraIssueType type) {
        return "Affects Version/s is not on the create screen of " + type.name() + " in project " + props.projectKey()
                + ": add the field to that screen in Jira, or pick another issue type";
    }

    /**
     * The page's values for the create screen, checked and turned into Jira's JSON before anything is created.
     * A value names an option by id or by label (any case); a single-valued field takes one value.
     *
     * @param values fieldId -> a value or a list of values
     * @return fieldId -> the JSON value for {@code fields} of the create request
     * @throws IllegalArgumentException a field the page does not fill, an unknown option, a bad number
     * @throws FieldsRequiredException  required fields left empty
     */
    public Map<String, Object> resolveFields(CreateMeta meta, Map<String, Object> values) {
        Map<String, Object> given = values == null ? Map.of() : values;
        Set<String> known = new HashSet<>();
        meta.fields().forEach(f -> known.add(f.fieldId()));
        for (String key : given.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("field '" + key + "' is not one the page fills for " + meta.issueType()
                        + " (fields: " + String.join(", ", known) + ")");
            }
        }
        Map<String, Object> jiraFields = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (CreateField f : meta.fields()) {
            List<String> chosen = texts(given.get(f.fieldId()));
            if (chosen.isEmpty()) {
                if (f.required()) {
                    missing.add(f.name());
                }
                continue;
            }
            if (!f.multiple() && chosen.size() > 1) {
                throw new IllegalArgumentException(f.name() + " takes one value, got " + chosen);
            }
            List<Object> json = new ArrayList<>();
            for (String value : chosen) {
                json.add(switch (f.kind()) {
                    case OPTIONS -> {
                        CreateField.Option option = f.option(value);
                        if (option == null) {
                            throw new IllegalArgumentException(f.name() + " has no value '" + value + "' (has: "
                                    + String.join(", ", f.options().stream().map(CreateField.Option::label).toList()) + ")");
                        }
                        yield Map.of("id", option.id());
                    }
                    // Cloud has no user names in its API, only account ids
                    case USER -> Map.of(meta.cloud() ? "accountId" : "name", value);
                    case NUMBER -> number(f, value);
                    case TEXT -> value;
                });
            }
            jiraFields.put(f.fieldId(), f.multiple() ? json : json.getFirst());
        }
        if (!missing.isEmpty()) {
            throw new FieldsRequiredException(props.projectKey(), meta.issueType(), missing);
        }
        return jiraFields;
    }

    /** Required create-screen fields left empty - answered 400 before anything is created. */
    public static class FieldsRequiredException extends RuntimeException {
        public FieldsRequiredException(String project, String issueType, List<String> names) {
            super("Jira requires " + String.join(", ", names) + " to create a " + issueType + " in " + project
                    + ": choose them on the page");
        }
    }

    private static List<String> texts(Object value) {
        List<String> out = new ArrayList<>();
        for (Object v : value instanceof Collection<?> c ? c : value == null ? List.of() : List.of(value)) {
            if (v != null && !v.toString().isBlank()) {
                out.add(v.toString().trim());
            }
        }
        return out;
    }

    private static Double number(CreateField f, String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(f.name() + " takes a number, got '" + value + "'");
        }
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

    /** @param fields from {@link #resolveFields}: the create screen's other fields, already in Jira's shape */
    public IssueRef create(Finding finding, String jiraVersion, Map<String, Object> fields, ReportView report,
                           JiraToken token) {
        List<String> labels = new ArrayList<>(props.labels());
        if (finding.cve() != null) {
            labels.add(finding.cve());
        }
        String summary = summary(finding, report);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", Map.of("key", props.projectKey()));
        body.put("issuetype", Map.of("name", token.issueTypeOr(props.issueType())));
        body.put("summary", summary);
        body.put("description", JiraDescription.of(finding, report));
        body.put("versions", List.of(Map.of("name", jiraVersion)));
        body.put("labels", labels);
        body.putAll(fields);
        JiraCreatedIssue created = UpstreamException.call(JIRA,
                () -> jira.createIssue(token.authorization(), new JiraCreateIssueRequest(body)));
        log.info("created {} for {} (upload {})", created.key(), finding.id(), report.id());
        return new IssueRef(created.key(), summary, null, null, null, List.of(jiraVersion), props.browse(created.key()));
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
