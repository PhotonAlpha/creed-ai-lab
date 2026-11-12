package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraIssueType;
import com.creed.resource.veracode.client.jira.JiraPage;
import com.creed.resource.veracode.client.jira.JiraSearchResult;
import com.creed.resource.veracode.config.ApplicationProperties;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastIssue;
import com.github.benmanes.caffeine.cache.Cache;
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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Jira issues that already cover a finding, and creates the ones that do not exist.
 *
 * <p>An issue covers a finding when (all three):
 * <ul>
 *   <li>its Affects Version/s include one of the Jira versions chosen on the page (none chosen: any). The report's
 *       own version is shown only - it is not searched by or filed under (asked for, 2026-11-06);</li>
 *   <li>its summary names the finding, loosely: the CVE id anywhere, case and separators ignored
 *       ({@code cve 2021 44228}, {@code CVE_2021_44228}) but never as part of a longer id; without a CVE, every
 *       word of the title (an advisory id such as {@code SRCCLR-SID-29587}), in any order;</li>
 *   <li>its summary names every component the page shows for the finding ({@code log4j-core 2.14.1}), as a run of
 *       words - asked for (2026-11-06): the same CVE in another library is another issue. Only while the summary
 *       template has {@code {component}}: otherwise the issues this page creates could never match.</li>
 * </ul>
 *
 * <p>Finding: one JQL search per affected version and batch of up to {@code search-batch-size} findings, the
 * fields returned inline ({@link Jql}); the match is decided here on what Jira returned. Data Center searches
 * {@code /rest/api/2/search}, Jira Cloud {@code /rest/api/3/search/jql} (v2 search is retired there). The
 * project's versions are the {@code versions} field of the issue type's create metadata.
 *
 * <p>SAST issues ({@link SastIssue}) go to their own project and issue type ({@code veracode.jira.sast}) and are
 * found by their summary alone: the CWE and the flaw category, no version (asked for, 2026-11-08). The create-screen
 * reading, field checks, search and create are the same code, run against that target.
 *
 * <p>Every call carries the user's {@link JiraToken}.
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
    private final ApplicationProperties apps;
    /**
     * Create screens read for analyses, by token fingerprint + project + issue type ({@code meta-cache-ttl}; built in
     * {@code JiraMetaCacheConfiguration}): metadata, which changes when someone edits the Jira scheme - not issues,
     * which every analysis and create reads afresh.
     */
    private final Cache<String, CreateMeta> metaCache;

    /** @param assignee display name; {@code null} = unassigned */
    /** @param labels the issue's labels - an application's label missing from them is added, not a second issue */
    public record IssueRef(String key, String summary, String status, String priority, String assignee,
                           List<String> versions, List<String> labels, String url) {

        public boolean hasLabel(String label) {
            return label == null || labels != null && labels.stream().anyMatch(label::equalsIgnoreCase);
        }

        IssueRef withLabels(List<String> added) {
            List<String> all = new ArrayList<>(labels == null ? List.of() : labels);
            all.addAll(added);
            return new IssueRef(key, summary, status, priority, assignee, versions, List.copyOf(all), url);
        }
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
     * @param countries      the Country field's options ({@code veracode.jira.country-field}) - the page's Country
     *                       select; empty when the create screen has no such field
     * @param issueTypes     every type the token may create, each with whether it can be filed against a version -
     *                       the page's issue type choice
     * @param versionNames   the versions {@code issueType} can be filed against - the page's Jira version list
     * @param defaultVersions {@code veracode.jira.defaults.affects-versions} that are among {@code versionNames}, in
     *                       Jira's spelling: the page selects them; empty = the page keeps its selection
     * @param problem        the first failed check, {@code null} when {@code ok}
     * @param status         Jira's HTTP status on failure, {@code 0} when Jira was not reachable
     */
    public record Connection(boolean ok, String baseUrl, String projectKey, String issueType, boolean tokenAccepted,
                             boolean canCreate, boolean affectsVersion, int versions, List<String> versionNames,
                             List<String> defaultVersions, List<CreateField> fields, List<String> countries,
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
     * @param projectKey      the project it was read from (SCA's or SAST's)
     * @param versions        Affects Version/s values keyed by lower case; empty without the field
     * @param fields          the fields the page fills (see {@link CreateField})
     * @param cloud           read with a Jira Cloud token: user fields then take an {@code accountId}, not a name
     * @param affectsVersion  the create screen has Affects Version/s (always, for SCA: checked on reading)
     * @param versionRequired ... and requires it: a SAST issue is then filed under the version chosen for it
     * @param country         the screen's Country field ({@code veracode.jira.country-field}), {@code null} without one
     */
    public record CreateMeta(String projectKey, String issueTypeId, String issueType, Map<String, String> versions,
                             List<CreateField> fields, boolean cloud, boolean affectsVersion, boolean versionRequired,
                             CreateField country) {
    }

    /**
     * The page's Country choice for the lookup: {@code field in (values)}, no clause when {@code values} is empty.
     *
     * @param field  the field as JQL names it ({@code cf[10300]}), {@code null} without the field
     * @param values option labels in Jira's spelling and order
     */
    public record CountryFilter(String field, List<String> values) {

        public static final CountryFilter ANY = new CountryFilter(null, List.of());
    }

    /** Where issues go and what is preselected: SCA's from the token and the top-level config, SAST's from its own. */
    private record Target(String projectKey, String issueType, JiraProperties.CreateDefaults defaults) {
    }

    private Target sca(JiraToken token) {
        return new Target(props.projectKey(), token.issueTypeOr(props.issueType()), props.defaults());
    }

    private Target sast() {
        return new Target(props.sastProjectKey(), props.sastIssueType(), props.sast().defaults());
    }

    /** The fields every create request carries by itself; anything else required comes from the page. */
    private static final Set<String> FILLED = Set.of("project", "issuetype", "summary", "description",
            JiraFieldMeta.VERSIONS, "labels");

    public Connection connection(JiraToken token) {
        token.require();
        // "test connection" is how a user says "I changed something in Jira": what this token had cached goes
        String fingerprint = fingerprint(token);
        metaCache.invalidateAll(metaCache.asMap().keySet().stream().filter(k -> k.startsWith(fingerprint + "|")).toList());
        Target target = sca(token);
        String project = target.projectKey();
        String wanted = target.issueType();
        List<JiraIssueType> types;
        Map<String, List<JiraFieldMeta>> screens = new LinkedHashMap<>();
        try {
            types = creatableTypes(token, project);
            // every type's screen, not just the wanted one: the page offers the types that would work
            for (JiraIssueType type : types) {
                screens.put(type.id(), fieldsOf(token, project, type));
            }
        } catch (UpstreamException e) {
            Problem problem = e.status() == 0 ? Problem.UNREACHABLE
                    : e.status() == 401 ? Problem.TOKEN_REJECTED : Problem.PROJECT;
            return new Connection(false, props.baseUrl(), project, wanted, problem != Problem.UNREACHABLE
                    && problem != Problem.TOKEN_REJECTED, false, false, 0, List.of(), List.of(), List.of(), List.of(), List.of(), problem, e.status(),
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
            return new Connection(false, props.baseUrl(), project, wanted, true, false, false, 0, List.of(), List.of(), List.of(), List.of(), options,
                    Problem.ISSUE_TYPE, 200, notCreatable(project, wanted, types));
        }
        List<JiraFieldMeta> fields = screens.get(type.id());
        if (versionsField(fields) == null) {
            return new Connection(false, props.baseUrl(), project, type.name(), true, true, false, 0, List.of(), List.of(), List.of(), List.of(),
                    options, Problem.AFFECTS_VERSION, 200, noAffectsVersion(project, type) + instead);
        }
        CreateMeta meta = meta(project, type, fields, token, target.defaults());
        if (meta.versions().isEmpty()) {
            return new Connection(false, props.baseUrl(), project, type.name(), true, true, true, 0, List.of(), List.of(), meta.fields(), countries(meta),
                    options, Problem.NO_VERSIONS, 200, "project " + project + " has no version to file a "
                    + type.name() + " against: create the versions (releases) in Jira");
        }
        List<String> required = meta.fields().stream().filter(CreateField::required).map(CreateField::name).toList();
        String message = "can create " + meta.issueType() + " in " + project + ", "
                + meta.versions().size() + " version(s) to file against"
                + (required.isEmpty() ? "" : "; to choose on the page: " + String.join(", ", required));
        return new Connection(true, props.baseUrl(), project, meta.issueType(), true, true, true,
                meta.versions().size(), List.copyOf(meta.versions().values()), defaultVersions(meta), meta.fields(), countries(meta),
                options, null, 200, message);
    }

    /** {@code sast.defaults.affects-versions} the SAST issue type has: the page's SAST version preselection. */
    public List<String> sastDefaultVersions(CreateMeta meta) {
        return defaultVersions(meta, props.sast().defaults());
    }

    private List<String> defaultVersions(CreateMeta meta) {
        return defaultVersions(meta, props.defaults());
    }

    /** The configured default versions Jira has, in Jira's spelling and the configured order. */
    private static List<String> defaultVersions(CreateMeta meta, JiraProperties.CreateDefaults defaults) {
        return defaults.affectsVersions().stream()
                .map(v -> v == null ? null : meta.versions().get(v.trim().toLowerCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull).distinct().toList();
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
        Target target = sca(token);
        return cached(token, "sca", target, () -> readCreateMeta(token, target));
    }

    private CreateMeta readCreateMeta(JiraToken token, Target target) {
        List<JiraIssueType> types = creatableTypes(token, target.projectKey());
        JiraIssueType type = byName(types, target.issueType());
        if (type == null) {
            throw UpstreamException.setup(JIRA, notCreatable(target.projectKey(), target.issueType(), types));
        }
        List<JiraFieldMeta> fields = fieldsOf(token, target.projectKey(), type);
        if (versionsField(fields) == null) {
            throw UpstreamException.setup(JIRA, noAffectsVersion(target.projectKey(), type)
                    + "; test the connection on the page to see which issue types have it");
        }
        return meta(target.projectKey(), type, fields, token, target.defaults());
    }

    /**
     * The SAST issue type's create screen ({@code veracode.jira.sast}). Unlike SCA, Affects Version/s is optional:
     * a SAST issue is not looked up by version; when the screen requires one, creating files under the version
     * chosen for SAST on the page.
     */
    public CreateMeta sastCreateMeta(JiraToken token) {
        token.require();
        Target target = sast();
        return cached(token, "sast", target, () -> readSastCreateMeta(token, target));
    }

    private CreateMeta readSastCreateMeta(JiraToken token, Target target) {
        List<JiraIssueType> types = creatableTypes(token, target.projectKey());
        JiraIssueType type = byName(types, target.issueType());
        if (type == null) {
            throw UpstreamException.setup(JIRA, notCreatable(target.projectKey(), target.issueType(), types)
                    + " (veracode.jira.sast.issue-type)");
        }
        return meta(target.projectKey(), type, fieldsOf(token, target.projectKey(), type), token, target.defaults());
    }

    /**
     * The create screen from {@link #metaCache} while fresh, else read and kept. A failed read (an exception from
     * {@code read}) is not kept: the next analysis asks Jira again. Keyed by a hash of the authorization header - the
     * token itself is never held.
     */
    private CreateMeta cached(JiraToken token, String kind, Target target, java.util.function.Supplier<CreateMeta> read) {
        String key = fingerprint(token) + "|" + kind + "|" + target.projectKey() + "|"
                + target.issueType().toLowerCase(Locale.ROOT);
        return metaCache.get(key, k -> read.get());
    }

    private static String fingerprint(JiraToken token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.authorization().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CreateMeta meta(String projectKey, JiraIssueType type, List<JiraFieldMeta> fields, JiraToken token,
                            JiraProperties.CreateDefaults defaults) {
        List<CreateField> asked = fields.stream()
                .filter(f -> JiraFieldMeta.COMPONENTS.equals(f.fieldId())
                        || f.required() && !f.hasDefaultValue() && !FILLED.contains(f.fieldId()))
                .map(CreateField::of)
                .map(f -> f.withConfigured(defaults.of(f.fieldId(), f.name())))
                .toList();
        JiraFieldMeta versions = versionsField(fields);
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        if (versions != null) {
            for (String name : versions.allowedNames()) {
                byLowerCase.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), name);
            }
        }
        CreateField country = fields.stream()
                .filter(f -> props.countryField().equalsIgnoreCase(f.fieldId()) || props.countryField().equalsIgnoreCase(f.name()))
                .findFirst().map(CreateField::of).orElse(null);
        return new CreateMeta(projectKey, type.id(), type.name(), byLowerCase, asked, token.cloud(), versions != null,
                versions != null && versions.required(), country);
    }

    /** Sub-task types are left out: they cannot be created without a parent. */
    private List<JiraIssueType> creatableTypes(JiraToken token, String projectKey) {
        return pages(from -> jira.createIssueTypes(token.authorization(), projectKey, from, props.pageSize()))
                .stream().filter(t -> !Boolean.TRUE.equals(t.subtask())).toList();
    }

    private List<JiraFieldMeta> fieldsOf(JiraToken token, String projectKey, JiraIssueType type) {
        return pages(from -> jira.createFields(token.authorization(), projectKey, type.id(), from, props.pageSize()));
    }

    private static JiraFieldMeta versionsField(List<JiraFieldMeta> fields) {
        return fields.stream().filter(f -> JiraFieldMeta.VERSIONS.equals(f.fieldId())).findFirst().orElse(null);
    }

    private static JiraIssueType byName(List<JiraIssueType> types, String name) {
        return types.stream().filter(t -> name.equalsIgnoreCase(t.name())).findFirst().orElse(null);
    }

    private static String notCreatable(String projectKey, String wanted, List<JiraIssueType> types) {
        return types.isEmpty()
                ? "no issue type can be created in project " + projectKey + " with this token"
                : "issue type '" + wanted + "' cannot be created in project " + projectKey
                        + " (creatable: " + String.join(", ", types.stream().map(JiraIssueType::name).toList()) + ")";
    }

    private static String noAffectsVersion(String projectKey, JiraIssueType type) {
        return "Affects Version/s is not on the create screen of " + type.name() + " in project " + projectKey
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
            // the Jira version to file under: read by createVersion, the create request sets it itself
            if (!known.contains(key) && !JiraFieldMeta.VERSIONS.equals(key)) {
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
            throw new FieldsRequiredException(meta.projectKey(), meta.issueType(), missing);
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

    /**
     * The page's Jira version choice in Jira's spelling, in the project's order. The report's version plays no
     * part (asked for, 2026-11-06): it is shown, never searched or filed under.
     *
     * @param chosen version names, any case; {@code null} / empty = no version chosen
     * @throws IllegalArgumentException a version the issue type cannot be filed against
     */
    public List<String> selectedVersions(CreateMeta meta, List<String> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : chosen) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String version = meta.versions().get(name.trim().toLowerCase(Locale.ROOT));
            if (version == null) {
                throw new IllegalArgumentException("project " + meta.projectKey() + " has no version '" + name.trim()
                        + "' to file a " + meta.issueType() + " against");
            }
            wanted.add(version);
        }
        return meta.versions().values().stream().filter(wanted::contains).toList();
    }

    /** The Country options of the screen, Jira's order; empty without the field. */
    static List<String> countries(CreateMeta meta) {
        return meta.country() == null ? List.of()
                : meta.country().options().stream().map(CreateField.Option::label).toList();
    }

    /**
     * The page's Country choice in Jira's spelling and order (asked for, 2026-11-11): searched with
     * {@code field in (...)}, like the versions.
     *
     * @param chosen option labels (or ids), any case; {@code null} / empty = any country
     * @throws IllegalArgumentException a country the field does not offer, or no Country field on the screen
     */
    public CountryFilter countryFilter(CreateMeta meta, List<String> chosen) {
        List<String> wanted = chosen == null ? List.of()
                : chosen.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
        if (wanted.isEmpty()) {
            return CountryFilter.ANY;
        }
        CreateField field = meta.country();
        if (field == null) {
            throw new IllegalArgumentException("the " + meta.issueType() + " create screen of " + meta.projectKey()
                    + " has no field '" + props.countryField() + "' (veracode.jira.country-field) to search by");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String name : wanted) {
            CreateField.Option option = field.option(name);
            if (option == null) {
                throw new IllegalArgumentException(field.name() + " has no option '" + name + "'");
            }
            ids.add(option.id());
        }
        return new CountryFilter(Jql.fieldRef(field.fieldId(), field.name()), field.options().stream()
                .filter(o -> ids.contains(o.id())).map(CreateField.Option::label).toList());
    }

    /**
     * The version a created issue is filed against: the page's Jira fields' {@code versions} (asked for, 2026-11-13: the
     * lookup's versions are optional, and choosing one to create with narrowed the lookup too), else - a caller that
     * does not send it - the one selected lookup version.
     *
     * @param fields the page's create values; {@code versions} = one version name, checked against the create screen
     * @param lookup from {@link #selectedVersions}: the lookup's versions
     */
    public String createVersion(CreateMeta meta, Map<String, Object> fields, List<String> lookup) {
        List<String> chosen = texts(fields == null ? null : fields.get(JiraFieldMeta.VERSIONS));
        return createVersion(chosen.isEmpty() ? lookup : selectedVersions(meta, chosen));
    }

    /**
     * The one version in {@code selected}. None or several is {@link VersionRequiredException} - answered before
     * anything is created.
     */
    public String createVersion(List<String> selected) {
        if (selected.size() != 1) {
            throw new VersionRequiredException(selected);
        }
        return selected.getFirst();
    }

    /** Creating without exactly one Jira version chosen on the page. */
    public static class VersionRequiredException extends RuntimeException {
        public VersionRequiredException(List<String> selected) {
            super(selected.isEmpty()
                    ? "choose the Jira version to file the issues against (Jira fields on the page)"
                    : "choose one Jira version to file the issues against, not " + selected.size() + " ("
                            + String.join(", ", selected) + ")");
        }
    }

    /**
     * One JQL search per {@code search-batch-size} findings, {@code affectedVersion in (...)} the selected versions
     * (none selected = any version, or none).
     *
     * @param versions  from {@link #selectedVersions}
     * @param countries from {@link #countryFilter}: Jira alone decides it - the issues are read without the field
     * @return finding id -> covering issues, every finding present
     */
    public Map<String, List<IssueRef>> find(List<Finding> findings, List<String> versions, CountryFilter countries,
                                            JiraToken token) {
        Map<String, List<IssueRef>> result = new LinkedHashMap<>();
        findings.forEach(f -> result.put(f.id(), new ArrayList<>()));
        for (int from = 0; from < findings.size(); from += props.searchBatchSize()) {
            List<Finding> batch = findings.subList(from, Math.min(findings.size(), from + props.searchBatchSize()));
            String jql = Jql.lookup(props.projectKey(), versions, countries.field(), countries.values(), batch);
            if (jql == null) {
                continue;
            }
            for (JiraIssue issue : search(jql, token)) {
                for (Finding f : batch) {
                    if (covers(issue, f, versions, props.summaryTemplate())) {
                        result.get(f.id()).add(toRef(issue));
                    }
                }
            }
        }
        return result;
    }

    /** Every page of one search; Cloud and Data Center page differently. */
    private List<JiraIssue> search(String jql, JiraToken token) {
        Map<String, JiraIssue> byKey = new LinkedHashMap<>();
        if (token.cloud()) {
            String next = null;
            do {
                String from = next;
                JiraSearchResult page = UpstreamException.call(JIRA,
                        () -> jira.searchJql(token.authorization(), jql, FIELDS, from, props.pageSize()));
                page.issuesOrEmpty().forEach(i -> byKey.putIfAbsent(i.key(), i));
                next = page.issuesOrEmpty().isEmpty() || Boolean.TRUE.equals(page.isLast()) ? null : page.nextPageToken();
            } while (next != null);
        } else {
            int startAt = 0;
            while (true) {
                int from = startAt;
                JiraSearchResult page = UpstreamException.call(JIRA,
                        () -> jira.search(token.authorization(), jql, FIELDS, from, props.pageSize()));
                page.issuesOrEmpty().forEach(i -> byKey.putIfAbsent(i.key(), i));
                startAt += page.issuesOrEmpty().size();
                if (page.issuesOrEmpty().isEmpty() || page.total() != null && startAt >= page.total()) {
                    break;
                }
            }
        }
        return List.copyOf(byKey.values());
    }


    /** @param fields from {@link #resolveFields}: the create screen's other fields, already in Jira's shape */
    public IssueRef create(Finding finding, String jiraVersion, Map<String, Object> fields, ReportView report,
                           JiraToken token) {
        List<String> labels = labels(finding);
        String summary = summary(finding);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", Map.of("key", props.projectKey()));
        body.put("issuetype", Map.of("name", token.issueTypeOr(props.issueType())));
        body.put("summary", summary);
        body.put("description", JiraDescription.of(finding, report, apps));
        body.put("versions", List.of(Map.of("name", jiraVersion)));
        body.put("labels", labels);
        body.putAll(fields);
        JiraCreatedIssue created = UpstreamException.call(JIRA,
                () -> jira.createIssue(token.authorization(), new JiraCreateIssueRequest(body)));
        log.info("created {} for {} (report {})", created.key(), finding.id(), report.fileName());
        return new IssueRef(created.key(), summary, null, null, null, List.of(jiraVersion), labels,
                props.browse(created.key()));
    }

    /**
     * The issue another application already has for this finding: the finding's application label is added
     * ({@code update.labels.add}, the other labels untouched) instead of filing a second issue, and the description
     * rewritten (asked for, 2026-11-13: not appended to) as one "Veracode SCA finding" table per application - this
     * analysis' applications from its findings, the others' tables kept from the old description
     * ({@link JiraDescription#mergeSca}).
     *
     * @param sharing  the analysis' findings of the same {@link Finding#issueKey}, this one among them
     * @param reportOf each finding's report: its Source line
     */
    public IssueRef addApplicationLabel(IssueRef issue, Finding finding, List<Finding> sharing,
                                        Function<Finding, ReportView> reportOf, JiraToken token) {
        String label = applicationLabel(finding);
        return label == null ? issue : addLabels(issue, List.of(label), finding.id(),
                current -> JiraDescription.mergeSca(current, sharing, reportOf, apps), token);
    }

    /**
     * The labels the issue lacks, added in one update ({@code update.labels.add} each, the others untouched), the
     * description rewritten in the same update. It is read first: the analysis may not hold every application the
     * issue covers, and those applications' parts are kept (asked for, 2026-11-13).
     *
     * @param description the issue's current description ({@code null} = none) -> the one to write
     */
    IssueRef addLabels(IssueRef issue, List<String> labels, String forId, UnaryOperator<String> description,
                       JiraToken token) {
        List<String> missing = labels.stream().filter(l -> !issue.hasLabel(l)).toList();
        if (missing.isEmpty()) {
            return issue;
        }
        String current = UpstreamException.call(JIRA,
                () -> jira.issue(token.authorization(), issue.key(), "description")).description();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("update", Map.of("labels", missing.stream().map(l -> Map.of("add", l)).toList()));
        update.put("fields", Map.of("description", description.apply(current)));
        UpstreamException.call(JIRA, () -> {
            jira.updateIssue(token.authorization(), issue.key(), update);
            return null;
        });
        log.info("labelled {} with {} for {}, description updated", issue.key(), missing, forId);
        return issue.withLabels(missing);
    }

    /**
     * The label of the finding's Veracode application: its mapped name ({@code veracode.applications}), else its
     * {@code app_name} - Jira-safe; {@code null} without one.
     */
    public String applicationLabel(Finding finding) {
        return label(apps.name(finding.application()));
    }

    /**
     * Attaches the report files an issue was filed from (uploaded ones: a downloaded file is linked from the
     * description instead). One request; Jira's XSRF check wants {@code X-Atlassian-Token: no-check}.
     */
    public void attach(String key, List<VeracodeAnalysisService.UploadedFile> files, JiraToken token) {
        if (files.isEmpty()) {
            return;
        }
        UpstreamException.call(JIRA, () -> {
            jira.addAttachments(token.authorization(), "no-check", key, MultipartFiles.of(files));
            return null;
        });
        log.info("attached {} to {}", files.stream().map(VeracodeAnalysisService.UploadedFile::filename).toList(), key);
    }

    /**
     * A created issue's labels: the configured ones, {@code project-name}, the application's. Not the {@code cve_id}
     * (asked for, 2026-11-06): the summary carries it, and matching reads the summary.
     */
    List<String> labels(Finding finding) {
        Set<String> labels = new LinkedHashSet<>();
        props.labels().forEach(l -> addLabel(labels, label(l)));
        addLabel(labels, label(props.project()));
        addLabel(labels, applicationLabel(finding));
        return List.copyOf(labels);
    }

    private static void addLabel(Set<String> labels, String label) {
        if (label != null && labels.stream().noneMatch(label::equalsIgnoreCase)) {
            labels.add(label);
        }
    }

    /** Jira refuses a label with a space in it: spaces become {@code _}. */
    private static String label(String text) {
        return text == null || text.isBlank() ? null : text.trim().replaceAll("\\s+", "_");
    }

    /**
     * {@code summary-template} filled in ({@code {project} - {cve}|{cwe} - {file}} by default), whitespace collapsed;
     * Jira limits a summary to 255 characters, so a long one is cut.
     */
    String summary(Finding finding) {
        String summary = props.summaryTemplate()
                .replace("{project}", props.project())
                .replace("{jiraProject}", props.projectKey())
                .replace("{application}", Objects.requireNonNullElse(finding.application(), ""))
                .replace("{component}", String.join(", ", finding.components()))
                .replace("{cve}", finding.cveText())
                .replace("{cwe}", finding.cweText())
                .replace("{file}", Objects.requireNonNullElse(finding.fileName(), ""))
                .replace("{version}", Objects.requireNonNullElse(finding.affectedVersion(), ""))
                .replaceAll("\\s+", " ").trim();
        return summary.length() <= 255 ? summary : summary.substring(0, 254) + "…";
    }

    /**
     * @param versions the selected Jira versions; empty = any
     * @param template the summary template: the file and the components are asked for only where it writes them,
     *                 otherwise the issues this page creates could never match
     */
    static boolean covers(JiraIssue issue, Finding finding, List<String> versions, String template) {
        boolean versionMatches = versions.isEmpty()
                || issue.versionNames().stream().anyMatch(v -> versions.stream().anyMatch(v::equalsIgnoreCase));
        return versionMatches && summaryNames(issue.summary(), finding)
                && (!template.contains("{file}") || named(issue.summary(), finding.fileName()))
                && (!template.contains("{component}") || componentsNamed(issue.summary(), finding));
    }

    /**
     * Every component of the finding appears in the summary as a run of whole words, case and separators ignored:
     * {@code log4j-core 2.14.1} is in "creed-platform log4j-core 2.14.1 CVE-2021-44228", not in "log4j-api 2.14.1 ..."
     * nor in "log4j-core 2.14.10 ...".
     */
    static boolean componentsNamed(String summary, Finding finding) {
        return finding.components().stream().allMatch(c -> named(summary, c));
    }

    /**
     * The text appears in the summary as a run of whole words, case and separators ignored: the file
     * {@code log4j-core-2.14.1.jar} is in "creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar", not in
     * "... log4j-core-2.14.10.jar". Blank text = nothing to ask for.
     */
    static boolean named(String summary, String text) {
        String wanted = Finding.normalise(text);
        return wanted.isEmpty() || (" " + Finding.normalise(summary) + " ").contains(" " + wanted + " ");
    }

    /**
     * The vulnerability part of the match. A CVE: its year and number with any run of space / {@code -} /
     * {@code _} (or nothing) between the parts, case ignored - but not inside a longer id, so {@code CVE-2021-4422}
     * does not match a summary about {@code CVE-2021-44228}. Another id ({@code SRCCLR-SID-29587}): its words as a
     * run. No id: "Unknown" and the CWE - the CWE is then what tells one Unknown from another.
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
        if (finding.cveId() != null) {
            return named(summary, finding.cveId());
        }
        return named(summary, Finding.UNKNOWN) && finding.cwe() != null && named(summary, finding.cwe());
    }

    /**
     * SAST lookups: one JQL per {@code search-batch-size} issues in the SAST project, by summary only.
     *
     * @return SAST issue id -> covering Jira issues, every issue present
     */
    public Map<String, List<IssueRef>> findSast(List<SastIssue> issues, CountryFilter countries, JiraToken token) {
        Map<String, List<IssueRef>> result = new LinkedHashMap<>();
        issues.forEach(i -> result.put(i.id(), new ArrayList<>()));
        for (int from = 0; from < issues.size(); from += props.searchBatchSize()) {
            List<SastIssue> batch = issues.subList(from, Math.min(issues.size(), from + props.searchBatchSize()));
            String jql = Jql.sastLookup(props.sastProjectKey(), countries.field(), countries.values(), batch);
            if (jql == null) {
                continue;
            }
            for (JiraIssue issue : search(jql, token)) {
                for (SastIssue i : batch) {
                    if (coversSast(issue.summary(), i)) {
                        result.get(i.id()).add(toRef(issue));
                    }
                }
            }
        }
        return result;
    }

    /**
     * @param jiraVersion the version chosen for SAST on the page; {@code null} = none (the field is then left out)
     * @param fields      from {@link #resolveFields} on {@link #sastCreateMeta}
     * @param reports     the reports of the issue's applications - the description's source
     */
    public IssueRef createSast(SastIssue issue, String jiraVersion, Map<String, Object> fields, List<ReportView> reports,
                               JiraToken token) {
        List<String> labels = sastLabels(issue);
        String summary = sastSummary(issue);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", Map.of("key", props.sastProjectKey()));
        body.put("issuetype", Map.of("name", props.sastIssueType()));
        body.put("summary", summary);
        body.put("description", JiraDescription.ofSast(issue, reports, apps, props.sastProject()));
        if (jiraVersion != null) {
            body.put("versions", List.of(Map.of("name", jiraVersion)));
        }
        body.put("labels", labels);
        body.putAll(fields);
        JiraCreatedIssue created = UpstreamException.call(JIRA,
                () -> jira.createIssue(token.authorization(), new JiraCreateIssueRequest(body)));
        log.info("created {} for {} ({} open flaws)", created.key(), issue.id(), issue.flaws().size());
        return new IssueRef(created.key(), summary, null, null, null,
                jiraVersion == null ? List.of() : List.of(jiraVersion), labels, props.browse(created.key()));
    }

    /**
     * A SAST issue's labels, as SCA's: {@code sast.labels}, {@code sast.project-name} and the label of every application
     * with an open flaw in it (its mapped name, else its {@code app_name}), Jira-safe (spaces become {@code _}).
     */
    List<String> sastLabels(SastIssue issue) {
        Set<String> labels = new LinkedHashSet<>();
        props.sast().labels().forEach(l -> addLabel(labels, label(l)));
        addLabel(labels, label(props.sastProject()));
        sastApplicationLabels(issue).forEach(l -> addLabel(labels, l));
        return List.copyOf(labels);
    }

    /** The labels of the issue's applications (mapped name, else {@code app_name}), Jira-safe. */
    public List<String> sastApplicationLabels(SastIssue issue) {
        Set<String> labels = new LinkedHashSet<>();
        issue.applications().forEach(a -> addLabel(labels, label(apps.name(a))));
        return List.copyOf(labels);
    }

    /**
     * The application labels that none of the issue's Jira issues carries yet - as for SCA, an application whose
     * flaws another application's issue already covers gets its label there, not a second issue. Empty when the issue
     * is not in Jira.
     */
    public List<String> missingSastLabels(SastIssue issue, List<IssueRef> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }
        return sastApplicationLabels(issue).stream().filter(l -> issues.stream().noneMatch(i -> i.hasLabel(l))).toList();
    }

    /**
     * {@link #missingSastLabels} added to the first of the issues, and its description rewritten as a new issue's
     * would be (asked for, 2026-11-13: in line with the page's "Jira summary", one block per application - a new
     * application changes it), the parts of applications this analysis does not hold kept
     * ({@link JiraDescription#mergeSast}).
     *
     * @param reports the analysis' reports: the description's sources
     */
    public IssueRef addSastLabels(SastIssue issue, List<IssueRef> issues, List<ReportView> reports, JiraToken token) {
        return addLabels(issues.getFirst(), missingSastLabels(issue, issues), issue.id(),
                current -> JiraDescription.mergeSast(current, issue, reports, apps, props.sastProject()), token);
    }

    /** {@code sast.summary-template} filled in ({@code {project} - {cwe} - {category}}), cut to Jira's 255. */
    public String sastSummary(SastIssue issue) {
        String summary = props.sast().summaryTemplate()
                .replace("{project}", props.sastProject())
                .replace("{jiraProject}", props.sastProjectKey())
                .replace("{cwe}", issue.cweText())
                .replace("{category}", issue.categoryText())
                .replaceAll("\\s+", " ").trim();
        return summary.length() <= 255 ? summary : summary.substring(0, 254) + "…";
    }

    /**
     * A summary covers a SAST issue when it names the CWE and the category, each as a run of whole words, case and
     * separators ignored: {@code CWE-89} is not in "... CWE-890 ...", and the category is not a prefix of a longer one.
     */
    static boolean coversSast(String summary, SastIssue issue) {
        return named(summary, issue.cweText()) && named(summary, issue.categoryText());
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
                issue.assigneeName(), issue.versionNames(),
                issue.fields() == null || issue.fields().labels() == null ? List.of() : List.copyOf(issue.fields().labels()),
                props.browse(issue.key()));
    }
}
