package com.creed.resource.veracode.mock;

import com.creed.resource.veracode.domain.Finding;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for the Jira endpoints the service calls - create and createmeta ({@code /rest/api/2/issue/**}), search
 * (Data Center {@code /rest/api/2/search}, Cloud {@code /rest/api/3/search/jql}) - backed by {@link MockJiraStore}.
 *
 * <p>Search understands exactly the JQL {@code Jql} builds - {@code project = "..."}, {@code affectedVersion in ("...", ...)},
 * {@code summary ~ "..."} OR'ed - and keeps the Jira behaviours the real code has to survive: an unknown version in
 * the JQL is a 400, {@code summary ~} matches tokens, not substrings ({@code CVE-2021-4104} finds a summary with
 * {@code CVE 2021 4104} too; a quoted value is a phrase, an unquoted one plain words in any order), and so it
 * returns more than the service counts as a match (another component's issue for the same CVE). Search and
 * createmeta pages honour {@code startAt}/{@code maxResults}, Cloud's search its {@code nextPageToken}.
 *
 * <p>The create screen is a strict one, as on the Jira this was written against: Affects Version/s, Component/s
 * (components {@link #COMPONENTS}) and a custom single select {@value #ENVIRONMENT_FIELD} "Environment Found"
 * ({@link #ENVIRONMENTS}) are required, and a create without them is Jira's 400. Components and options are named
 * by id in a create, as real Jira takes them.
 *
 * <p>Authentication: every call needs a {@code Bearer} token (Data Center) or {@code Basic} e-mail + API token
 * (Cloud), as on a Jira without anonymous access - the service has no account of its own. The token {@value #REJECTED_TOKEN} is refused (401), so a failed "test
 * connection" can be seen; the reporter is named after the token's last characters.
 */
@RestController
@RequestMapping("/mock/jira")
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockJiraController {

    private static final String LITERAL = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final Pattern PROJECT = Pattern.compile("project\\s*=\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSIONS = Pattern.compile("affectedVersion\\s+in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern A_LITERAL = Pattern.compile(LITERAL);
    private static final Pattern SUMMARY = Pattern.compile("summary\\s*~\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    static final String REJECTED_TOKEN = "invalid";
    static final List<String> COMPONENTS = List.of("Security", "Platform", "Payments");
    static final String ENVIRONMENT_FIELD = "customfield_10100";
    /** option id -> value */
    static final Map<String, String> ENVIRONMENTS = new LinkedHashMap<>();
    /**
     * Task's create screen has no Affects Version/s, as in a Jira Cloud Scrum project: the connection test must say
     * so (and offer the other types) instead of failing as "cannot create".
     */
    static final String NO_VERSIONS_TYPE = "3";

    /** id -> name, as createmeta lists them (insertion order: {@code Map.of} has none). */
    private static final Map<String, String> ISSUE_TYPES = new LinkedHashMap<>();

    static {
        ENVIRONMENTS.put("10200", "Production");
        ENVIRONMENTS.put("10201", "Staging");
        ISSUE_TYPES.put("1", "Bug");
        ISSUE_TYPES.put("3", "Task");
        ISSUE_TYPES.put("10001", "Story");
        ISSUE_TYPES.put("10100", "Vulnerability");
    }

    private final MockJiraStore store;

    @GetMapping("/rest/api/2/issue/createmeta/{project}/issuetypes")
    ResponseEntity<?> issueTypes(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @PathVariable String project,
                                 @RequestParam(defaultValue = "0") int startAt,
                                 @RequestParam(defaultValue = "50") int maxResults) {
        if (user(authorization) == null) {
            return unauthorized();
        }
        if (!MockJiraStore.PROJECT.equalsIgnoreCase(project)) {
            return error(HttpStatus.BAD_REQUEST, "No project could be found with key '" + project + "'.", null);
        }
        List<Map<String, Object>> types = new ArrayList<>();
        ISSUE_TYPES.forEach((id, name) -> types.add(Map.of("id", id, "name", name, "subtask", false,
                "self", "/rest/api/2/issuetype/" + id)));
        return ResponseEntity.ok(page(types, startAt, maxResults));
    }

    @GetMapping("/rest/api/2/issue/createmeta/{project}/issuetypes/{issueTypeId}")
    ResponseEntity<?> createFields(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                   @PathVariable String project, @PathVariable String issueTypeId,
                                   @RequestParam(defaultValue = "0") int startAt,
                                   @RequestParam(defaultValue = "50") int maxResults) {
        if (user(authorization) == null) {
            return unauthorized();
        }
        if (!MockJiraStore.PROJECT.equalsIgnoreCase(project)) {
            return error(HttpStatus.BAD_REQUEST, "No project could be found with key '" + project + "'.", null);
        }
        if (!ISSUE_TYPES.containsKey(issueTypeId)) {
            return error(HttpStatus.NOT_FOUND, "Issue type with id '" + issueTypeId + "' does not exist.", null);
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        int id = 20_000;
        for (String name : store.versions()) {
            versions.add(Map.of("id", String.valueOf(++id), "name", name, "released", !"2.4.0".equals(name),
                    "archived", false));
        }
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
                field("summary", "Summary", true, schema("string", null, "summary", null), null),
                field("issuetype", "Issue Type", true, schema("issuetype", null, "issuetype", null), null),
                field("project", "Project", true, schema("project", null, "project", null), null),
                field("description", "Description", false, schema("string", null, "description", null), null),
                field("versions", "Affects Version/s", true, schema("array", "version", "versions", null), versions),
                field("components", "Component/s", true, schema("array", "component", "components", null),
                        componentValues()),
                field(ENVIRONMENT_FIELD, "Environment Found", true,
                        schema("option", null, null, "com.atlassian.jira.plugin.system.customfieldtypes:select"),
                        environmentValues()),
                field("labels", "Labels", false, schema("array", "string", "labels", null), null),
                field("priority", "Priority", false, schema("priority", null, "priority", null), null)));
        if (NO_VERSIONS_TYPE.equals(issueTypeId)) {
            fields.removeIf(f -> "versions".equals(f.get("fieldId")));
        }
        return ResponseEntity.ok(page(fields, startAt, maxResults));
    }

    /** Data Center search: {@code startAt} / {@code maxResults} / {@code total}. */
    @GetMapping("/rest/api/2/search")
    ResponseEntity<?> search(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                             @RequestParam(defaultValue = "") String jql,
                             @RequestParam(defaultValue = "0") int startAt,
                             @RequestParam(defaultValue = "50") int maxResults) {
        if (user(authorization) == null) {
            return unauthorized();
        }
        Object hits = matching(jql);
        if (hits instanceof ResponseEntity<?> refused) {
            return refused;
        }
        List<?> all = (List<?>) hits;
        int from = Math.min(startAt, all.size());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("startAt", startAt);
        page.put("maxResults", maxResults);
        page.put("total", all.size());
        page.put("issues", all.subList(from, Math.min(all.size(), from + maxResults)));
        return ResponseEntity.ok(page);
    }

    /**
     * Jira Cloud's search ({@code /rest/api/2/search} is retired there): paged by an opaque {@code nextPageToken},
     * {@code isLast} on the last page, no {@code total}. The token here is just the next offset.
     */
    @GetMapping("/rest/api/3/search/jql")
    ResponseEntity<?> searchJql(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @RequestParam(defaultValue = "") String jql,
                                @RequestParam(required = false) String nextPageToken,
                                @RequestParam(defaultValue = "50") int maxResults) {
        if (user(authorization) == null) {
            return unauthorized();
        }
        Object hits = matching(jql);
        if (hits instanceof ResponseEntity<?> refused) {
            return refused;
        }
        List<?> all = (List<?>) hits;
        int from = nextPageToken == null ? 0 : Math.min(Integer.parseInt(nextPageToken), all.size());
        int to = Math.min(all.size(), from + maxResults);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("issues", all.subList(from, to));
        page.put("isLast", to >= all.size());
        if (to < all.size()) {
            page.put("nextPageToken", String.valueOf(to));
        }
        return ResponseEntity.ok(page);
    }

    /**
     * The issues the JQL {@code Jql} builds selects, as issue JSON in key order - or Jira's 400 for an unknown
     * project or version. {@code summary ~} terms are OR'ed, as {@code Jql} joins them.
     */
    private Object matching(String jql) {
        Matcher project = PROJECT.matcher(jql);
        if (project.find() && !MockJiraStore.PROJECT.equalsIgnoreCase(unescape(project.group(1)))) {
            return error(HttpStatus.BAD_REQUEST, "The value '" + unescape(project.group(1))
                    + "' does not exist for the field 'project'.", null);
        }
        // no clause = any version
        List<String> versions = new ArrayList<>();
        Matcher v = VERSIONS.matcher(jql);
        if (v.find()) {
            Matcher literal = A_LITERAL.matcher(v.group(1));
            while (literal.find()) {
                String version = unescape(literal.group(1));
                if (store.versions().stream().noneMatch(version::equalsIgnoreCase)) {
                    return error(HttpStatus.BAD_REQUEST,
                            "The value '" + version + "' does not exist for the field 'affectedVersion'.", null);
                }
                versions.add(version);
            }
        }
        // phrase -> contained as is; plain words -> all present, any order
        List<String> phrases = new ArrayList<>();
        List<List<String>> words = new ArrayList<>();
        Matcher s = SUMMARY.matcher(jql);
        while (s.find()) {
            String term = unescape(s.group(1));
            if (term.startsWith("\"")) {
                phrases.add(Finding.normalise(term));
            } else {
                words.add(List.of(Finding.normalise(term).split(" ")));
            }
        }
        return store.all().stream()
                .filter(i -> versions.isEmpty() || i.versions().stream().anyMatch(iv -> versions.stream().anyMatch(iv::equalsIgnoreCase)))
                .filter(i -> phrases.isEmpty() && words.isEmpty()
                        || phrases.stream().anyMatch(p -> (" " + Finding.normalise(i.summary()) + " ").contains(" " + p + " "))
                        || words.stream().anyMatch(w -> List.of(Finding.normalise(i.summary()).split(" ")).containsAll(w)))
                .map(this::toJson)
                .toList();
    }

    @PostMapping("/rest/api/2/issue")
    ResponseEntity<?> create(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                             @RequestBody JsonNode body) {
        String reporter = user(authorization);
        if (reporter == null) {
            return unauthorized();
        }
        JsonNode fields = body.path("fields");
        Map<String, String> errors = new LinkedHashMap<>();
        if (!MockJiraStore.PROJECT.equalsIgnoreCase(fields.path("project").path("key").asString())) {
            errors.put("project", "project is required");
        }
        String issueType = fields.path("issuetype").path("name").asString();
        if (ISSUE_TYPES.values().stream().noneMatch(issueType::equalsIgnoreCase)) {
            errors.put("issuetype", "Specify a valid issue type");
        }
        String summary = fields.path("summary").asString("");
        if (summary.isBlank()) {
            errors.put("summary", "You must specify a summary of the issue.");
        } else if (summary.length() > 255) {
            errors.put("summary", "Summary must be less than 255 characters.");
        }
        if (ISSUE_TYPES.get(NO_VERSIONS_TYPE).equalsIgnoreCase(issueType) && fields.has("versions")) {
            errors.put("versions", "Field 'versions' cannot be set. It is not on the appropriate screen, or unknown.");
        }
        List<String> versions = new ArrayList<>();
        for (JsonNode version : fields.path("versions")) {
            String name = version.path("name").asString();
            if (store.versions().stream().noneMatch(name::equals)) {
                errors.put("versions", "Version name '" + name + "' is not valid");
            }
            versions.add(name);
        }
        if (versions.isEmpty()) {
            errors.put("versions", "Affects Version/s is required.");
        }
        List<String> components = new ArrayList<>();
        for (JsonNode component : fields.path("components")) {
            String name = componentName(component);
            if (name == null) {
                errors.put("components", "Component with id '" + component.path("id").asString("") + "' is not valid");
            } else {
                components.add(name);
            }
        }
        if (components.isEmpty() && !errors.containsKey("components")) {
            errors.put("components", "Component/s is required.");
        }
        String environment = ENVIRONMENTS.get(fields.path(ENVIRONMENT_FIELD).path("id").asString(""));
        if (environment == null) {
            errors.put(ENVIRONMENT_FIELD, "Environment Found is required.");
        }
        if (!errors.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, null, errors);
        }
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(l -> labels.add(l.asString()));
        MockJiraStore.Issue issue = store.create(summary, fields.path("description").asString(null), versions,
                components, environment, labels, reporter, issueType);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", String.valueOf(issue.id()), "key", issue.key(), "self", self(issue)));
    }

    /** What an issue link in the UI opens. */
    @GetMapping(value = "/browse/{key}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> browse(@PathVariable String key) {
        MockJiraStore.Issue issue = store.find(key);
        if (issue == null) {
            return ResponseEntity.notFound().build();
        }
        String html = "<!doctype html><meta charset=\"utf-8\"><title>" + HtmlUtils.htmlEscape(issue.key())
                + "</title><p><i>mock Jira</i></p><h1>" + HtmlUtils.htmlEscape(issue.key()) + " "
                + HtmlUtils.htmlEscape(issue.summary()) + "</h1><p>Status: " + HtmlUtils.htmlEscape(issue.status())
                + " &middot; Affects: " + HtmlUtils.htmlEscape(String.join(", ", issue.versions()))
                + " &middot; Components: " + HtmlUtils.htmlEscape(String.join(", ", issue.components()))
                + " &middot; Environment Found: " + HtmlUtils.htmlEscape(String.valueOf(issue.environment()))
                + " &middot; Labels: " + HtmlUtils.htmlEscape(String.join(", ", issue.labels()))
                + (issue.reporter() == null ? "" : " &middot; Reporter: " + HtmlUtils.htmlEscape(issue.reporter()))
                + "</p><pre>"
                + HtmlUtils.htmlEscape(issue.description() == null ? "" : issue.description()) + "</pre>";
        return ResponseEntity.ok(html);
    }

    private Map<String, Object> toJson(MockJiraStore.Issue issue) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", issue.summary());
        fields.put("project", Map.of("key", MockJiraStore.PROJECT, "name", "Creed"));
        fields.put("status", Map.of("name", issue.status()));
        fields.put("versions", issue.versions().stream().map(v -> Map.of("name", v)).toList());
        fields.put("labels", issue.labels());
        fields.put("components", issue.components().stream().map(c -> Map.of("name", c)).toList());
        fields.put(ENVIRONMENT_FIELD, issue.environment() == null ? null : Map.of("value", issue.environment()));
        fields.put("priority", issue.priority() == null ? null : Map.of("name", issue.priority()));
        fields.put("assignee", issue.assignee() == null ? null
                : Map.of("name", issue.assignee().toLowerCase().replace(' ', '.'), "displayName", issue.assignee()));
        return Map.of("id", String.valueOf(issue.id()), "key", issue.key(), "self", self(issue), "fields", fields);
    }

    private static List<Map<String, Object>> componentValues() {
        List<Map<String, Object>> values = new ArrayList<>();
        int id = 30_000;
        for (String name : COMPONENTS) {
            values.add(Map.of("id", String.valueOf(++id), "name", name));
        }
        return values;
    }

    private static List<Map<String, Object>> environmentValues() {
        List<Map<String, Object>> values = new ArrayList<>();
        ENVIRONMENTS.forEach((id, value) -> values.add(Map.of("id", id, "value", value)));
        return values;
    }

    /** By id (what the service sends) or name. */
    private static String componentName(JsonNode component) {
        String id = component.path("id").asString(null);
        if (id != null) {
            int index = Integer.parseInt(id) - 30_001;
            return index >= 0 && index < COMPONENTS.size() ? COMPONENTS.get(index) : null;
        }
        String name = component.path("name").asString(null);
        return COMPONENTS.contains(name) ? name : null;
    }

    private static Map<String, Object> schema(String type, String items, String system, String custom) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (items != null) {
            schema.put("items", items);
        }
        if (system != null) {
            schema.put("system", system);
        }
        if (custom != null) {
            schema.put("custom", custom);
        }
        return schema;
    }

    private static Map<String, Object> field(String id, String name, boolean required, Map<String, Object> schema,
                                             List<?> allowedValues) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldId", id);
        field.put("name", name);
        field.put("required", required);
        field.put("hasDefaultValue", false);
        field.put("schema", schema);
        if (allowedValues != null) {
            field.put("allowedValues", allowedValues);
        }
        return field;
    }

    private static Map<String, Object> page(List<?> all, int startAt, int maxResults) {
        int from = Math.max(0, startAt);
        int size = Math.min(Math.max(1, maxResults), 50);
        List<?> values = all.stream().skip(from).limit(size).toList();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("startAt", from);
        page.put("maxResults", size);
        page.put("total", all.size());
        page.put("isLast", from + values.size() >= all.size());
        page.put("values", values);
        return page;
    }

    /** The login a Bearer token stands for, {@code null} = no token or the rejected one. */
    private static String user(String authorization) {
        if (authorization == null) {
            return null;
        }
        if (authorization.startsWith("Basic ")) {
            // Cloud style: base64(email:apiToken); the user is the e-mail
            String decoded;
            try {
                decoded = new String(java.util.Base64.getDecoder().decode(authorization.substring("Basic ".length()).trim()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            int colon = decoded.indexOf(':');
            if (colon <= 0 || decoded.substring(colon + 1).isBlank() || decoded.substring(colon + 1).equals(REJECTED_TOKEN)) {
                return null;
            }
            return decoded.substring(0, colon);
        }
        if (!authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty() || token.equals(REJECTED_TOKEN)) {
            return null;
        }
        return "user-" + token.substring(Math.max(0, token.length() - 4));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "You are not authenticated. Authentication required to perform this operation.", null);
    }

    private static String self(MockJiraStore.Issue issue) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/mock/jira/rest/api/2/issue/{id}").buildAndExpand(issue.id()).toUriString();
    }

    private static String unescape(String literal) {
        return literal.replaceAll("\\\\(.)", "$1");
    }

    /** Jira's error envelope. */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, Map<String, String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorMessages", message == null ? List.of() : List.of(message));
        body.put("errors", errors == null ? Map.of() : errors);
        return ResponseEntity.status(status).body(body);
    }
}
