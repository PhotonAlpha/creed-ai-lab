package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraIssueType;
import com.creed.resource.veracode.client.jira.JiraPage;
import com.creed.resource.veracode.client.jira.JiraPickerResult;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Jira project's shapes the mock does not have: other issue types, no versions field, foreign picks. */
class JiraLookupServiceTest {

    private static final JiraToken TOKEN = new JiraToken("pat");

    @Test
    void anIssueTypeThatCannotBeCreatedFailsTheConnectionTestButNotTheToken() {
        FakeJira jira = new FakeJira();
        jira.types = List.of(new JiraIssueType("3", "Task", false));

        JiraLookupService.Connection c = service(jira).connection(TOKEN);

        assertThat(c.ok()).isFalse();
        assertThat(c.tokenAccepted()).isTrue();
        assertThat(c.message()).contains("issue type 'Bug' cannot be created in project CREED (creatable: Task)");
    }

    @Test
    void withoutAffectsVersionOnTheCreateScreenNothingCanBeLookedUp() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(new JiraFieldMeta("summary", "Summary", true, false, null, null, null));

        assertThatThrownBy(() -> service(jira).createMeta(TOKEN))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("Affects Version/s is not on the create screen of Bug");
    }

    @Test
    void picksFromAnotherProjectOrGoneAreNotMatches() {
        FakeJira jira = new FakeJira();
        jira.picked = List.of("CREED-1", "OTHER-7", "CREED-2", "CREED-1");
        jira.issues = Map.of(
                "CREED-1", issue("CREED-1", "CREED", "[Veracode] CVE-2021-44228 log4j"),
                "OTHER-7", issue("OTHER-7", "OTHER", "CVE-2021-44228 elsewhere"));
        Finding log4shell = new Finding("CVE-2021-44228@2.3.0", "CVE-2021-44228", "Log4Shell", List.of(), null, null,
                "2.3.0", null, null, List.of());

        Map<String, List<JiraLookupService.IssueRef>> found =
                service(jira).find(List.of(log4shell), Map.of("2.3.0", "2.3.0"), TOKEN);

        assertThat(found.get(log4shell.id())).extracting(JiraLookupService.IssueRef::key).containsExactly("CREED-1");
        // one read per key, the duplicate pick included; CREED-2 answered 404 and was skipped
        assertThat(jira.reads).containsExactly("CREED-1", "OTHER-7", "CREED-2");
        assertThat(jira.lastJql).isEqualTo(
                "project = \"CREED\" AND affectedVersion = \"2.3.0\" AND summary ~ \"\\\"CVE 2021 44228\\\"\"");
    }

    @Test
    void theCreateScreenDecidesWhatThePageAsksFor() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0"), null),
                // asked for although optional: Component/s was asked for on the page
                new JiraFieldMeta("components", "Component/s", false, false, ARRAY, values("Security"), null),
                new JiraFieldMeta("customfield_1", "Team", true, false, new JiraFieldMeta.Schema("option", null, null, "select"),
                        options("Red", "Blue"), null),
                new JiraFieldMeta("customfield_2", "Approver", true, false, new JiraFieldMeta.Schema("user", null, null, "userpicker"),
                        null, null),
                new JiraFieldMeta("customfield_3", "Story Points", true, false, new JiraFieldMeta.Schema("number", null, null, "float"),
                        null, null),
                new JiraFieldMeta("customfield_4", "Region", true, false, new JiraFieldMeta.Schema("option", null, null, "select"),
                        options("EMEA"), null),
                // Jira fills these itself, or this service does
                new JiraFieldMeta("reporter", "Reporter", true, true, null, null, null),
                new JiraFieldMeta("summary", "Summary", true, false, null, null, null),
                new JiraFieldMeta("priority", "Priority", false, false, null, null, null));
        JiraLookupService service = service(jira);

        JiraLookupService.CreateMeta meta = service.createMeta(TOKEN);
        assertThat(meta.fields()).extracting(CreateField::name)
                .containsExactly("Component/s", "Team", "Approver", "Story Points", "Region");
        assertThat(meta.fields()).extracting(CreateField::kind).containsExactly(CreateField.Kind.OPTIONS,
                CreateField.Kind.OPTIONS, CreateField.Kind.USER, CreateField.Kind.NUMBER, CreateField.Kind.OPTIONS);
        // the only option of a required field is preselected
        assertThat(meta.fields().get(4).defaults()).containsExactly("o-EMEA");
        assertThat(meta.fields().get(1).defaults()).isEmpty();
        assertThat(service.connection(TOKEN).message())
                .endsWith("to choose on the page: Team, Approver, Story Points, Region");

        Map<String, Object> json = service.resolveFields(meta, Map.of("customfield_1", "blue", "customfield_2", "alice",
                "customfield_3", "3", "customfield_4", List.of("o-EMEA"), "components", List.of("Security")));
        assertThat(json).containsEntry("components", List.of(Map.of("id", "o-Security")))
                .containsEntry("customfield_1", Map.of("id", "o-Blue"))
                .containsEntry("customfield_2", Map.of("name", "alice"))
                .containsEntry("customfield_3", 3.0)
                .containsEntry("customfield_4", Map.of("id", "o-EMEA"));
        // optional and left empty: not sent
        assertThat(service.resolveFields(meta, Map.of("customfield_1", "Red", "customfield_2", "a", "customfield_3", "1",
                "customfield_4", "EMEA"))).doesNotContainKey("components");
        assertThatThrownBy(() -> service.resolveFields(meta, Map.of("customfield_1", "Red")))
                .isInstanceOf(JiraLookupService.FieldsRequiredException.class)
                .hasMessage("Jira requires Approver, Story Points, Region to create a Bug in CREED: choose them on the page");
        assertThatThrownBy(() -> service.resolveFields(meta, Map.of("customfield_3", "many")))
                .hasMessage("Story Points takes a number, got 'many'");
    }

    @Test
    void jirasOwnDefaultIsPreselected() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0"), null),
                new JiraFieldMeta("components", "Component/s", true, false, ARRAY, values("Security", "Platform"),
                        JsonMapper.builder().build().readTree("[{\"id\":\"o-Platform\",\"name\":\"Platform\"}]")));

        assertThat(service(jira).createMeta(TOKEN).fields().getFirst().defaults()).containsExactly("o-Platform");
    }

    @Test
    void theTokenSaysCloudOrDataCenter() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0"), null),
                new JiraFieldMeta("customfield_2", "Approver", true, false, new JiraFieldMeta.Schema("user", null, null, "userpicker"),
                        null, null));
        JiraLookupService service = service(jira);

        // Jira Cloud: API token + Atlassian e-mail as Basic, and a user is an account id
        JiraLookupService.CreateMeta cloud = service.createMeta(new JiraToken("api-token", "me@example.com"));
        assertThat(jira.lastAuthorization).isEqualTo("Basic "
                + java.util.Base64.getEncoder().encodeToString("me@example.com:api-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(service.resolveFields(cloud, Map.of("customfield_2", "5b10ac8d82e05b22cc7d4ef5")))
                .containsEntry("customfield_2", Map.of("accountId", "5b10ac8d82e05b22cc7d4ef5"));
        // Data Center: a personal access token alone is a Bearer, and a user is a name
        JiraLookupService.CreateMeta dataCenter = service.createMeta(TOKEN);
        assertThat(jira.lastAuthorization).isEqualTo("Bearer pat");
        assertThat(service.resolveFields(dataCenter, Map.of("customfield_2", "alice")))
                .containsEntry("customfield_2", Map.of("name", "alice"));
    }

    @Test
    void cloudPagesNameTheirItems() {
        JsonMapper mapper = JsonMapper.builder().build();
        // Jira Cloud: issueTypes / fields instead of Data Center's values, and no isLast
        JiraPage<JiraIssueType> types = mapper.readValue("{\"startAt\":0,\"maxResults\":50,\"total\":1,"
                + "\"issueTypes\":[{\"id\":\"10001\",\"name\":\"Bug\"}]}", new tools.jackson.core.type.TypeReference<>() { });
        JiraPage<JiraFieldMeta> fields = mapper.readValue("{\"startAt\":0,\"maxResults\":50,\"total\":1,"
                + "\"fields\":[{\"fieldId\":\"versions\",\"name\":\"Affects versions\",\"required\":true,\"hasDefaultValue\":false}]}",
                new tools.jackson.core.type.TypeReference<>() { });
        assertThat(types.valuesOrEmpty()).extracting(JiraIssueType::name).containsExactly("Bug");
        assertThat(fields.valuesOrEmpty()).extracting(JiraFieldMeta::fieldId).containsExactly("versions");
    }

    private static final JiraFieldMeta.Schema ARRAY = new JiraFieldMeta.Schema("array", "component", null, null);

    /** Custom options carry a {@code value}, not a {@code name}. */
    private static List<JsonNode> options(String... values) {
        JsonMapper mapper = JsonMapper.builder().build();
        return java.util.Arrays.stream(values)
                .map(v -> (JsonNode) mapper.createObjectNode().put("id", "o-" + v).put("value", v)).toList();
    }

    private static List<JsonNode> values(String... names) {
        JsonMapper mapper = JsonMapper.builder().build();
        return java.util.Arrays.stream(names)
                .map(n -> (JsonNode) mapper.createObjectNode().put("id", "o-" + n).put("name", n)).toList();
    }

    private static JiraIssue issue(String key, String project, String summary) {
        return new JiraIssue("1", key, new JiraIssue.Fields(summary, new JiraIssue.Status("To Do"),
                List.of(JiraVersion.named("2.3.0")), List.of(), null, null, new JiraIssue.Project(project)));
    }

    private static JiraLookupService service(JiraClient jira) {
        return new JiraLookupService(jira, new JiraProperties("http://jira", "CREED", "Bug", "{project} {component} {cve}",
                List.of("veracode"), "http://jira/browse/{key}", 1, 5,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    /** Pages of one item each (pageSize 1), so paging is walked as well. */
    private static final class FakeJira implements JiraClient {

        List<JiraIssueType> types = List.of(new JiraIssueType("1", "Bug", false));
        List<JiraFieldMeta> fields = List.of(
                new JiraFieldMeta("summary", "Summary", true, false, null, null, null),
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0"), null));
        List<String> picked = List.of();
        Map<String, JiraIssue> issues = Map.of();
        final List<String> reads = new ArrayList<>();
        String lastJql;
        String lastAuthorization;

        @Override
        public JiraCreatedIssue createIssue(String authorization, JiraCreateIssueRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JiraPickerResult picker(String authorization, String query, String currentJQL, boolean showSubTasks) {
            lastJql = currentJQL;
            return new JiraPickerResult(List.of(new JiraPickerResult.Section("cs", "Current Search",
                    picked.stream().map(k -> new JiraPickerResult.Item(k, "")).toList())));
        }

        @Override
        public JiraIssue issue(String authorization, String key, String fields) {
            reads.add(key);
            JiraIssue issue = issues.get(key);
            if (issue == null) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                        "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            return issue;
        }

        @Override
        public JiraPage<JiraIssueType> createIssueTypes(String authorization, String project, int startAt, int maxResults) {
            lastAuthorization = authorization;
            return page(types, startAt);
        }

        @Override
        public JiraPage<JiraFieldMeta> createFields(String authorization, String project, String issueTypeId,
                                                    int startAt, int maxResults) {
            return page(fields, startAt);
        }

        private static <T> JiraPage<T> page(List<T> all, int startAt) {
            List<T> values = all.stream().skip(startAt).limit(1).toList();
            return new JiraPage<>(startAt, 1, all.size(), startAt + values.size() >= all.size(), values);
        }
    }
}
