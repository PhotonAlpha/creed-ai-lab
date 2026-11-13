package com.creed.resource.veracode.api;

import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.config.RemoteClientsConfiguration;
import com.creed.resource.veracode.mock.MockConfluenceController;
import com.creed.resource.veracode.mock.MockJiraStore;
import com.creed.resource.veracode.service.ConfluenceToken;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    MockConfluenceController confluence;

    /** The user's token, as the page sends it; the mock names its user after the last four characters. */
    private static final String TOKEN = "pat-0000-abcd";

    /** The create screen's other fields, as the page sends them: an option by label (any case) or by id. */
    /** The page's create-screen choices and the one Jira version it files under. */
    private static final String FIELDS = "\"fields\":{\"components\":[\"security\"],\"customfield_10100\":\"Production\"},"
            + "\"jiraVersions\":[\"2.3.0\"]";

    /** Every request carries the user's token, as the page does; {@link #anonymous} carries none. */
    MockMvc mvc;
    MockMvc anonymous;

    @BeforeEach
    void reset() {
        jira.reset();
        confluence.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header(JiraToken.HEADER, TOKEN)).build();
        anonymous = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockMultipartFile sample() throws Exception {
        return new MockMultipartFile("file", "detailedreport.xml", "application/xml",
                new ClassPathResource(VeracodeController.SAMPLE).getContentAsByteArray());
    }

    /** Uploads the sample; returns the upload id. */
    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.uploadId");
    }

    /** A lookup-condition change asks for the SCA half only: SAST is not read again. */
    @Test
    void skipSastLeavesTheSastHalfOut() throws Exception {
        String uploadId = upload();
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"jiraVersions\":[\"2.3.0\"],\"skipSast\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sast").doesNotExist())
                .andExpect(jsonPath("$.counts.total").value(10));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(jsonPath("$.sast.projectKey").exists());
    }

    /** CREED-103 (jackson-databind) is filed for Malaysia, the other seeds for Singapore. */
    @Test
    void theChosenCountriesNarrowTheLookup() throws Exception {
        String jackson = "$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2020-36518@jackson-databind-2.12.6.jar')]";
        String log4shell = "$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')]";
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0")
                        .param("countries", "singapore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries", contains("Singapore", "Malaysia", "China", "Hong Kong")))
                .andExpect(jsonPath("$.selectedCountries", contains("Singapore")))
                .andExpect(jsonPath(jackson + ".status").value(contains("MISSING")))
                .andExpect(jsonPath(log4shell + ".status").value(contains("EXISTS")));
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0")
                        .param("countries", "Singapore", "Malaysia"))
                .andExpect(jsonPath(jackson + ".status").value(contains("EXISTS")));
        // an option the field does not offer
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("countries", "Atlantis"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUploadedReportIsMatchedAgainstJira() throws Exception {
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].fileName").value("detailedreport.xml"))
                .andExpect(jsonPath("$.reports[0].appName").value("creed-platform-2026-10-06-Creed"))
                .andExpect(jsonPath("$.reports[0].scanVersion").value("2.3.0"))
                .andExpect(jsonPath("$.reports[0].affectedVersion").value("2.3.0"))
                .andExpect(jsonPath("$.reports[0].versionOverridden").value(false))
                .andExpect(jsonPath("$.reports[0].policyName").value("Creed Default"))
                .andExpect(jsonPath("$.reports[0].policyCompliance").value("Did Not Pass"))
                // the sample's software_composition_analysis: stated, then counted (one mitigated, skipped)
                .andExpect(jsonPath("$.reports[0].sca.thirdPartyComponents").value(57))
                .andExpect(jsonPath("$.reports[0].sca.violatesPolicy").value(true))
                .andExpect(jsonPath("$.reports[0].sca.componentsViolatedPolicy").value(6))
                .andExpect(jsonPath("$.reports[0].sca.blacklistedComponents").value(0))
                .andExpect(jsonPath("$.reports[0].sca.vulnerableComponents").value(8))
                .andExpect(jsonPath("$.reports[0].sca.vulnerabilities").value(10))
                .andExpect(jsonPath("$.reports[0].sca.mitigated").value(1))
                .andExpect(jsonPath("$.reports[0].sca.maxCvss").value(10.0))
                .andExpect(jsonPath("$.reports[0].sca.bySeverity[0].severity").value("Very High"))
                // SAST statistics: not in the SCA counts (SAST's Jira half is sast.*)
                .andExpect(jsonPath("$.reports[0].sast.rating").value("B"))
                .andExpect(jsonPath("$.reports[0].sast.totalFlaws").value(3))
                .andExpect(jsonPath("$.reports[0].sast.flaws").value(4))
                .andExpect(jsonPath("$.reports[0].sast.open").value(2))
                .andExpect(jsonPath("$.reports[0].sast.mitigated").value(1))
                .andExpect(jsonPath("$.reports[0].sast.fixed").value(1))
                .andExpect(jsonPath("$.reports[0].sast.affectsPolicy").value(2))
                .andExpect(jsonPath("$.sastFlaws.length()").value(4))
                .andExpect(jsonPath("$.sastFlaws[?(@.issueId == '2')].cwe").value(contains("CWE-78")))
                .andExpect(jsonPath("$.sastFlaws[?(@.issueId == '2')].sourceFile").value(contains("com/creed/ops/ScriptRunner.java")))
                .andExpect(jsonPath("$.projectKey").value("CREED"))
                // the same CVE in two snakeyaml jars is two findings now: 10, not 9
                .andExpect(jsonPath("$.counts.total").value(10))
                .andExpect(jsonPath("$.counts.exists").value(3))
                .andExpect(jsonPath("$.counts.missing").value(7))
                .andExpect(jsonPath("$.counts.needsLabel").value(0))
                // the project's versions are the page's list; the chosen one is what was searched and is filed under
                .andExpect(jsonPath("$.jiraVersions", contains("2.2.1", "2.3.0", "2.4.0")))
                .andExpect(jsonPath("$.selectedVersions", contains("2.3.0")))
                .andExpect(jsonPath("$.createVersion").value("2.3.0"))
                .andExpect(jsonPath("$.warnings", contains("1 mitigated vulnerability skipped")))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].url")
                        .value("http://localhost/mock/jira/browse/CREED-101"))
                // the title is cve_id|cwe_id; the jar is the component's file_name; the description the whole cve_summary
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].title").value("CVE-2021-44228|CWE-502"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].application").value("creed-platform-2026-10-06-Creed"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].fileName").value("log4j-core-2.14.1.jar"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].components[0]").value("log4j-core 2.14.1"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].description").value("Apache Log4j2 JNDI features do not "
                        + "protect against attacker-controlled LDAP and other JNDI related endpoints. An attacker who can "
                        + "control log messages can execute arbitrary code."))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].needsLabel").value(false))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].severity").value("Very High"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].locations[0]")
                        .value("log4j-core 2.14.1 (creed-platform.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)"))
                // A summary that spells the CVE "cve 2020 36518", without the prefix, still matches.
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2020-36518@jackson-databind-2.12.6.jar')].issues[0].key").value("CREED-103"))
                // Jira's own attributes, for the page's filters; CREED-103 is unassigned.
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].priority").value("Highest"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].assignee").value("Alice Wang"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2020-36518@jackson-databind-2.12.6.jar')].issues[0].assignee").value(contains(nullValue())))
                // Filed for 2.2.1 only -> still missing for 2.3.0.
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar')].status").value("MISSING"))
                // one CVE in two snakeyaml jars: one finding (and one Jira issue) per jar
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-1471@snakeyaml-1.33.jar')].status").value("MISSING"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-1471@snakeyaml-1.30.jar')].status").value("MISSING"))
                // One component, two CVEs: one filed, one not; its mitigated third is not a finding.
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-4104@log4j-1.2.17.jar')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-23302@log4j-1.2.17.jar')].status").value("MISSING"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2019-17571@log4j-1.2.17.jar')]", hasSize(0)))
                // An advisory id is the cve_id as given.
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|SRCCLR-SID-29587@netty-codec-4.1.68.Final.jar')].status").value("MISSING"))
                .andExpect(jsonPath("$.findings[0].severity").value("Very High"));
    }

    @Test
    void theReportVersionIsShownJiraIsSearchedByThePagesVersions() throws Exception {
        // no Jira version chosen: any version - the 2.2.1 issue now covers CVE-2022-22965 too
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedVersions", hasSize(0)))
                .andExpect(jsonPath("$.createVersion").value(nullValue()))
                .andExpect(jsonPath("$.counts.exists").value(4))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar')].status").value("EXISTS"));
        // the report's version (or the page's override) is display only: 2.5.0 is no CREED version, nothing breaks
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("affectedVersion", "2.5.0")
                        .param("jiraVersions", "2.2.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].scanVersion").value("2.3.0"))
                .andExpect(jsonPath("$.reports[0].affectedVersion").value("2.5.0"))
                .andExpect(jsonPath("$.reports[0].versionOverridden").value(true))
                .andExpect(jsonPath("$.counts.exists").value(1))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar')].status").value("EXISTS"));
        // several versions: any of them; names in any case, answered in Jira's spelling and order
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample())
                        .param("jiraVersions", "2.3.0").param("jiraVersions", "2.2.1"))
                .andExpect(jsonPath("$.selectedVersions", contains("2.2.1", "2.3.0")))
                .andExpect(jsonPath("$.createVersion").value(nullValue()))
                .andExpect(jsonPath("$.counts.exists").value(4));
        // a version the project lacks is refused, not put into the JQL (Jira would 400 the search)
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "9.9.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("project CREED has no version '9.9.9'")));
    }

    @Test
    void createsMissingIssuesOnceAndReportsWhyOthersWereNotCreated() throws Exception {
        String uploadId = upload();
        String request = """
                {"uploadId":"%s","findingIds":["creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar","creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar",
                 "creed-platform-2026-10-06-Creed|CVE-1999-0001@x.jar","creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar"],%s}
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
        // "{project} - {cve}|{cwe} - {file}", {project} = project-name (not the Veracode application); as the token's user
        assertThat(created.summary()).isEqualTo("creed - CVE-2022-22965|CWE-94 - spring-beans-5.3.17.jar");
        assertThat(created.reporter()).isEqualTo("user-abcd");
        assertThat(created.versions()).containsExactly("2.3.0");
        // the page's choices, sent as Jira's option ids
        assertThat(created.components()).containsExactly("Security");
        assertThat(created.environment()).isEqualTo("Production");
        // configured, project-name, the application's label (no application alias: its app_name); no CVE
        assertThat(created.labels()).containsExactly("veracode", "creed", "creed-platform-2026-10-06-Creed");
        // no "\\|" escape anywhere: Jira Cloud renders it as a backslash and an extra cell
        assertThat(created.description()).doesNotContain("\\|");
        assertThat(created.description()).contains(
                "|CVE|[CVE-2022-22965|https://nvd.nist.gov/vuln/detail/CVE-2022-22965]|",
                "|CWE|[CWE-94|https://cwe.mitre.org/data/definitions/94.html]|",
                "|Veracode application|creed-platform-2026-10-06-Creed|", "|Library file|spring-beans-5.3.17.jar|",
                "|Component(s)|spring-beans 5.3.17|",
                "|Description|Spring Framework RCE via data binding on JDK 9+. A Spring MVC or WebFlux application running on JDK 9+ may be vulnerable.|",
                "Source: uploaded report detailedreport.xml (attached to this issue), scan 2.3.0, policy Creed Default");
        // the uploaded file itself is attached to the issue
        assertThat(jira.attachments("CREED-105")).containsExactly("detailedreport.xml");

        // The second click: re-checked, not duplicated.
        mvc.perform(post("/api/veracode/jira/issues").header(JiraToken.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"]," + FIELDS + "}"))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"));
        assertThat(jira.all()).hasSize(5);
    }

    @Test
    void aFindingWhoseIssuesAreAllClosedMayBeFiledAgainOneAtATime() throws Exception {
        String spring = "creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar";
        String log4j = "creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar";
        // under 2.2.1 spring-beans has only CREED-102, Done; log4j-core's CREED-101 (2.3.0) is In Progress
        String uploadId = JsonPath.read(mvc.perform(multipart("/api/veracode/analysis/upload").file(sample())
                        .param("jiraVersions", "2.2.1", "2.3.0"))
                .andExpect(jsonPath("$.findings[?(@.id=='" + spring + "')].recreatable").value(true))
                .andExpect(jsonPath("$.findings[?(@.id=='" + log4j + "')].recreatable").value(false))
                .andReturn().getResponse().getContentAsString(), "$.uploadId");
        String fields = "\"fields\":{\"components\":[\"Security\"],\"customfield_10100\":\"Production\"},\"jiraVersions\":[\"2.2.1\"]";
        // without the flag a closed issue still counts as filed
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"%s\",\"findingIds\":[\"%s\"],%s}".formatted(uploadId, spring, fields)))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"));
        // never in bulk
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"%s\",\"findingIds\":[\"%s\",\"%s\"],%s,\"recreateClosed\":true}"
                                .formatted(uploadId, spring, log4j, fields)))
                .andExpect(status().isBadRequest());
        assertThat(jira.all()).hasSize(4);
        // one, confirmed: filed again beside the Done one
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"%s\",\"findingIds\":[\"%s\"],%s,\"recreateClosed\":true}"
                                .formatted(uploadId, spring, fields)))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"));
        // now an open issue exists: the flag files nothing more, and the page no longer offers it
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"%s\",\"findingIds\":[\"%s\"],%s,\"recreateClosed\":true}"
                                .formatted(uploadId, spring, fields)))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"%s\",\"jiraVersions\":[\"2.2.1\"]}".formatted(uploadId)))
                .andExpect(jsonPath("$.findings[?(@.id=='" + spring + "')].recreatable").value(false));
        assertThat(jira.all()).hasSize(5);
    }

    @Test
    void creatingNeedsExactlyOneJiraVersion() throws Exception {
        String uploadId = upload();
        String head = "{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"],"
                + "\"fields\":{\"components\":[\"Security\"],\"customfield_10100\":\"Production\"}";
        for (String versions : new String[] {"", ",\"jiraVersions\":[\"2.3.0\",\"2.4.0\"]"}) {
            mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content(head + versions + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("jira_version_required"));
        }
        // auto-create likewise, before anything is created
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("autoCreate", "true")
                        .param("fields", "{\"components\":[\"Security\"],\"customfield_10100\":\"Production\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_version_required"));
        assertThat(jira.all()).hasSize(4);
        // filed under the chosen version, whatever the report says
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content(head + ",\"jiraVersions\":[\"2.4.0\"]}"))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].versions", contains("2.4.0")));
        assertThat(jira.all()).last().extracting(MockJiraStore.Issue::versions).isEqualTo(List.of("2.4.0"));
    }

    /**
     * The version to file under is a Jira field of its own (asked for, 2026-11-13): the lookup may name none (any
     * version) or several, and choosing one to create with no longer narrows the lookup.
     */
    @Test
    void theCreateVersionIsAJiraFieldApartFromTheLookupVersions() throws Exception {
        String uploadId = upload();
        String ids = "\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"]";
        // looked up in two versions (several = refused before), filed under the field's
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("{\"uploadId\":\""
                        + uploadId + "\"," + ids + ",\"jiraVersions\":[\"2.3.0\",\"2.4.0\"],\"fields\":{\"components\":[\"Security\"],"
                        + "\"customfield_10100\":\"Production\",\"versions\":[\"2.2.1\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].versions", contains("2.2.1")));
        assertThat(jira.all()).last().extracting(MockJiraStore.Issue::versions).isEqualTo(List.of("2.2.1"));
        // a version the create screen does not offer is a 400 before anything is created
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("{\"uploadId\":\""
                        + uploadId + "\"," + ids.replace("CVE-2022-22965@spring-beans", "CVE-2016-1000027@spring-web")
                        + ",\"fields\":{\"components\":[\"Security\"],\"customfield_10100\":\"Production\",\"versions\":\"9.9.9\"}}"))
                .andExpect(status().isBadRequest());
        // auto-create too, with no lookup version at all
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("autoCreate", "true")
                        .param("fields", "{\"components\":[\"Security\"],\"customfield_10100\":\"Production\",\"versions\":[\"2.4.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedVersions.length()").value(0))
                .andExpect(jsonPath("$.counts.created").isNotEmpty());
        assertThat(jira.all()).last().extracting(MockJiraStore.Issue::versions).isEqualTo(List.of("2.4.0"));
    }

    @Test
    void autoCreateFilesEveryMissingFindingAndFindsThemAgain() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("autoCreate", "true")
                        .param("jiraVersions", "2.3.0")
                        .param("fields", "{\"components\":[\"Platform\",\"30001\"],\"customfield_10100\":[\"10201\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.created").value(7))
                .andExpect(jsonPath("$.counts.missing").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(jira.find("CREED-105").components()).containsExactly("Platform", "Security");
        assertThat(jira.find("CREED-105").environment()).isEqualTo("Staging");
        // the advisory id is the cve_id; one CVE in two jars is two issues - each found again below
        assertThat(jira.all()).extracting(MockJiraStore.Issue::summary)
                .contains("creed - SRCCLR-SID-29587|CWE-400 - netty-codec-4.1.68.Final.jar",
                        "creed - CVE-2022-1471|CWE-502 - snakeyaml-1.33.jar", "creed - CVE-2022-1471|CWE-502 - snakeyaml-1.30.jar");

        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + JsonPath.read(body, "$.uploadId") + "\",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(jsonPath("$.counts.exists").value(10))
                .andExpect(jsonPath("$.counts.created").value(0));
    }

    /** The sample as another Veracode application's report. */
    private static MockMultipartFile otherApplication() throws Exception {
        String xml = new String(new ClassPathResource(VeracodeController.SAMPLE).getContentAsByteArray(), StandardCharsets.UTF_8)
                .replace("app_name=\"creed-platform-2026-10-06-Creed\"", "app_name=\"creed-pay-gateway-api\"");
        return new MockMultipartFile("file", "gateway.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code entries}: name, content, name, content ... */
    private static MockMultipartFile zip(String name, Object... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write(entries[i + 1] instanceof byte[] b ? b : ((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", name, "application/zip", bytes.toByteArray());
    }

    @Test
    void aZipIsReadAsTheXmlFilesInIt() throws Exception {
        // Veracode's download: the report beside its schema; a second report in a folder, macOS' copy skipped
        MockMultipartFile reports = zip("reports.zip",
                "detailedreport.xml", sample().getBytes(),
                "detailedreport.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>",
                "gateway/", "",
                "gateway/detailedreport.xml", otherApplication().getBytes(),
                "__MACOSX/._detailedreport.xml", "junk");
        mvc.perform(multipart("/api/veracode/analysis/upload").file(reports).param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].fileName",
                        contains("reports.zip/detailedreport.xml", "reports.zip/gateway/detailedreport.xml")))
                .andExpect(jsonPath("$.reports[*].appName", contains("creed-platform-2026-10-06-Creed", "creed-pay-gateway-api")))
                .andExpect(jsonPath("$.counts.total").value(20));
        // a zip and a plain XML in one upload
        mvc.perform(multipart("/api/veracode/analysis/upload").file(zip("one.zip", "a/report.XML", sample().getBytes()))
                        .file(otherApplication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].fileName", contains("one.zip/a/report.XML", "gateway.xml")));
        // only the schema: nothing to read
        mvc.perform(multipart("/api/veracode/analysis/upload").file(zip("schema.zip", "detailedreport.xsd", "<x/>")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_file"))
                .andExpect(jsonPath("$.message").value(containsString("schema.zip holds no .xml file")));
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "broken.zip", "application/zip", "PK\u0003\u0004nonsense".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_file"));
    }

    @Test
    void confluenceConnectionNamesTheUser() throws Exception {
        mvc.perform(post("/api/veracode/confluence/connection").header(ConfluenceToken.HEADER, "pat-wiki-9876"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user").value("Mock user-9876"));
        // Cloud: e-mail + API token as Basic
        mvc.perform(post("/api/veracode/confluence/connection").header(ConfluenceToken.HEADER, "api-token")
                        .header(ConfluenceToken.USER_HEADER, "me@example.com"))
                .andExpect(jsonPath("$.user").value("Mock me@example.com"));
        mvc.perform(post("/api/veracode/confluence/connection").header(ConfluenceToken.HEADER, "invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value("TOKEN_REJECTED"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/veracode/confluence/connection"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("confluence_token_required"));
        mvc.perform(get("/api/veracode/settings"))
                .andExpect(jsonPath("$.confluenceBaseUrl").value("http://localhost/mock/confluence"))
                .andExpect(jsonPath("$.confluenceCloud").value(false))
                .andExpect(jsonPath("$.confluenceParentPageId").value("98700"));
    }

    /** The configured parent's children (asked for, 2026-11-13): the page picks one, then lists its attachments. */
    @Test
    void confluenceListsTheParentPagesChildren() throws Exception {
        mvc.perform(get("/api/veracode/confluence/pages/98700/children").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Veracode"))
                .andExpect(jsonPath("$.children[*].id", contains("98765", "98766")))
                .andExpect(jsonPath("$.children[0].title").value("Veracode reports - creed"))
                .andExpect(jsonPath("$.children[0].url").value("http://localhost/mock/confluence/pages/viewpage.action?pageId=98765"))
                .andExpect(jsonPath("$.children[0].modified").value("2026-11-09T08:30:00.000Z"));
        mvc.perform(get("/api/veracode/confluence/pages/1/children").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("confluence_page_not_found"));
        mvc.perform(get("/api/veracode/confluence/pages/98700/children"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("confluence_token_required"));
    }

    @Test
    void confluenceListsAPagesAttachmentsAcrossPages() throws Exception {
        // page-size 2 in the test profile: three attachments are two listing pages
        mvc.perform(get("/api/veracode/confluence/pages/98765/attachments").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Veracode reports - creed"))
                .andExpect(jsonPath("$.spaceKey").value("CREED"))
                .andExpect(jsonPath("$.url").value("http://localhost/mock/confluence/pages/viewpage.action?pageId=98765"))
                .andExpect(jsonPath("$.attachments[*].title",
                        contains("veracode-detailedreport.xml", "veracode-reports_09_Nov_2026.zip", "scan-summary_09_Nov_2026.pdf")))
                .andExpect(jsonPath("$.attachments[*].supported", contains(true, true, false)))
                .andExpect(jsonPath("$.attachments[0].mediaType").value("application/xml"))
                .andExpect(jsonPath("$.attachments[0].author").value("ci-veracode"));
        mvc.perform(get("/api/veracode/confluence/pages/98766/attachments").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(jsonPath("$.attachments", hasSize(0)));
        mvc.perform(get("/api/veracode/confluence/pages/1/attachments").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("confluence_page_not_found"));
        mvc.perform(get("/api/veracode/confluence/pages/abc/attachments").header(ConfluenceToken.HEADER, TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/veracode/confluence/pages/98765/attachments").header(ConfluenceToken.HEADER, "invalid"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("confluence_error"));
    }

    /** {@code sample/veracode-detailedreport-2.xml}: a second project, overlapping the first on purpose (see its header). */
    @Test
    void twoProjectsShareTheirScaAndSastIssues() throws Exception {
        MockMultipartFile gateway = new MockMultipartFile("file", "veracode-detailedreport-2.xml", "application/xml",
                new ClassPathResource("sample/veracode-detailedreport-2.xml").getContentAsByteArray());
        String gw = "creed-gateway-2026-10-08-Gateway";
        // the platform alone files the SQL injection SAST issue: with its own application label
        String platform = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sqlId = JsonPath.<List<String>>read(platform, "$.sast.issues[?(@.cwe=='CWE-89')].id").getFirst();
        String sastCreate = "{\"uploadId\":\"%s\",\"issueIds\":[\"" + sqlId + "\"],\"jiraVersions\":[\"2.3.0\"],"
                + "\"fields\":{\"components\":[\"security\"],\"customfield_10100\":\"Production\"}}";
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON)
                        .content(sastCreate.formatted(JsonPath.<String>read(platform, "$.uploadId"))))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].labels", contains("veracode-sast", "creed-sast", "creed-platform-2026-10-06-Creed")));

        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).file(gateway).param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].appName", contains("creed-platform-2026-10-06-Creed", gw)))
                // 10 platform findings + 5 gateway ones (CVE-2020-8908 is mitigated)
                .andExpect(jsonPath("$.counts.total").value(15))
                // the platform's issue, found for the gateway too - without its label yet
                .andExpect(jsonPath("$.findings[?(@.id=='" + gw + "|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.findings[?(@.id=='" + gw + "|CVE-2021-44228@log4j-core-2.14.1.jar')].needsLabel").value(true))
                .andExpect(jsonPath("$.findings[?(@.id=='" + gw + "|CVE-2016-1000027@spring-web-5.3.17.jar')].issues.length()").value(0))
                // SAST: open flaws only; the SQL injection in both projects is one issue
                .andExpect(jsonPath("$.sast.issues[*].cwe", contains("CWE-78", "CWE-89", "CWE-80")))
                .andExpect(jsonPath("$.sast.issues[1].flaws.length()").value(2))
                .andExpect(jsonPath("$.sast.issues[1].applications", contains("creed-platform-2026-10-06-Creed", gw)))
                // filed by the platform: the gateway's label is missing, as for SCA
                .andExpect(jsonPath("$.sast.issues[1].needsLabel").value(true))
                // the gateway is mapped (application-test.yml): its label is its name there, linked on the page
                .andExpect(jsonPath("$.sast.issues[1].missingLabels", contains("Creed-Gateway")))
                .andExpect(jsonPath("$.applications[1].name").value("Creed-Gateway"))
                .andExpect(jsonPath("$.applications[1].url").value("https://git.example.com/projects/GATEWAY/browse"))
                .andExpect(jsonPath("$.applications[0].name").value("creed-platform-2026-10-06-Creed"))
                .andExpect(jsonPath("$.applications[0].url").doesNotExist())
                // per application, as the page shows the issue: "<name>: <sast project-name> - <category>" ...
                .andExpect(jsonPath("$.sast.issues[1].byApplication[*].name", contains("Creed-Gateway", "creed-platform-2026-10-06-Creed")))
                .andExpect(jsonPath("$.sast.issues[1].byApplication[0].heading").value("creed-sast - Improper Neutralization of Special Elements used in an SQL Command"))
                .andExpect(jsonPath("$.sast.issues[1].byApplication[0].cwe").value(org.hamcrest.Matchers.startsWith("CWE 89 Improper Neutralization")))
                .andExpect(jsonPath("$.sast.issues[1].byApplication[0].flawIds").value(org.hamcrest.Matchers.matchesPattern("CWE 89 \\(Flaw IDs: \\d+\\)")))
                .andExpect(jsonPath("$.sast.counts.needsLabel").value(1))
                .andExpect(jsonPath("$.sastFlaws.length()").value(8))
                .andReturn().getResponse().getContentAsString();

        String uploadId = JsonPath.read(body, "$.uploadId");
        String sqlKey = JsonPath.read(body, "$.sast.issues[1].issues[0].key");
        jira.describe(sqlKey, "written by hand");
        // "add label": the existing issue gets the gateway's label, no second issue
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON).content(sastCreate.formatted(uploadId)))
                .andExpect(jsonPath("$.results[0].outcome").value("LABELED"))
                .andExpect(jsonPath("$.results[0].issues[0].labels", contains("veracode-sast", "creed-sast",
                        "creed-platform-2026-10-06-Creed", "Creed-Gateway")));
        // ... and its description overwritten, as SCA's (2026-11-13): the page's "Jira summary", a block per application
        assertThat(jira.find(sqlKey).description()).doesNotContain("written by hand");
        assertThat(jira.find(sqlKey).description())
                .startsWith("[Creed-Gateway|https://git.example.com/projects/GATEWAY/browse]: creed-sast - Improper Neutralization")
                .contains("\n\ncreed-platform-2026-10-06-Creed: creed-sast - Improper Neutralization")
                .contains("h3. Veracode SAST finding");
        assertThat(jira.find(sqlKey).description().split("h3. Veracode SAST finding", -1)).hasSize(2);
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON).content(sastCreate.formatted(uploadId)))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"));

        // on the Confluence page, one row per Jira issue: the repositories sharing it, one per line
        mvc.perform(post("/api/veracode/confluence/export").header(ConfluenceToken.HEADER, "pat-wiki-9876")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"uploadId\":\"" + JsonPath.read(body, "$.uploadId")
                                + "\",\"parentPageId\":\"" + MockConfluenceController.PAGE + "\",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(status().isOk());
        String storage = confluence.createdPages().getLast().storage();
        assertThat(storage.split(">CREED-101</a>", -1)).hasSize(2);
        // repositories by their mapped names, linked when mapped, alphabetical
        assertThat(storage).contains("<td><a href=\"https://git.example.com/projects/GATEWAY/browse\">Creed-Gateway</a>"
                        + "<br />creed-platform-2026-10-06-Creed</td>")
                // not in Jira: a row per finding, the summary row still counts findings
                .contains("<td>SCA (15)</td>");
    }

    @Test
    void confluenceAttachmentsAreDownloadedAndAnalysed() throws Exception {
        // the xml directly, the zip through the media redirect (which refuses a token): three reports
        String downloaded = mvc.perform(post("/api/veracode/analysis/confluence").header(ConfluenceToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageId\":\"98765\",\"attachmentIds\":[\"att1001\",\"att1002\"],\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].fileName", contains("veracode-detailedreport.xml",
                        "veracode-reports_09_Nov_2026.zip/reports/veracode-detailedreport-2.xml",
                        "veracode-reports_09_Nov_2026.zip/reports/veracode-detailedreport.xml")))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].key")
                        .value("CREED-101"))
                .andReturn().getResponse().getContentAsString();
        // filed from a downloaded report: the description links the Confluence attachment, Jira gets no file
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("""
                        {"uploadId":"%s","findingIds":["creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar"],%s}
                        """.formatted(JsonPath.<String>read(downloaded, "$.uploadId"), FIELDS)))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"));
        MockJiraStore.Issue filed = jira.find("CREED-105");
        assertThat(filed.description()).contains("Source: report Confluence attachment [veracode-detailedreport.xml|"
                + "http://localhost/mock/confluence/download/attachments/98765/veracode-detailedreport.xml")
                .contains(" on page [Veracode reports - creed|http://localhost/mock/confluence/pages/viewpage.action?pageId=98765]");
        assertThat(jira.attachments(filed.key())).isEmpty();
        // the PDF is listed, not analysable; an id from another page is refused, not fetched
        mvc.perform(post("/api/veracode/analysis/confluence").header(ConfluenceToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"98765\",\"attachmentIds\":[\"att1003\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_file"));
        mvc.perform(post("/api/veracode/analysis/confluence").header(ConfluenceToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"98766\",\"attachmentIds\":[\"att1001\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not on page 98766")));
        // no Jira token: refused before anything is downloaded
        anonymous.perform(post("/api/veracode/analysis/confluence").header(ConfluenceToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"98765\",\"attachmentIds\":[\"att1001\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_token_required"));
    }

    @Test
    void twoApplicationsShareAnIssueAndEachAddsItsLabel() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).file(otherApplication())
                        .param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].appName", contains("creed-platform-2026-10-06-Creed", "creed-pay-gateway-api")))
                // a finding per application: the page groups and filters by it
                .andExpect(jsonPath("$.counts.total").value(20))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-pay-gateway-api|CVE-2021-44228@log4j-core-2.14.1.jar')].application")
                        .value("creed-pay-gateway-api"))
                // the same CVE in the same jar is the same issue - which lacks this application's label
                .andExpect(jsonPath("$.findings[?(@.id=='creed-pay-gateway-api|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].key")
                        .value("CREED-101"))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-pay-gateway-api|CVE-2021-44228@log4j-core-2.14.1.jar')].needsLabel")
                        .value(true))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].needsLabel")
                        .value(false))
                .andExpect(jsonPath("$.counts.needsLabel").value(3))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(body, "$.uploadId");

        // labelling rewrites the description (asked for, 2026-11-13): a table per application - an application filed
        // from an earlier analysis keeps its own, other text goes
        jira.describe("CREED-101", "written by hand\n\nh3. Veracode SCA finding\n||Field||Value||\n"
                + "|Veracode application|creed-legacy|\n|Severity|Very High|\n");
        // both applications miss CVE-2022-22965: one issue is created, the second application only labels it
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("""
                        {"uploadId":"%s","findingIds":["creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar",
                         "creed-pay-gateway-api|CVE-2022-22965@spring-beans-5.3.17.jar",
                         "creed-pay-gateway-api|CVE-2021-44228@log4j-core-2.14.1.jar"],%s}
                        """.formatted(uploadId, FIELDS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].outcome", contains("CREATED", "LABELED", "LABELED")))
                .andExpect(jsonPath("$.results[1].issues[0].key").value("CREED-105"))
                .andExpect(jsonPath("$.results[2].issues[0].key").value("CREED-101"));
        assertThat(jira.all()).hasSize(5);
        // no application alias: each application is labelled by its app_name
        assertThat(jira.find("CREED-105").labels()).containsExactly("veracode", "creed", "creed-platform-2026-10-06-Creed",
                "creed-pay-gateway-api");
        assertThat(jira.find("CREED-101").labels()).contains("creed-platform-2026-10-06-Creed", "creed-pay-gateway-api");
        for (String key : List.of("CREED-105", "CREED-101")) {
            String description = jira.find(key).description();
            assertThat(description.split("h3. Veracode SCA finding", -1)).as(key).hasSize(key.equals("CREED-101") ? 4 : 3);
            assertThat(description).as(key).startsWith("h3. Veracode SCA finding")
                    .contains("|Veracode application|creed-platform-2026-10-06-Creed|",
                            "|Veracode application|creed-pay-gateway-api|")
                    .doesNotContain("written by hand");
        }
        assertThat(jira.find("CREED-101").description()).startsWith("h3. Veracode SCA finding\n||Field||Value||\n"
                + "|Veracode application|creed-legacy|\n|Severity|Very High|\n");

        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(jsonPath("$.counts.needsLabel").value(2))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-pay-gateway-api|CVE-2022-22965@spring-beans-5.3.17.jar')].status")
                        .value("EXISTS"));
    }

    /**
     * Application aliases live in the database, edited as one table (asked for, 2026-11-13): the old
     * {@code veracode.applications} of application-test.yml was imported at start, and a save renames at once.
     */
    @Test
    void applicationAliasesAreEditedAsOneTable() throws Exception {
        String imported = mvc.perform(get("/api/veracode/application-aliases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].appName", contains("creed-gateway-2026-10-08-Gateway")))
                .andExpect(jsonPath("$[0].alias").value("Creed-Gateway"))
                .andExpect(jsonPath("$[0].repositoryUrl").value("https://git.example.com/projects/GATEWAY/browse"))
                .andReturn().getResponse().getContentAsString();
        String[][] refused = {
                {"[{\"appName\":\" \",\"alias\":\"x\"}]", "row 1: the Veracode application name is required"},
                {"[{\"appName\":\"a\",\"alias\":\"x\"},{\"appName\":\"A \",\"alias\":\"y\"}]", "row 2: 'A' is listed twice"},
                {"[{\"appName\":\"a\"}]", "give 'a' an alias or a repository URL"},
                {"[{\"appName\":\"a\",\"repositoryUrl\":\"javascript:alert(1)\"}]", "must be http(s)"},
        };
        for (String[] r : refused) {
            mvc.perform(put("/api/veracode/application-aliases").contentType(MediaType.APPLICATION_JSON).content(r[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"))
                    .andExpect(jsonPath("$.message").value(containsString(r[1])));
        }
        try {
            mvc.perform(put("/api/veracode/application-aliases").contentType(MediaType.APPLICATION_JSON).content("""
                            [{"appName":" CREED-PLATFORM-2026-10-06-Creed ","alias":" Creed-Platform ","repositoryUrl":""}]
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].appName").value("CREED-PLATFORM-2026-10-06-Creed"))
                    .andExpect(jsonPath("$[0].alias").value("Creed-Platform"))
                    .andExpect(jsonPath("$[0].repositoryUrl").value(nullValue()));
            // the next analysis shows - and would label - the application by it, its app_name matched in any case
            mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0"))
                    .andExpect(jsonPath("$.applications[0].name").value("Creed-Platform"))
                    .andExpect(jsonPath("$.applications[0].url").value(nullValue()));
        } finally {
            mvc.perform(put("/api/veracode/application-aliases").contentType(MediaType.APPLICATION_JSON).content(imported))
                    .andExpect(status().isOk());
        }
    }

    /** Two applications missing their labels on one issue: one read and one update for both, not a pair each. */
    @Test
    void applicationsSharingAnIssueAreLabelledInOneUpdate() throws Exception {
        MockMultipartFile billing = new MockMultipartFile("file", "billing.xml", "application/xml",
                new String(otherApplication().getBytes(), StandardCharsets.UTF_8)
                        .replace("creed-pay-gateway-api", "creed-billing").getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(otherApplication()).file(billing)
                        .param("jiraVersions", "2.3.0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("""
                        {"uploadId":"%s","findingIds":["creed-pay-gateway-api|CVE-2021-44228@log4j-core-2.14.1.jar",
                         "creed-billing|CVE-2021-44228@log4j-core-2.14.1.jar"],%s}
                        """.formatted(JsonPath.<String>read(body, "$.uploadId"), FIELDS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].outcome", contains("LABELED", "LABELED")))
                .andExpect(jsonPath("$.results[0].message").value("added label creed-pay-gateway-api to CREED-101"))
                .andExpect(jsonPath("$.results[1].message").value("added label creed-billing to CREED-101"))
                .andExpect(jsonPath("$.results[1].issues[0].labels", hasItems("creed-pay-gateway-api", "creed-billing")));
        assertThat(jira.find("CREED-101").labels()).contains("creed-pay-gateway-api", "creed-billing");
        assertThat(jira.descriptionWrites("CREED-101")).isEqualTo(1);
        assertThat(jira.find("CREED-101").description())
                .contains("|Veracode application|creed-pay-gateway-api|", "|Veracode application|creed-billing|");
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
        String uploadId = JsonPath.read(body, "$.uploadId");
        String head = "{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"]";
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
                .andExpect(jsonPath("$.reports[0].appName").value("creed-platform-2026-10-06-Creed"))
                .andExpect(jsonPath("$.counts.total").value(10));
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
            assertThat(findings.getLastRowNum()).isEqualTo(10);
            assertThat(findings.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Status");
            List<String> log4shell = new ArrayList<>();
            for (Row row : findings) {
                if ("CVE-2021-44228|CWE-502".equals(row.getCell(2).getStringCellValue())) {
                    row.forEach(c -> log4shell.add(c.toString()));
                }
            }
            assertThat(log4shell).contains("EXISTS", "creed-platform-2026-10-06-Creed", "log4j-core-2.14.1.jar", "CREED-101", "In Progress",
                    "2.3.0");
            // Severity is filled by level: the same colour for the same level, a different one per level
            java.util.Map<String, String> fills = new java.util.HashMap<>();
            for (Row row : findings) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                org.apache.poi.xssf.usermodel.XSSFCellStyle style =
                        (org.apache.poi.xssf.usermodel.XSSFCellStyle) row.getCell(5).getCellStyle();
                String rgb = style.getFillForegroundColorColor() == null ? null
                        : style.getFillForegroundColorColor().getARGBHex();
                assertThat(rgb).as(row.getCell(5).getStringCellValue()).isNotNull();
                assertThat(fills.putIfAbsent(row.getCell(5).getStringCellValue(), rgb)).isIn(null, rgb);
            }
            assertThat(fills).hasSizeGreaterThan(1);
            assertThat(new java.util.HashSet<>(fills.values())).hasSameSizeAs(fills.values());
            Sheet sca = wb.getSheet("SCA summary");
            assertThat(wb.getSheetIndex(sca)).isEqualTo(1);
            assertThat(sca.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Third-party components");
            assertThat(sca.getRow(0).getCell(8).getStringCellValue()).isEqualTo("Very High");
            Row report = sca.getRow(1);
            assertThat(report.getCell(0).getStringCellValue()).isEqualTo("detailedreport.xml");
            assertThat(report.getCell(2).getNumericCellValue()).isEqualTo(57);
            assertThat(report.getCell(3).getNumericCellValue()).isEqualTo(8);
            assertThat(report.getCell(4).getStringCellValue()).isEqualTo("Yes");
            assertThat(report.getCell(7).getNumericCellValue()).isEqualTo(10);
            double levels = 0;
            for (int c = 8; c < 14; c++) {
                levels += report.getCell(c).getNumericCellValue();
            }
            // every counted vulnerability has one of the six levels in the sample
            assertThat(levels).isEqualTo(10);
            // one report: no Total row
            assertThat(sca.getLastRowNum()).isEqualTo(1);
            Sheet sast = wb.getSheet("SAST flaws");
            assertThat(wb.getSheetIndex(sast)).isEqualTo(2);
            assertThat(sast.getLastRowNum()).isEqualTo(4);
            // the most severe first; the CWE links to MITRE
            Row first = sast.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Open");
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("Very High");
            assertThat(first.getCell(4).getHyperlink().getAddress())
                    .isEqualTo("https://cwe.mitre.org/data/definitions/78.html");
            assertThat(first.getCell(8).getStringCellValue()).isEqualTo("com/creed/ops/ScriptRunner.java");
            assertThat(first.getCell(9).getNumericCellValue()).isEqualTo(42);
            List<String> statuses = new ArrayList<>();
            sast.forEach(row -> statuses.add(row.getCell(0).getStringCellValue()));
            assertThat(statuses).containsExactlyInAnyOrder("Status", "Open", "Open", "Mitigated", "Fixed");
            Row sastSummary = wb.getSheet("SAST summary").getRow(1);
            assertThat(sastSummary.getCell(2).getStringCellValue()).isEqualTo("B");
            assertThat(sastSummary.getCell(7).getNumericCellValue()).isEqualTo(2);
            Sheet source = wb.getSheet("Source");
            // a block per uploaded report, then the analysis
            assertThat(source.getRow(0).getCell(1).getStringCellValue()).isEqualTo("detailedreport.xml");
            assertThat(source.getRow(1).getCell(1).getStringCellValue()).isEqualTo("creed-platform-2026-10-06-Creed");
            assertThat(source.getRow(6).getCell(1).getStringCellValue()).isEqualTo("CREED");
        }
    }

    @Test
    void sastIssuesAreFoundAndFiledByCweAndCategory() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()))
                .andExpect(status().isOk())
                // two open flaws, two issues; the mitigated and the fixed one make none
                .andExpect(jsonPath("$.sast.projectKey").value("CREED"))
                .andExpect(jsonPath("$.sast.issueType").value("Vulnerability"))
                .andExpect(jsonPath("$.sast.versionRequired").value(true))
                // the configured SAST default the project has (9.9.9 is not in the mock)
                .andExpect(jsonPath("$.sast.defaultVersions[0]").value("2.4.0"))
                .andExpect(jsonPath("$.sast.defaultVersions.length()").value(1))
                .andExpect(jsonPath("$.sast.error").doesNotExist())
                .andExpect(jsonPath("$.sast.counts.total").value(2))
                .andExpect(jsonPath("$.sast.counts.missing").value(2))
                .andExpect(jsonPath("$.sast.issues[0].cwe").value("CWE-78"))
                .andExpect(jsonPath("$.sast.issues[0].summary")
                        .value("creed-sast - CWE-78 - Improper Neutralization of Special Elements used in an OS Command"))
                .andExpect(jsonPath("$.sast.issues[0].flaws.length()").value(1))
                // the SCA half is untouched
                .andExpect(jsonPath("$.counts.total").value(10))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(body, "$.uploadId");
        String cmd = JsonPath.read(body, "$.sast.issues[0].id");
        String fields = "\"fields\":{\"components\":[\"security\"],\"customfield_10100\":\"Production\"}";
        String request = "{\"uploadId\":\"" + uploadId + "\",\"issueIds\":[\"" + cmd + "\",\"SAST|CWE-117|x\"]," + fields;

        // the issue type requires Affects Version/s: one version for SAST
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON).content(request + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("jira_version_required"));
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON)
                        .content(request + ",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                // a mitigated flaw (CWE-117) is never filed
                .andExpect(jsonPath("$.results[1].outcome").value("NOT_IN_REPORT"));
        MockJiraStore.Issue created = jira.all().stream().filter(i -> i.summary().startsWith("creed-sast")).findFirst()
                .orElseThrow();
        assertThat(created.summary())
                .isEqualTo("creed-sast - CWE-78 - Improper Neutralization of Special Elements used in an OS Command");
        assertThat(created.issueType()).isEqualTo("Vulnerability");
        assertThat(created.labels()).containsExactly("veracode-sast", "creed-sast", "creed-platform-2026-10-06-Creed");
        assertThat(created.versions()).containsExactly("2.3.0");
        assertThat(created.description()).contains("ScriptRunner.java", "CWE-78",
                "Source (creed-platform-2026-10-06-Creed): uploaded report detailedreport.xml (attached to this issue)");
        // headed by the page's "Jira summary" column: name: project - category / CWE line / flaw ids
        assertThat(created.description()).startsWith("creed-platform-2026-10-06-Creed: creed-sast - "
                + "Improper Neutralization of Special Elements used in an OS Command\nCWE 78");
        assertThat(created.description()).contains("\nCWE 78 (Flaw IDs: 2)\n\nh3. Veracode SAST finding");
        assertThat(jira.attachments(created.key())).containsExactly("detailedreport.xml");

        // found again by its summary alone - no version clause
        mvc.perform(post("/api/veracode/jira/sast-issues").contentType(MediaType.APPLICATION_JSON)
                        .content(request + ",\"jiraVersions\":[\"2.3.0\"]}"))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value(created.key()));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"jiraVersions\":[\"2.2.1\"]}"))
                .andExpect(jsonPath("$.sast.counts.exists").value(1))
                .andExpect(jsonPath("$.sast.issues[0].status").value("EXISTS"));
        byte[] xlsx = mvc.perform(get("/api/veracode/analysis/export").param("uploadId", uploadId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row first = wb.getSheet("SAST flaws").getRow(1);
            assertThat(first.getCell(4).getStringCellValue()).isEqualTo("CWE-78");
            assertThat(first.getCell(14).getStringCellValue()).isEqualTo(created.key());
        }
    }

    @Test
    void exportsTheAnalysisAsAConfluenceChildPage() throws Exception {
        String uploadId = upload();
        String request = "{\"uploadId\":\"" + uploadId + "\",\"parentPageId\":\"" + MockConfluenceController.PAGE
                + "\",\"jiraVersions\":[\"2.3.0\"]}";

        String body = mvc.perform(post("/api/veracode/confluence/export").header(ConfluenceToken.HEADER, "pat-wiki-9876")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceKey").value("CREED"))
                .andExpect(jsonPath("$.parentId").value(MockConfluenceController.PAGE))
                .andReturn().getResponse().getContentAsString();
        // {project} - {version} - Reports (SAST,SCA) - {yyyy-MM-dd HH-mm-ss}
        assertThat(JsonPath.<String>read(body, "$.title")).matches(
                "creed-platform-2026-10-06-Creed - 2\\.3\\.0 - Reports \\(SAST,SCA\\) - \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2}");
        assertThat(JsonPath.<String>read(body, "$.url")).endsWith("pageId=" + JsonPath.read(body, "$.id"));

        MockConfluenceController.CreatedPage page = confluence.createdPages().getFirst();
        String storage = page.storage();
        // storage format is XHTML: it must parse as XML
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader("<root>" + storage + "</root>")));
        assertThat(storage).contains("<h2>SCA Issues Summary</h2>", "<h2>SCA Issues</h2>", "<h2>SAST Issues</h2>",
                        "<th>Very High (", "<td>SCA (10)</td>", "<td>SAST (2)</td>", "<th>Total issue Count</th>")
                // an existing issue links to Jira with its summary; a missing one is named by the summary it would get
                .contains(">CREED-101</a>", "Not in Jira", "creed - CVE-2022-42889|CWE-94 - commons-text-1.9.jar")
                // SAST Details: per application, three lines (the Jira summary is not repeated there)
                .contains("<td>creed-platform-2026-10-06-Creed: creed-sast - Improper Neutralization of Special Elements"
                        + " used in an OS Command<br />CWE 78 Improper Neutralization of Special Elements used in an OS Command"
                        + " (&#39;OS Command Injection&#39;)<br />CWE 78 (Flaw IDs: 2)</td>")
                // the template's sample text and Thymeleaf's own markup never reach the page
                .doesNotContain("DT-15", "th:", "<!--")
                // the report it was made from, attached and linked by name
                .contains("Veracode report(s): <ac:link><ri:attachment ri:filename=\"detailedreport.xml\" />"
                        + "<ac:link-body>detailedreport.xml</ac:link-body></ac:link>. Jira CREED");
        assertThat(JsonPath.<List<String>>read(body, "$.attachments")).containsExactly("detailedreport.xml");
        assertThat(page.attachments().get("detailedreport.xml"))
                .isEqualTo(sample().getBytes());

        // the page's own project and version win
        mvc.perform(post("/api/veracode/confluence/export").header(ConfluenceToken.HEADER, "pat-wiki-9876")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"jiraVersions\"", "\"project\":\"Creed Platform\",\"version\":\"R26.1\",\"jiraVersions\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", org.hamcrest.Matchers.startsWith("Creed Platform - R26.1 - Reports (SAST,SCA) - ")));
        // a parent the user cannot see / that does not exist
        mvc.perform(post("/api/veracode/confluence/export").header(ConfluenceToken.HEADER, "pat-wiki-9876")
                        .contentType(MediaType.APPLICATION_JSON).content(request.replace(MockConfluenceController.PAGE, "424242")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("confluence_page_not_found"));
        mvc.perform(post("/api/veracode/confluence/export").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("confluence_token_required"));
        assertThat(confluence.createdPages()).hasSize(2);
    }

    @Test
    void theScaSummarySheetTotalsSeveralReports() throws Exception {
        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(sample()).file(otherApplication()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        byte[] xlsx = mvc.perform(get("/api/veracode/analysis/export")
                        .param("uploadId", JsonPath.<String>read(body, "$.uploadId")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sca = wb.getSheet("SCA summary");
            assertThat(sca.getRow(2).getCell(1).getStringCellValue()).isEqualTo("creed-pay-gateway-api");
            Row total = sca.getRow(3);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(total.getCell(2).getNumericCellValue()).isEqualTo(114);
            assertThat(total.getCell(7).getNumericCellValue()).isEqualTo(20);
            // yes/no has no sum; Max CVSS is the highest, not a sum
            assertThat(total.getCell(4)).isNull();
            assertThat(total.getCell(15).getNumericCellValue()).isEqualTo(10.0);
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
        String request = "{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"]," + FIELDS + "}";
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
                // veracode.jira.defaults of the test profile, as far as the mock has them
                .andExpect(jsonPath("$.defaultVersions", contains("2.3.0")))
                .andExpect(jsonPath("$.fields[1].defaults", contains("10201")))
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
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"creed-platform-2026-10-06-Creed|CVE-2022-22965@spring-beans-5.3.17.jar\"]," + FIELDS + "}"))
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
        anonymous.perform(multipart("/api/veracode/analysis/upload").file(sample()).param("jiraVersions", "2.3.0")
                        .header(JiraToken.HEADER, "api-token").header(JiraToken.USER_HEADER, "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.exists").value(3))
                .andExpect(jsonPath("$.findings[?(@.id=='creed-platform-2026-10-06-Creed|CVE-2021-44228@log4j-core-2.14.1.jar')].issues[0].key").value("CREED-101"));
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
        // the version is display only now: a report without one is analysed all the same
        byte[] noVersion = "<detailedreport xmlns=\"https://www.veracode.com/schema/reports/export/1.0\" app_name=\"x\"/>"
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "r.xml", "application/xml", noVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].affectedVersion").value(nullValue()));
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
                .andExpect(jsonPath("$.summaryTemplate").value("{project} - {cve}|{cwe} - {file}"))
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
            return RemoteClientsConfiguration.jiraClient(
                    RestClient.builder().baseUrl("http://localhost/mock/jira").requestFactory(overMockMvc(context)).build());
        }

        @Bean
        @Primary
        ConfluenceClient mockMvcConfluenceClient(WebApplicationContext context) {
            return RemoteClientsConfiguration.confluenceClient(
                    RestClient.builder().baseUrl("http://localhost/mock/confluence").requestFactory(overMockMvc(context)).build());
        }

        /**
         * A multipart body (attaching files, to Jira or Confluence) goes in as MockMvc's multipart request:
         * MockMvcClientHttpRequestFactory hands it over as raw bytes, and a MockHttpServletRequest has no parts.
         */
        private static ClientHttpRequestFactory overMockMvc(WebApplicationContext context) {
            AtomicReference<MockMvc> mvc = new AtomicReference<>();
            Supplier<MockMvc> mockMvc = () -> mvc.updateAndGet(m -> m != null ? m : MockMvcBuilders.webAppContextSetup(context).build());
            ClientHttpRequestFactory factory = (uri, method) -> new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() throws IOException {
                    MediaType type = getHeaders().getContentType();
                    if (type == null || !MediaType.MULTIPART_FORM_DATA.includes(type)) {
                        ClientHttpRequest plain = new MockMvcClientHttpRequestFactory(mockMvc.get()).createRequest(uri, method);
                        getHeaders().forEach((name, values) -> plain.getHeaders().put(name, values));
                        plain.getBody().write(getBodyAsBytes());
                        return plain.execute();
                    }
                    var request = MockMvcRequestBuilders.multipart(method, uri);
                    getHeaders().forEach((name, values) -> {
                        if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                            request.header(name, values.toArray());
                        }
                    });
                    parts(getBodyAsBytes(), type.getParameter("boundary")).forEach(request::file);
                    try {
                        MockHttpServletResponse response = mockMvc.get().perform(request).andReturn().getResponse();
                        MockClientHttpResponse answer = new MockClientHttpResponse(response.getContentAsByteArray(),
                                HttpStatusCode.valueOf(response.getStatus()));
                        response.getHeaderNames().forEach(n -> answer.getHeaders().addAll(n, response.getHeaders(n)));
                        return answer;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
            };
            return factory;
        }

        /** The file parts of a multipart/form-data body. */
        private static List<MockMultipartFile> parts(byte[] body, String boundary) {
            String text = new String(body, StandardCharsets.ISO_8859_1);
            List<MockMultipartFile> files = new java.util.ArrayList<>();
            for (String part : text.split("--" + java.util.regex.Pattern.quote(boundary))) {
                int split = part.indexOf("\r\n\r\n");
                if (split < 0) {
                    continue;
                }
                String headers = part.substring(0, split);
                String content = part.substring(split + 4, part.length() - 2);
                java.util.regex.Matcher name = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(headers);
                java.util.regex.Matcher filename = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
                java.util.regex.Matcher contentType = java.util.regex.Pattern.compile("(?i)content-type:\\s*(\\S+)").matcher(headers);
                files.add(new MockMultipartFile(name.find() ? name.group(1) : "file", filename.find() ? filename.group(1) : null,
                        contentType.find() ? contentType.group(1) : null, content.getBytes(StandardCharsets.ISO_8859_1)));
            }
            return files;
        }
    }
}
