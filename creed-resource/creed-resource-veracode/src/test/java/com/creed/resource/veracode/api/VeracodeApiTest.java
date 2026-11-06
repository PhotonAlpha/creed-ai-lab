package com.creed.resource.veracode.api;

import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.config.RemoteClientsConfiguration;
import com.creed.resource.veracode.mock.MockJiraStore;
import com.creed.resource.veracode.service.JiraToken;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chain over the in-process Jira mock: the bundled sample Veracode XML uploaded, the real
 * {@code @HttpExchange} proxy and the picker / createmeta Jira mock - only the transport is MockMvc instead of a
 * socket, so no port has to be known before the context starts.
 */
@SpringBootTest
@ActiveProfiles("test")
class VeracodeApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    MockJiraStore jira;

    /** The user's token, as the page sends it; the mock names its user after the last four characters. */
    private static final String TOKEN = "pat-0000-abcd";

    /** The create screen's other fields, as the page sends them: an option by label (any case) or by id. */
    private static final String FIELDS = "\"fields\":{\"components\":[\"security\"],\"customfield_10100\":\"Production\"}";

    /** Every request carries the user's token, as the page does; {@link #anonymous} carries none. */
    MockMvc mvc;
    MockMvc anonymous;

    @BeforeEach
    void reset() {
        jira.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header(JiraToken.HEADER, TOKEN)).build();
        anonymous = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockMultipartFile sample() throws Exception {
        return new MockMultipartFile("file", "detailedreport.xml", "application/xml",
                new ClassPathResource(VeracodeController.SAMPLE).getContentAsByteArray());
    }

    /** Uploads the sample; returns the upload id. */
    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.report.id");
    }

    @Test
    void anUploadedReportIsMatchedAgainstJira() throws Exception {
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.fileName").value("detailedreport.xml"))
                .andExpect(jsonPath("$.report.appName").value("creed-platform"))
                .andExpect(jsonPath("$.report.scanVersion").value("2.3.0"))
                .andExpect(jsonPath("$.report.affectedVersion").value("2.3.0"))
                .andExpect(jsonPath("$.report.versionOverridden").value(false))
                .andExpect(jsonPath("$.report.policyName").value("Creed Default"))
                .andExpect(jsonPath("$.report.policyCompliance").value("Did Not Pass"))
                .andExpect(jsonPath("$.projectKey").value("CREED"))
                .andExpect(jsonPath("$.counts.total").value(9))
                .andExpect(jsonPath("$.counts.exists").value(3))
                .andExpect(jsonPath("$.counts.missing").value(6))
                .andExpect(jsonPath("$.counts.versionNotInJira").value(0))
                .andExpect(jsonPath("$.warnings", contains("1 mitigated vulnerability skipped")))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].url")
                        .value("http://localhost/mock/jira/browse/CREED-101"))
                // the library and its version form the component; the title is the summary's first sentence
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].components[0]").value("log4j-core 2.14.1"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].title")
                        .value("Apache Log4j2 JNDI features do not protect against attacker-controlled LDAP and other JNDI related endpoints"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].severity").value("Very High"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].locations[0]")
                        .value("log4j-core 2.14.1 (creed-platform.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)"))
                // A summary that spells the CVE "cve 2020 36518", without the prefix, still matches.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2020-36518@2.3.0')].issues[0].key").value("CREED-103"))
                // Jira's own attributes, for the page's filters; CREED-103 is unassigned.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].priority").value("Highest"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].assignee").value("Alice Wang"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2020-36518@2.3.0')].issues[0].assignee").value(contains(nullValue())))
                // Filed for 2.2.1 only -> still missing for 2.3.0.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-22965@2.3.0')].status").value("MISSING"))
                // Two snakeyaml versions, one finding.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-1471@2.3.0')].components[*]", hasSize(2)))
                // One component, two CVEs: one filed, one not; its mitigated third is not a finding.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-4104@2.3.0')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-23302@2.3.0')].status").value("MISSING"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2019-17571@2.3.0')]", hasSize(0)))
                // No CVE -> keyed and matched by the advisory id.
                .andExpect(jsonPath("$.findings[?(@.id=='title:srcclr sid 29587@2.3.0')].status").value("MISSING"))
                .andExpect(jsonPath("$.findings[0].severity").value("Very High"));
    }

    @Test
    void thePageCanOverrideTheAffectedVersion() throws Exception {
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("affectedVersion", "2.5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.scanVersion").value("2.3.0"))
                .andExpect(jsonPath("$.report.affectedVersion").value("2.5.0"))
                .andExpect(jsonPath("$.report.versionOverridden").value(true))
                // 2.5.0 is not a CREED version: not searched (the JQL would 400), flagged instead.
                .andExpect(jsonPath("$.counts.versionNotInJira").value(9))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.5.0')].jiraVersion").value(contains(nullValue())));
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("affectedVersion", "2.2.1"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-22965@2.2.1')].status").value("EXISTS"));
    }

    @Test
    void createsMissingIssuesOnceAndReportsWhyOthersWereNotCreated() throws Exception {
        String uploadId = upload();
        String request = """
                {"uploadId":"%s","findingIds":["CVE-2022-22965@2.3.0","CVE-2021-44228@2.3.0",
                 "CVE-1999-0001@2.3.0","CVE-2022-22965@2.3.0"],%s}
                """.formatted(uploadId, FIELDS);
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"))
                .andExpect(jsonPath("$.results[1].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[1].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.results[2].outcome").value("NOT_IN_REPORT"));

        MockJiraStore.Issue created = jira.find("CREED-105");
        // summary-template "{project} {component} {cve}", {project} = the report's app_name; filed as the token's user
        assertThat(created.summary()).isEqualTo("creed-platform spring-beans 5.3.17 CVE-2022-22965");
        assertThat(created.reporter()).isEqualTo("user-abcd");
        assertThat(created.versions()).containsExactly("2.3.0");
        // the page's choices, sent as Jira's option ids
        assertThat(created.components()).containsExactly("Security");
        assertThat(created.environment()).isEqualTo("Production");
        assertThat(created.labels()).containsExactly("veracode", "CVE-2022-22965");
        assertThat(created.description()).contains("|Component(s)|spring-beans 5.3.17|",
                "Source: Veracode application creed-platform, scan 2.3.0, policy Creed Default (report detailedreport.xml)");

        // The second click: re-checked, not duplicated.
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-22965@2.3.0\"]," + FIELDS + "}"))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"));
        assertThat(jira.all()).hasSize(5);
    }

    @Test
    void versionNotInJiraIsNotCreated() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("affectedVersion", "2.5.0"))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(body, "$.report.id");
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-22965@2.5.0\"]," + FIELDS + "}"))
                .andExpect(jsonPath("$.results[0].outcome").value("VERSION_NOT_IN_JIRA"));
    }

    @Test
    void autoCreateFilesEveryMissingFindingAndFindsThemAgain() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("autoCreate", "true")
                        .param("fields", "{\"components\":[\"Platform\",\"30001\"],\"customfield_10100\":[\"10201\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.created").value(6))
                .andExpect(jsonPath("$.counts.missing").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(jira.find("CREED-105").components()).containsExactly("Platform", "Security");
        assertThat(jira.find("CREED-105").environment()).isEqualTo("Staging");
        // the advisory without a CVE: its id stands in for {cve}, and is what finds it again below
        assertThat(jira.all()).extracting(MockJiraStore.Issue::summary)
                .contains("creed-platform netty-codec 4.1.68.Final SRCCLR-SID-29587",
                        "creed-platform snakeyaml 1.33, snakeyaml 1.30 CVE-2022-1471");

        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + JsonPath.read(body, "$.report.id") + "\"}"))
                .andExpect(jsonPath("$.counts.exists").value(9))
                .andExpect(jsonPath("$.counts.created").value(0));
    }

    @Test
    void requiredFieldsAreOfferedThroughTheTokenAndCheckedBeforeCreating() throws Exception {
        // the analysis says what the create screen needs from the page, with the options this token may use
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(jsonPath("$.createFields[*].name", contains("Component/s", "Environment Found")))
                .andExpect(jsonPath("$.createFields[0].kind").value("OPTIONS"))
                .andExpect(jsonPath("$.createFields[0].multiple").value(true))
                .andExpect(jsonPath("$.createFields[0].required").value(true))
                .andExpect(jsonPath("$.createFields[1].multiple").value(false))
                .andExpect(jsonPath("$.createFields[1].options[*].label", contains("Production", "Staging")))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(body, "$.report.id");
        String head = "{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-22965@2.3.0\"]";
        String[][] refused = {
                {head + "}", "jira_fields_required", "Jira requires Component/s, Environment Found to create a Bug in CREED"},
                {head + ",\"fields\":{\"components\":[\"Nope\"],\"customfield_10100\":\"Production\"}}", "bad_request",
                        "Component/s has no value 'Nope' (has: Security, Platform, Payments)"},
                {head + ",\"fields\":{\"components\":[\"Security\"],\"customfield_10100\":[\"Production\",\"Staging\"]}}",
                        "bad_request", "Environment Found takes one value"},
                {head + ",\"fields\":{\"priority\":\"High\"}}", "bad_request", "field 'priority' is not one the page fills"},
        };
        for (String[] r : refused) {
            mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content(r[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(r[1]))
                    .andExpect(jsonPath("$.message").value(containsString(r[2])));
        }
        // autoCreate is checked the same way, before anything is created
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"autoCreate\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_fields_required"));
        assertThat(jira.all()).hasSize(4);
    }

    @Test
    void theSampleIsServedWithTheMocks() throws Exception {
        mvc.perform(post("/api/veracode/analysis/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.appName").value("creed-platform"))
                .andExpect(jsonPath("$.counts.total").value(9));
    }

    @Test
    void exportsTheAnalysisAsXlsx() throws Exception {
        String uploadId = upload();
        byte[] xlsx = mvc.perform(get("/api/veracode/analysis/export").param("uploadId", uploadId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VeracodeController.XLSX))
                .andExpect(header().string("Content-Disposition", containsString("veracode-creed-platform-")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet findings = wb.getSheet("Findings");
            assertThat(findings.getLastRowNum()).isEqualTo(9);
            assertThat(findings.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Status");
            List<String> log4shell = new ArrayList<>();
            for (Row row : findings) {
                if ("CVE-2021-44228".equals(row.getCell(1).getStringCellValue())) {
                    row.forEach(c -> log4shell.add(c.toString()));
                }
            }
            assertThat(log4shell).contains("EXISTS", "CREED-101", "In Progress", "2.3.0");
            // Severity is filled by level: the same colour for the same level, a different one per level
            java.util.Map<String, String> fills = new java.util.HashMap<>();
            for (Row row : findings) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                org.apache.poi.xssf.usermodel.XSSFCellStyle style =
                        (org.apache.poi.xssf.usermodel.XSSFCellStyle) row.getCell(4).getCellStyle();
                String rgb = style.getFillForegroundColorColor() == null ? null
                        : style.getFillForegroundColorColor().getARGBHex();
                assertThat(rgb).as(row.getCell(4).getStringCellValue()).isNotNull();
                assertThat(fills.putIfAbsent(row.getCell(4).getStringCellValue(), rgb)).isIn(null, rgb);
            }
            assertThat(fills).hasSizeGreaterThan(1);
            assertThat(new java.util.HashSet<>(fills.values())).hasSameSizeAs(fills.values());
            Sheet source = wb.getSheet("Source");
            assertThat(source.getRow(0).getCell(1).getStringCellValue()).isEqualTo("creed-platform");
            assertThat(source.getRow(6).getCell(1).getStringCellValue()).isEqualTo("CREED");
        }
    }

    @Test
    void everyCallNeedsTheUsersToken() throws Exception {
        String uploadId = upload();
        // no service account to fall back on: reading needs the token as much as creating
        anonymous.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
        anonymous.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
        anonymous.perform(get("/api/veracode/analysis/export").param("uploadId", uploadId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
        String request = "{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-22965@2.3.0\"]," + FIELDS + "}";
        anonymous.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
        // Jira refuses the token: already on the create metadata, before anything is created
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("jira_error"))
                .andExpect(jsonPath("$.message").value(containsString("401")));
        assertThat(jira.all()).hasSize(4);
    }

    @Test
    void connectionTestAsksTheCreateMetadata() throws Exception {
        mvc.perform(post("/api/veracode/jira/connection").header(JiraToken.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.tokenAccepted").value(true))
                .andExpect(jsonPath("$.canCreate").value(true))
                .andExpect(jsonPath("$.issueType").value("Bug"))
                // createmeta answers 2 per page in the test profile: three versions = two pages of fields
                .andExpect(jsonPath("$.versions").value(3))
                // what the page asks for: Component/s and the required custom select, with Jira's options
                .andExpect(jsonPath("$.fields[*].fieldId", contains("components", "customfield_10100")))
                .andExpect(jsonPath("$.fields[0].options[*].label", contains("Security", "Platform", "Payments")))
                .andExpect(jsonPath("$.fields[1].options[*].id", contains("10200", "10201")))
                .andExpect(jsonPath("$.message").value(containsString("to choose on the page: Component/s, Environment Found")))
                .andExpect(jsonPath("$.projectKey").value("CREED"));
        mvc.perform(post("/api/veracode/jira/connection").header(JiraToken.HEADER, "invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.tokenAccepted").value(false))
                .andExpect(jsonPath("$.status").value(401));
        anonymous.perform(post("/api/veracode/jira/connection"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
    }

    @Test
    void connectionTestSaysWhichCheckFailedAndWhichIssueTypesWork() throws Exception {
        // every creatable type, each with whether it can be filed against a version (the mock's Task cannot)
        mvc.perform(post("/api/veracode/jira/connection"))
                .andExpect(jsonPath("$.problem").doesNotExist())
                .andExpect(jsonPath("$.affectsVersion").value(true))
                .andExpect(jsonPath("$.issueTypes[*].name", contains("Bug", "Task", "Story", "Vulnerability")))
                .andExpect(jsonPath("$.issueTypes[*].affectsVersion", contains(true, false, true, true)))
                .andExpect(jsonPath("$.issueTypes[1].versions").value(0));
        // the page's choice travels as a header: a type without Affects Version/s is named as such, not "cannot create"
        mvc.perform(post("/api/veracode/jira/connection").header(JiraToken.ISSUE_TYPE_HEADER, "Task"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.tokenAccepted").value(true))
                .andExpect(jsonPath("$.canCreate").value(true))
                .andExpect(jsonPath("$.affectsVersion").value(false))
                .andExpect(jsonPath("$.problem").value("AFFECTS_VERSION"))
                .andExpect(jsonPath("$.message").value(containsString("issue types that have it: Bug, Story, Vulnerability")));
        mvc.perform(post("/api/veracode/jira/connection").header(JiraToken.ISSUE_TYPE_HEADER, "Epic"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.canCreate").value(false))
                .andExpect(jsonPath("$.problem").value("ISSUE_TYPE"));
        mvc.perform(post("/api/veracode/jira/connection").header(JiraToken.HEADER, "invalid"))
                .andExpect(jsonPath("$.problem").value("TOKEN_REJECTED"));
        // an analysis with such a type says what to do as well
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).header(JiraToken.ISSUE_TYPE_HEADER, "Task"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(containsString("add the field to that screen in Jira")));
    }

    @Test
    void issuesAreCreatedAsTheIssueTypeThePageChose() throws Exception {
        String uploadId = upload();
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.ISSUE_TYPE_HEADER, "Story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-22965@2.3.0\"]," + FIELDS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"));
        assertThat(jira.all()).last().extracting(MockJiraStore.Issue::issueType).isEqualTo("Story");
    }

    @Test
    void jiraCloudTokensComeWithTheEmail() throws Exception {
        // Cloud on the page: e-mail + API token, sent to Jira as Basic (the mock decodes it like Cloud does)
        anonymous.perform(post("/api/veracode/jira/connection")
                        .header(JiraToken.HEADER, "api-token").header(JiraToken.USER_HEADER, "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        anonymous.perform(post("/api/veracode/jira/connection")
                        .header(JiraToken.HEADER, "invalid").header(JiraToken.USER_HEADER, "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value(401));
        // an e-mail without the token is still no token
        anonymous.perform(post("/api/veracode/jira/connection").header(JiraToken.USER_HEADER, "me@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
    }

    @Test
    void jiraCloudLooksUpThroughItsOwnSearch() throws Exception {
        // Cloud retired /rest/api/2/search: the same analysis goes through /rest/api/3/search/jql and its page token
        anonymous.perform(multipart("/api/veracode/analysis/upload").file(sample())
                        .header(JiraToken.HEADER, "api-token").header(JiraToken.USER_HEADER, "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.exists").value(3))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].key").value("CREED-101"));
    }

    @Test
    void uploadErrorsAreNamed() throws Exception {
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF-1.7".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_file"));
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "pom.xml", "application/xml", "<project/>".getBytes())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("report_unreadable"));
        byte[] noVersion = "<detailedreport xmlns=\"https://www.veracode.com/schema/reports/export/1.0\" app_name=\"x\"/>"
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "r.xml", "application/xml", noVersion)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("enter the affected version")));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"no-such-upload\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("upload_expired"));
    }

    @Test
    void errorsAreNamed() throws Exception {
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"uploadId\":\"x\",\"findingIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("findingIds"));
    }

    @Test
    void settingsCarryNoCredentials() throws Exception {
        mvc.perform(get("/api/veracode/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectKey").value("CREED"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.summaryTemplate").value("{project} {component} {cve}"))
                .andExpect(jsonPath("$.requireUserToken").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * Same proxy, MockMvc underneath. MockMvc is built on first use: building it while the context is
     * still creating this bean would ask a half-built context for its filters.
     */
    @TestConfiguration
    static class ClientsOverMockMvc {

        @Bean
        @Primary
        JiraClient mockMvcJiraClient(WebApplicationContext context) {
            AtomicReference<MockMvc> mvc = new AtomicReference<>();
            ClientHttpRequestFactory factory = (uri, method) -> new MockMvcClientHttpRequestFactory(
                    mvc.updateAndGet(m -> m != null ? m : MockMvcBuilders.webAppContextSetup(context).build()))
                    .createRequest(uri, method);
            return RemoteClientsConfiguration.jiraClient(
                    RestClient.builder().baseUrl("http://localhost/mock/jira").requestFactory(factory).build());
        }
    }
}
