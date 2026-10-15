package com.creed.resource.veracode.mock;

import com.creed.resource.veracode.domain.Finding;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for the three Jira v2 endpoints the service calls, backed by {@link MockJiraStore}.
 *
 * <p>The search understands exactly the JQL {@code Jql} builds - {@code project = "..."},
 * {@code affectedVersion in (...)}, {@code summary ~ "\"...\""} clauses OR'd - and keeps the two Jira
 * behaviours the real code has to survive: an unknown version in the JQL is a 400 for the whole query,
 * and {@code summary ~} matches tokens, not substrings ({@code CVE-2021-4104} finds a summary with
 * {@code CVE 2021 4104} too). Paging honours {@code startAt}/{@code maxResults}.
 */
@RestController
@RequestMapping("/mock/jira")
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockJiraController {

    private static final String LITERAL = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final Pattern PROJECT = Pattern.compile("project\\s*=\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSIONS = Pattern.compile(
            "affectedVersion\\s+in\\s*\\(((?:\\s*" + LITERAL + "\\s*,?)+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY = Pattern.compile("summary\\s*~\\s*" + LITERAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_LITERAL = Pattern.compile(LITERAL);
    private static final Set<String> ISSUE_TYPES = Set.of("bug", "task", "story", "vulnerability");

    private final MockJiraStore store;

    @GetMapping("/rest/api/2/project/{key}/versions")
    ResponseEntity<?> versions(@PathVariable String key) {
        if (!MockJiraStore.PROJECT.equalsIgnoreCase(key)) {
            return error(HttpStatus.NOT_FOUND, "No project could be found with key '" + key + "'.", null);
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        int id = 20_000;
        for (String name : store.versions()) {
            versions.add(Map.of("id", String.valueOf(++id), "name", name, "released", !"2.4.0".equals(name),
                    "archived", false));
        }
        return ResponseEntity.ok(versions);
    }

    @PostMapping("/rest/api/2/search")
    ResponseEntity<?> search(@RequestBody JsonNode body) {
        String jql = body.path("jql").asText("");
        int startAt = Math.max(0, body.path("startAt").asInt(0));
        int maxResults = Math.min(Math.max(1, body.path("maxResults").asInt(50)), 100);

        Matcher project = PROJECT.matcher(jql);
        if (project.find() && !MockJiraStore.PROJECT.equalsIgnoreCase(unescape(project.group(1)))) {
            return error(HttpStatus.BAD_REQUEST, "The value '" + unescape(project.group(1))
                    + "' does not exist for the field 'project'.", null);
        }
        List<String> versions = new ArrayList<>();
        Matcher v = VERSIONS.matcher(jql);
        if (v.find()) {
            Matcher lit = ANY_LITERAL.matcher(v.group(1));
            while (lit.find()) {
                String name = unescape(lit.group(1));
                if (store.versions().stream().noneMatch(name::equalsIgnoreCase)) {
                    return error(HttpStatus.BAD_REQUEST,
                            "The value '" + name + "' does not exist for the field 'affectedVersion'.", null);
                }
                versions.add(name);
            }
        }
        List<String> phrases = new ArrayList<>();
        Matcher s = SUMMARY.matcher(jql);
        while (s.find()) {
            phrases.add(Finding.normalise(unescape(s.group(1))));
        }

        List<MockJiraStore.Issue> hits = store.all().stream()
                .filter(i -> versions.isEmpty() || i.versions().stream().anyMatch(iv -> versions.stream().anyMatch(iv::equalsIgnoreCase)))
                .filter(i -> phrases.isEmpty() || phrases.stream().anyMatch(p ->
                        (" " + Finding.normalise(i.summary()) + " ").contains(" " + p + " ")))
                .toList();
        List<Map<String, Object>> page = hits.stream().skip(startAt).limit(maxResults).map(this::toJson).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("startAt", startAt);
        response.put("maxResults", maxResults);
        response.put("total", hits.size());
        response.put("issues", page);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/rest/api/2/issue")
    ResponseEntity<?> create(@RequestBody JsonNode body) {
        JsonNode fields = body.path("fields");
        Map<String, String> errors = new LinkedHashMap<>();
        if (!MockJiraStore.PROJECT.equalsIgnoreCase(fields.path("project").path("key").asText())) {
            errors.put("project", "project is required");
        }
        if (!ISSUE_TYPES.contains(fields.path("issuetype").path("name").asText().toLowerCase())) {
            errors.put("issuetype", "Specify a valid issue type");
        }
        String summary = fields.path("summary").asText("");
        if (summary.isBlank()) {
            errors.put("summary", "You must specify a summary of the issue.");
        } else if (summary.length() > 255) {
            errors.put("summary", "Summary must be less than 255 characters.");
        }
        List<String> versions = new ArrayList<>();
        for (JsonNode version : fields.path("versions")) {
            String name = version.path("name").asText();
            if (store.versions().stream().noneMatch(name::equals)) {
                errors.put("versions", "Version name '" + name + "' is not valid");
            }
            versions.add(name);
        }
        if (!errors.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, null, errors);
        }
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(l -> labels.add(l.asText()));
        MockJiraStore.Issue issue = store.create(summary, fields.path("description").asText(null), versions, labels);
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
                + " &middot; Labels: " + HtmlUtils.htmlEscape(String.join(", ", issue.labels())) + "</p><pre>"
                + HtmlUtils.htmlEscape(issue.description() == null ? "" : issue.description()) + "</pre>";
        return ResponseEntity.ok(html);
    }

    private Map<String, Object> toJson(MockJiraStore.Issue issue) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", issue.summary());
        fields.put("status", Map.of("name", issue.status()));
        fields.put("versions", issue.versions().stream().map(v -> Map.of("name", v)).toList());
        fields.put("labels", issue.labels());
        return Map.of("id", String.valueOf(issue.id()), "key", issue.key(), "self", self(issue), "fields", fields);
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
