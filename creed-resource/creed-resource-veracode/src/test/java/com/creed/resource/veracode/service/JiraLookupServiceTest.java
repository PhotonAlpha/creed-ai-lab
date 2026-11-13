package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.client.jira.JiraCreateIssueRequest;
import com.creed.resource.veracode.client.jira.JiraCreatedIssue;
import com.creed.resource.veracode.client.jira.JiraFieldMeta;
import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraIssueType;
import com.creed.resource.veracode.client.jira.JiraPage;
import com.creed.resource.veracode.client.jira.JiraSearchResult;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.domain.ApplicationAliases;
import com.creed.resource.veracode.config.JiraMetaCacheConfiguration;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Jira project's shapes the mock does not have: other issue types, no versions field, search batching and paging. */
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
    void findingsAreLookedUpInBatchesAndMatchedOnComponentAndCve() {
        FakeJira jira = new FakeJira();
        jira.found = List.of(
                issue("CREED-1", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar"),
                // the same CVE, another jar: Jira's summary ~ returns it, it is not this finding's issue
                issue("CREED-2", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-api-2.14.1.jar"),
                issue("CREED-3", "CREED", "creed - CVE-2022-22965|CWE-94 - spring-beans-5.3.17.jar"),
                // a longer version is not the jar's version
                issue("CREED-4", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.10.jar"),
                issue("CREED-1", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar"));
        Finding log4shell = finding("CVE-2021-44228", "log4j-core-2.14.1.jar");
        Finding spring4shell = finding("CVE-2022-22965", "spring-beans-5.3.17.jar");
        Finding text4shell = finding("CVE-2022-42889", "commons-text-1.9.jar");

        Map<String, List<JiraLookupService.IssueRef>> found = service(jira, 2)
                .find(List.of(log4shell, spring4shell, text4shell), List.of("2.3.0"), JiraLookupService.CountryFilter.ANY, TOKEN);

        assertThat(found.get(log4shell.id())).extracting(JiraLookupService.IssueRef::key).containsExactly("CREED-1");
        assertThat(found.get(spring4shell.id())).extracting(JiraLookupService.IssueRef::key).containsExactly("CREED-3");
        assertThat(found.get(text4shell.id())).isEmpty();
        // three findings, batches of two: two searches, not one call (plus a read per candidate) per finding - side by side
        assertThat(jira.jqls).containsExactlyInAnyOrder(
                "project = \"CREED\" AND affectedVersion in (\"2.3.0\") AND (summary ~ \"\\\"CVE-2021-44228\\\"\" OR "
                        + "summary ~ \"\\\"CVE-2022-22965\\\"\") ORDER BY key",
                "project = \"CREED\" AND affectedVersion in (\"2.3.0\") AND (summary ~ \"\\\"CVE-2022-42889\\\"\") ORDER BY key");
        // Data Center pages by startAt: one issue per page here, so every page was read
        assertThat(jira.pages).isEqualTo(5 + 5);
    }

    @Test
    void jiraCloudSearchesThroughSearchJqlAndItsPageToken() {
        FakeJira jira = new FakeJira();
        jira.found = List.of(
                issue("CREED-1", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar"),
                issue("CREED-2", "CREED", "creed - CVE-2021-44228|CWE-502 - log4j-api-2.14.1.jar"));
        Finding log4shell = finding("CVE-2021-44228", "log4j-core-2.14.1.jar");

        Map<String, List<JiraLookupService.IssueRef>> found = service(jira, 50)
                .find(List.of(log4shell), List.of("2.3.0"), JiraLookupService.CountryFilter.ANY, new JiraToken("api-token", "me@example.com"));

        assertThat(found.get(log4shell.id())).extracting(JiraLookupService.IssueRef::key).containsExactly("CREED-1");
        assertThat(jira.cloudPages).isEqualTo(2);
        assertThat(jira.pages).isZero();
    }

    @Test
    void withoutTheFileInTheSummaryTemplateTheCveAloneDecides() {
        FakeJira jira = new FakeJira();
        jira.found = List.of(issue("CREED-2", "CREED", "[Veracode] CVE-2021-44228 log4j"));
        Finding log4shell = finding("CVE-2021-44228", "log4j-core-2.14.1.jar");
        JiraLookupService service = service(jira, properties("[Veracode] {cve}", 50));

        assertThat(service.find(List.of(log4shell), List.of("2.3.0"), JiraLookupService.CountryFilter.ANY, TOKEN).get(log4shell.id()))
                .extracting(JiraLookupService.IssueRef::key).containsExactly("CREED-2");
    }

    @Test
    void theSummaryAndTheLabelsOfACreatedIssue() {
        JiraLookupService service = service(new FakeJira());
        Finding log4shell = new Finding("CREED-PAY-GATEWAY-API|CVE-2021-44228@log4j-core-2.14.1.jar",
                "CREED-PAY-GATEWAY-API", "CVE-2021-44228", "CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar",
                List.of("log4j-core 2.14.1"), null, null, "2.3.0", null, List.of());
        Finding unknown = new Finding("a|Unknown|Unknown@x.jar", "two words", null, null, null, "x.jar", List.of(), null,
                null, null, null, List.of());

        // {project} is project-name, not the Veracode application
        assertThat(service.summary(log4shell)).isEqualTo("creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar");
        assertThat(service.summary(unknown)).isEqualTo("creed - Unknown|Unknown - x.jar");
        // configured labels, project-name, and the application's mapped name - not the cve_id
        assertThat(service.labels(log4shell)).containsExactly("veracode", "creed", "Pay-Gateway");
        assertThat(service.applicationLabel(log4shell)).isEqualTo("Pay-Gateway");
        // no mapping: the app_name itself; Jira takes no spaces in a label
        assertThat(service.labels(unknown)).containsExactly("veracode", "creed", "two_words");
    }

    /**
     * A lookup-condition change re-analyses: the create screen is reused per token. "Test connection" reads afresh and
     * keeps what it read - the analysis after it reads no create screen.
     */
    @Test
    void aCreateScreenIsReusedPerTokenAndRefreshedByTheConnectionTest() {
        FakeJira jira = new FakeJira();
        JiraLookupService service = service(jira, cachedFor(Duration.ofMinutes(1)));

        service.createMeta(TOKEN);
        service.createMeta(TOKEN);
        assertThat(jira.typeReads).isEqualTo(1);
        assertThat(jira.fieldReads).isEqualTo(1);
        // another user's token reads its own
        service.createMeta(new JiraToken("other-token", null));
        assertThat(jira.typeReads).isEqualTo(2);
        // "test connection" reads afresh...
        service.connection(TOKEN);
        assertThat(jira.typeReads).isEqualTo(3);
        int fieldsAfterTest = jira.fieldReads;
        // ...and the analysis after it, SCA and SAST, reuses that read
        service.createMeta(TOKEN);
        service.sastCreateMeta(TOKEN);
        assertThat(jira.typeReads).isEqualTo(3);
        assertThat(jira.fieldReads).isEqualTo(fieldsAfterTest);
    }

    /** SAST on SCA's project and issue type (the default): one analysis, one issue types read and one fields read. */
    @Test
    void scaAndSastShareTheReadsOfOneCreateScreen() {
        FakeJira jira = new FakeJira();
        JiraLookupService service = service(jira, cachedFor(Duration.ofMinutes(1)));

        // side by side, as an analysis runs them
        java.util.concurrent.Future<JiraLookupService.CreateMeta> sast =
                Concurrently.start("test-sast", () -> service.sastCreateMeta(TOKEN));
        JiraLookupService.CreateMeta sca = service.createMeta(TOKEN);

        assertThat(Concurrently.join(sast).issueTypeId()).isEqualTo(sca.issueTypeId());
        assertThat(jira.typeReads).isEqualTo(1);
        assertThat(jira.fieldReads).isEqualTo(1);
    }

    private static JiraProperties cachedFor(Duration ttl) {
        JiraProperties base = properties("{project} - {cve}|{cwe} - {file}", 50);
        return new JiraProperties(base.baseUrl(), base.projectKey(),
                base.issueType(), base.projectName(), base.summaryTemplate(), base.labels(), base.browseUrl(),
                base.pageSize(), base.searchBatchSize(), base.maxConnections(), base.connectTimeout(), base.readTimeout(),
                base.defaults(), base.sast(), base.closedStatuses(), base.countryField(), ttl, base.searchConcurrency());
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
    void configuredDefaultsArePreselectedAsFarAsJiraOffersThem() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0", "2.4.0"), null),
                new JiraFieldMeta("components", "Component/s", true, false, ARRAY, values("Security", "Platform"),
                        JsonMapper.builder().build().readTree("[{\"id\":\"o-Platform\",\"name\":\"Platform\"}]")),
                new JiraFieldMeta("customfield_4", "Region", true, false, new JiraFieldMeta.Schema("option", null, null, "select"),
                        options("EMEA", "APAC"), null),
                new JiraFieldMeta("customfield_5", "Team", true, false, new JiraFieldMeta.Schema("option", null, null, "select"),
                        options("Red"), null));
        // by id or by name, any case; values Jira does not offer are dropped
        JiraLookupService service = service(jira, properties("{cve}", 3, new JiraProperties.CreateDefaults(
                List.of("2.4.0", "9.9.9", "2.3.0"), Map.of("COMPONENTS", List.of("security", "Nope"),
                "region", List.of("APAC"), "customfield_5", List.of("Blue")))));

        JiraLookupService.Connection connection = service.connection(TOKEN);
        assertThat(connection.defaultVersions()).containsExactly("2.4.0", "2.3.0");
        // the configured value replaces Jira's default ...
        assertThat(connection.fields().get(0).defaults()).containsExactly("o-Security");
        assertThat(connection.fields().get(1).defaults()).containsExactly("o-APAC");
        // ... and one Jira does not offer leaves what was there (the only option, preselected)
        assertThat(connection.fields().get(2).defaults()).containsExactly("o-Red");

        // nothing configured: nothing selected for the page
        assertThat(service(jira).connection(TOKEN).defaultVersions()).isEmpty();
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
                List.of(JiraVersion.named("2.3.0")), List.of(), null, null, new JiraIssue.Project(project), null));
    }

    private static Finding finding(String cve, String file) {
        return JiraMatchingTest.finding(cve, "CWE-1", file);
    }

    private static JiraLookupService service(JiraClient jira) {
        return service(jira, 50);
    }

    @Test
    void aSastIssueLacksTheLabelsOfApplicationsItsJiraIssueDoesNotCarry() {
        JiraLookupService service = service(new FakeJira(), 50);
        SastIssue sql = SastIssue.group(List.of(
                sastFlaw("creed-platform-2026-10-06-Creed", "CWE-89", "SQL Injection"),
                sastFlaw("creed-pay-gateway-api", "CWE-89", "SQL Injection"))).getFirst();
        JiraLookupService.IssueRef filed = new JiraLookupService.IssueRef("CREED-9", "x", "To Do", null, null, List.of(),
                List.of("veracode", "CREED-PLATFORM-2026-10-06-creed"), "u");

        // as SCA: every application's label (mapped name, else app_name) on create; an existing issue lacks those it
        // does not carry (any case)
        assertThat(service.sastLabels(sql)).endsWith("creed-platform-2026-10-06-Creed", "Pay-Gateway");
        assertThat(service.missingSastLabels(sql, List.of(filed))).containsExactly("Pay-Gateway");
        assertThat(service.missingSastLabels(sql, List.of())).isEmpty();
    }

    private static SastFlaw sastFlaw(String application, String cwe, String category) {
        return new SastFlaw(application, "1", "High", cwe, null, category, "a.war", "com/x/A.java", 1, null, "New",
                "Not Mitigated", false, false, true, null, null);
    }

    private static SastIssue sastIssue(String cwe, String category) {
        SastFlaw flaw = new SastFlaw("app", "1", "High", cwe, null, category, "a.war", "com/x/A.java", 1, null, "New",
                "Not Mitigated", false, false, true, null, null);
        return SastIssue.group(List.of(flaw)).getFirst();
    }

    @Test
    void aSastIssueIsItsCweAndCategoryInTheSummary() {
        JiraLookupService service = service(new FakeJira(), 50);
        SastIssue sql = sastIssue("CWE-89", "SQL Injection");

        // the summary is the template; the SAST project falls back to the SCA one (no sast.project-key)
        assertThat(service.sastSummary(sql)).isEqualTo("IDB VERACODE STSA - CWE-89 - SQL Injection");
        // spaces cannot be in a Jira label; the project name is the configured label already; then the application's
        assertThat(service.sastLabels(sql)).containsExactly("IDB_VERACODE_STSA", "veracode", "app");
        assertThat(JiraLookupService.coversSast("IDB VERACODE STSA - cwe 89 - sql injection", sql)).isTrue();
        // another CWE number, another category: not this issue
        assertThat(JiraLookupService.coversSast("IDB VERACODE STSA - CWE-890 - SQL Injection", sql)).isFalse();
        assertThat(JiraLookupService.coversSast("IDB VERACODE STSA - CWE-89 - Blind SQL", sql)).isFalse();
        assertThat(JiraLookupService.coversSast("IDB VERACODE STSA - CWE-89 - SQL Injection in reports", sql)).isTrue();
    }

    @Test
    void theSastSearchHasNoVersionClause() {
        String jql = Jql.sastLookup("SEC", List.of(sastIssue("CWE-89", "SQL Injection"), sastIssue(null, "Odd: thing")));

        assertThat(jql).isEqualTo("project = \"SEC\" AND (summary ~ \"\\\"CWE-89\\\"\" OR summary ~ \"odd thing\")"
                + " ORDER BY key");
        // the page's Countries narrow it as they do SCA's (asked for, 2026-11-12)
        assertThat(Jql.sastLookup("SEC", "cf[10300]", List.of("Singapore"), List.of(sastIssue("CWE-89", "SQL Injection"))))
                .isEqualTo("project = \"SEC\" AND cf[10300] in (\"Singapore\") AND (summary ~ \"\\\"CWE-89\\\"\") ORDER BY key");
    }

    @Test
    void sastCreateMetaDoesNotNeedAffectsVersion() {
        FakeJira jira = new FakeJira();
        jira.fields = List.of(new JiraFieldMeta("summary", "Summary", true, false, null, null, null));

        JiraLookupService.CreateMeta meta = service(jira, 50).sastCreateMeta(TOKEN);

        assertThat(meta.affectsVersion()).isFalse();
        assertThat(meta.versionRequired()).isFalse();
        assertThat(meta.versions()).isEmpty();
    }

    /** CREED-PAY-GATEWAY-API is mapped (case ignored); every other application keeps its app_name. */
    private static final ApplicationAliases APPS = ApplicationAliases.of(List.of(
            new com.creed.resource.veracode.domain.ApplicationAlias("creed-pay-gateway-api", "Pay-Gateway", "https://git/pay")));

    private static JiraLookupService service(JiraClient jira, int searchBatchSize) {
        return service(jira, properties("{project} - {cve}|{cwe} - {file}", searchBatchSize));
    }

    private static JiraLookupService service(JiraClient jira, JiraProperties props) {
        return new JiraLookupService(jira, props, APPS, JiraMetaCacheConfiguration.metaCache(props.metaCacheTtl()));
    }

    private static JiraProperties properties(String template, int searchBatchSize) {
        return properties(template, searchBatchSize, new JiraProperties.CreateDefaults(null, null));
    }

    private static JiraProperties properties(String template, int searchBatchSize, JiraProperties.CreateDefaults defaults) {
        return new JiraProperties("http://jira", "CREED", "Bug", "creed", template, List.of("veracode"),
                "http://jira/browse/{key}", 1, searchBatchSize, 5,
                Duration.ofSeconds(1), Duration.ofSeconds(1), defaults,
                new JiraProperties.Sast(null, null, "IDB VERACODE STSA", "{project} - {cwe} - {category}",
                        List.of("IDB VERACODE STSA", "veracode"), new JiraProperties.CreateDefaults(null, null)),
                List.of("Closed", "Done"), "Country", Duration.ZERO, 4);
    }

    /** Pages of one item each (pageSize 1), so paging is walked as well. */
    private static final class FakeJira implements JiraClient {

        List<JiraIssueType> types = List.of(new JiraIssueType("1", "Bug", false));
        List<JiraFieldMeta> fields = List.of(
                new JiraFieldMeta("summary", "Summary", true, false, null, null, null),
                new JiraFieldMeta("versions", "Affects Version/s", true, false, ARRAY, values("2.3.0"), null));
        /** What every search answers, whatever the JQL - the service decides the match. */
        List<JiraIssue> found = List.of();
        final List<String> jqls = new ArrayList<>();
        int pages;
        int cloudPages;
        String lastAuthorization;
        /** Reads started, not pages: the fake answers a page per item, the counter counts the first page only. */
        final java.util.concurrent.atomic.AtomicInteger typeReadCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger fieldReadCount = new java.util.concurrent.atomic.AtomicInteger();
        int typeReads;
        int fieldReads;

        @Override
        public JiraCreatedIssue createIssue(String authorization, JiraCreateIssueRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addAttachments(String authorization, String xsrf, String key,
                                   org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JiraIssue issue(String authorization, String key, String fields) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateIssue(String authorization, String key, Map<String, Object> update) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized JiraSearchResult search(String authorization, String jql, String fields, int startAt,
                                                    int maxResults) {
            pages++;
            if (startAt == 0) {
                jqls.add(jql);
            }
            List<JiraIssue> page = found.stream().skip(startAt).limit(maxResults).toList();
            return new JiraSearchResult(startAt, found.size(), null, null, page);
        }

        @Override
        public synchronized JiraSearchResult searchJql(String authorization, String jql, String fields, String nextPageToken,
                                          int maxResults) {
            cloudPages++;
            int from = nextPageToken == null ? 0 : Integer.parseInt(nextPageToken);
            List<JiraIssue> page = found.stream().skip(from).limit(maxResults).toList();
            boolean last = from + page.size() >= found.size();
            return new JiraSearchResult(null, null, last, last ? null : String.valueOf(from + page.size()), page);
        }

        @Override
        public JiraPage<JiraIssueType> createIssueTypes(String authorization, String project, int startAt, int maxResults) {
            if (startAt == 0) {
                typeReads = typeReadCount.incrementAndGet();
            }
            lastAuthorization = authorization;
            return page(types, startAt);
        }

        @Override
        public JiraPage<JiraFieldMeta> createFields(String authorization, String project, String issueTypeId,
                                                    int startAt, int maxResults) {
            if (startAt == 0) {
                fieldReads = fieldReadCount.incrementAndGet();
            }
            return page(fields, startAt);
        }

        private static <T> JiraPage<T> page(List<T> all, int startAt) {
            List<T> values = all.stream().skip(startAt).limit(1).toList();
            return new JiraPage<>(startAt, 1, all.size(), startAt + values.size() >= all.size(), values);
        }
    }
}
