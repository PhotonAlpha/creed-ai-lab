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
import com.creed.resource.veracode.config.RemoteAuth;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
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
        jira.fields = List.of(new JiraFieldMeta("summary", "Summary", true, null));

        assertThatThrownBy(() -> service(jira).projectVersions(TOKEN))
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

    private static JiraIssue issue(String key, String project, String summary) {
        return new JiraIssue("1", key, new JiraIssue.Fields(summary, new JiraIssue.Status("To Do"),
                List.of(JiraVersion.named("2.3.0")), List.of(), null, null, new JiraIssue.Project(project)));
    }

    private static JiraLookupService service(JiraClient jira) {
        return new JiraLookupService(jira, new JiraProperties("http://jira", "CREED", "Bug", "{project} {component} {cve}",
                true, List.of("veracode"), "http://jira/browse/{key}", 1, 5, new RemoteAuth(null, null, null),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    /** Pages of one item each (pageSize 1), so paging is walked as well. */
    private static final class FakeJira implements JiraClient {

        List<JiraIssueType> types = List.of(new JiraIssueType("1", "Bug", false));
        List<JiraFieldMeta> fields = List.of(
                new JiraFieldMeta("summary", "Summary", true, null),
                new JiraFieldMeta("versions", "Affects Version/s", false,
                        List.of(JsonMapper.builder().build().readTree("{\"id\":\"1\",\"name\":\"2.3.0\"}"))));
        List<String> picked = List.of();
        Map<String, JiraIssue> issues = Map.of();
        final List<String> reads = new ArrayList<>();
        String lastJql;

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
