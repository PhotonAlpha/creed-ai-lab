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
 * Stand-in for the issue-resource endpoints the service calls ({@code /rest/api/2/issue/**}: create, picker,
 * get, createmeta), backed by {@link MockJiraStore}.
 *
 * <p>The picker understands exactly the {@code currentJQL} {@code Jql} builds - {@code project = "..."},
 * {@code affectedVersion = "..."}, {@code summary ~ "..."} - and keeps the Jira behaviours the real code
 * has to survive: an unknown version in the JQL is a 400, {@code summary ~} matches tokens, not substrings
 * ({@code CVE-2021-4104} finds a summary with {@code CVE 2021 4104} too; a quoted value is a phrase, an
 * unquoted one plain words in any order), the {@code query} matches word starts or the key, and the result
 * is capped at {@value #PICKER_LIMIT}. createmeta pages honour {@code startAt}/{@code maxResults}.
 *
 * <p>Authentication, as far as the page's token needs it: creating needs a {@code Bearer} token, and the
 * token {@value #REJECTED_TOKEN} is refused (401) everywhere, so a failed "test connection" can be seen; the
 * reporter is named after the token's last characters. Everything else accepts anonymous calls - the
 * service's configured auth is NONE against the mock.
 */
@RestController
@RequestMapping("/mock/jira")
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockJiraController {

    private static final String LITERAL = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final Pattern PROJECT = Pattern.compile("project\\s*=\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("affectedVersion\\s*=\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY = Pattern.compile("summary\\s*~\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    /** id -> name, as createmeta lists them. */
    private static final Map<String, String> ISSUE_TYPES = new LinkedHashMap<>(Map.of(
            "1", "Bug", "3", "Task", "10001", "Story", "10100", "Vulnerability"));
    static final String REJECTED_TOKEN = "invalid";
    static final int PICKER_LIMIT = 20;

    private final MockJiraStore store;

    @GetMapping("/rest/api/2/issue/createmeta/{project}/issuetypes")
    ResponseEntity<?> issueTypes(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @PathVariable String project,
                                 @RequestParam(defaultValue = "0") int startAt,
                                 @RequestParam(defaultValue = "50") int maxResults) {
        if (rejected(authorization)) {
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
        if (rejected(authorization)) {
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
        List<Map<String, Object>> fields = List.of(
                field("summary", "Summary", true, null),
                field("issuetype", "Issue Type", true, null),
                field("project", "Project", true, null),
                field("description", "Description", false, null),
                field("versions", "Affects Version/s", false, versions),
                field("labels", "Labels", false, null),
                field("priority", "Priority", false, null));
        return ResponseEntity.ok(page(fields, startAt, maxResults));
    }

    @GetMapping("/rest/api/2/issue/picker")
    ResponseEntity<?> picker(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                             @RequestParam(defaultValue = "") String query,
                             @RequestParam(defaultValue = "") String currentJQL) {
        if (rejected(authorization)) {
            return unauthorized();
        }
        Matcher project = PROJECT.matcher(currentJQL);
        if (project.find() && !MockJiraStore.PROJECT.equalsIgnoreCase(unescape(project.group(1)))) {
            return error(HttpStatus.BAD_REQUEST, "The value '" + unescape(project.group(1))
                    + "' does not exist for the field 'project'.", null);
        }
        String version = null;
        Matcher v = VERSION.matcher(currentJQL);
        if (v.find()) {
            version = unescape(v.group(1));
            if (store.versions().stream().noneMatch(version::equalsIgnoreCase)) {
                return error(HttpStatus.BAD_REQUEST,
                        "The value '" + version + "' does not exist for the field 'affectedVersion'.", null);
            }
        }
        // phrase -> contained as is; plain words -> all present, any order
        List<String> phrases = new ArrayList<>();
        List<List<String>> words = new ArrayList<>();
        Matcher s = SUMMARY.matcher(currentJQL);
        while (s.find()) {
            String term = unescape(s.group(1));
            if (term.startsWith("\"")) {
                phrases.add(Finding.normalise(term));
            } else {
                words.add(List.of(Finding.normalise(term).split(" ")));
            }
        }
        List<String> queryWords = Finding.normalise(query).isEmpty() ? List.of()
                : List.of(Finding.normalise(query).split(" "));
        String affected = version;
        List<Map<String, Object>> hits = store.all().stream()
                .filter(i -> affected == null || i.versions().stream().anyMatch(affected::equalsIgnoreCase))
                .filter(i -> phrases.isEmpty() && words.isEmpty()
                        || phrases.stream().anyMatch(p -> (" " + Finding.normalise(i.summary()) + " ").contains(" " + p + " "))
                        || words.stream().anyMatch(w -> List.of(Finding.normalise(i.summary()).split(" ")).containsAll(w)))
                .filter(i -> !queryWords.isEmpty() && (i.key().equalsIgnoreCase(query.trim()) || queryWords.stream()
                        .allMatch(q -> List.of(Finding.normalise(i.summary()).split(" ")).stream().anyMatch(t -> t.startsWith(q)))))
                .limit(PICKER_LIMIT)
                .map(i -> Map.<String, Object>of("key", i.key(), "keyHtml", i.key(), "img", "/images/icons/issuetypes/bug.png",
                        "summary", HtmlUtils.htmlEscape(i.summary()), "summaryText", i.summary()))
                .toList();
        return ResponseEntity.ok(Map.of("sections", List.of(
                Map.of("id", "hs", "label", "History Search", "issues", List.of()),
                Map.of("id", "cs", "label", "Current Search", "sub", "Showing " + hits.size() + " issue(s)",
                        "issues", hits))));
    }

    @GetMapping("/rest/api/2/issue/{key}")
    ResponseEntity<?> issue(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                            @PathVariable String key) {
        if (rejected(authorization)) {
            return unauthorized();
        }
        MockJiraStore.Issue issue = store.find(key);
        if (issue == null) {
            return error(HttpStatus.NOT_FOUND, "Issue Does Not Exist", null);
        }
        return ResponseEntity.ok(toJson(issue));
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
        List<String> versions = new ArrayList<>();
        for (JsonNode version : fields.path("versions")) {
            String name = version.path("name").asString();
            if (store.versions().stream().noneMatch(name::equals)) {
                errors.put("versions", "Version name '" + name + "' is not valid");
            }
            versions.add(name);
        }
        if (!errors.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, null, errors);
        }
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(l -> labels.add(l.asString()));
        MockJiraStore.Issue issue = store.create(summary, fields.path("description").asString(null), versions, labels,
                reporter);
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
        fields.put("priority", issue.priority() == null ? null : Map.of("name", issue.priority()));
        fields.put("assignee", issue.assignee() == null ? null
                : Map.of("name", issue.assignee().toLowerCase().replace(' ', '.'), "displayName", issue.assignee()));
        return Map.of("id", String.valueOf(issue.id()), "key", issue.key(), "self", self(issue), "fields", fields);
    }

    private static Map<String, Object> field(String id, String name, boolean required, List<?> allowedValues) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldId", id);
        field.put("name", name);
        field.put("required", required);
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

    /** The refused token was sent (any other header, or none, is let through). */
    private static boolean rejected(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                && authorization.substring("Bearer ".length()).trim().equals(REJECTED_TOKEN);
    }

    /** The login a Bearer token stands for, {@code null} = no token or the rejected one. */
    private static String user(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
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
