package com.creed.resource.veracode.api;

import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.jira.JiraClient;
import com.creed.resource.veracode.config.RemoteClientsConfiguration;
import com.creed.resource.veracode.mock.MockJiraStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

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
 * The whole chain over the in-process mocks: the real {@code @HttpExchange} proxies, the real
 * Confluence page and the JQL-evaluating Jira mock - only the transport is MockMvc instead of a socket,
 * so no port has to be known before the context starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VeracodeApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MockJiraStore jira;

    @BeforeEach
    void reset() {
        jira.reset();
    }

    @Test
    void analysisMarksWhatJiraAlreadyHas() throws Exception {
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.source").value("CONFLUENCE"))
                .andExpect(jsonPath("$.page.id").value("98765"))
                .andExpect(jsonPath("$.page.version").value(7))
                .andExpect(jsonPath("$.page.url").value("http://localhost/mock/confluence/pages/viewpage.action?pageId=98765"))
                .andExpect(jsonPath("$.projectKey").value("CREED"))
                .andExpect(jsonPath("$.counts.total").value(10))
                .andExpect(jsonPath("$.counts.exists").value(3))
                .andExpect(jsonPath("$.counts.missing").value(7))
                .andExpect(jsonPath("$.counts.versionNotInJira").value(1))
                .andExpect(jsonPath("$.warnings", contains("table 2, row 11: skipped, neither a CVE nor a title")))
                // Filed for 2.3.0 -> found, link built from browse-url.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].issues[0].url")
                        .value("http://localhost/mock/jira/browse/CREED-101"))
                // Summary without the [Veracode] prefix still matches on the CVE token.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2020-36518@2.3.0')].issues[0].key").value("CREED-103"))
                // Filed for 2.2.1 only -> still missing for 2.3.0.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-22965@2.3.0')].status").value("MISSING"))
                // Two report rows, one finding.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-1471@2.3.0')].components[*]", hasSize(2)))
                // One cell, two CVEs: one filed, one not.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-4104@2.3.0')].status").value("EXISTS"))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2022-23302@2.3.0')].status").value("MISSING"))
                // No CVE -> matched (and keyed) by title.
                .andExpect(jsonPath("$.findings[?(@.id=='title:sql injection in reportquerybuilder@2.3.0')].status")
                        .value("MISSING"))
                // 2.5.0 is not a CREED version: not searched (the JQL would 400), flagged instead.
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2023-6378@2.5.0')].jiraVersion").value(contains(nullValue())))
                // Severity order: Very High first.
                .andExpect(jsonPath("$.findings[0].severity").value("Very High"));
    }

    @Test
    void createsMissingIssuesOnceAndReportsWhyOthersWereNotCreated() throws Exception {
        String request = """
                {"pageId":"98765","findingIds":["CVE-2022-22965@2.3.0","CVE-2021-44228@2.3.0",
                 "CVE-2023-6378@2.5.0","CVE-1999-0001@2.3.0","CVE-2022-22965@2.3.0"]}
                """;
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(4)))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"))
                .andExpect(jsonPath("$.results[1].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[1].issues[0].key").value("CREED-101"))
                .andExpect(jsonPath("$.results[2].outcome").value("VERSION_NOT_IN_JIRA"))
                .andExpect(jsonPath("$.results[3].outcome").value("NOT_IN_REPORT"));

        MockJiraStore.Issue created = jira.find("CREED-105");
        assertThat(created.summary()).isEqualTo("[Veracode] CVE-2022-22965 Spring Framework RCE via data binding on JDK 9+");
        assertThat(created.versions()).containsExactly("2.3.0");
        assertThat(created.labels()).containsExactly("veracode", "CVE-2022-22965");
        assertThat(created.description()).contains("|Component(s)|spring-beans 5.3.17|", "pageId=98765");

        // The second click: re-checked, not duplicated.
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingIds\":[\"CVE-2022-22965@2.3.0\"]}"))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[0].issues[0].key").value("CREED-105"));
        assertThat(jira.all()).hasSize(5);
    }

    @Test
    void autoCreateFilesEveryMissingFindingWhoseVersionExists() throws Exception {
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoCreate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.created").value(6))
                .andExpect(jsonPath("$.counts.missing").value(1))
                .andExpect(jsonPath("$.findings[?(@.status=='MISSING')].id", contains("CVE-2023-6378@2.5.0")));

        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.counts.exists").value(9))
                .andExpect(jsonPath("$.counts.created").value(0));
    }

    @Test
    void exportsTheAnalysisAsXlsx() throws Exception {
        byte[] xlsx = mvc.perform(get("/api/veracode/analysis/export").param("pageId", "98765"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VeracodeController.XLSX))
                .andExpect(header().string("Content-Disposition", containsString("veracode-98765-")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet findings = wb.getSheet("Findings");
            assertThat(findings.getLastRowNum()).isEqualTo(10);
            assertThat(findings.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Status");
            List<String> log4shell = new ArrayList<>();
            for (Row row : findings) {
                if ("CVE-2021-44228".equals(row.getCell(1).getStringCellValue())) {
                    row.forEach(c -> log4shell.add(c.toString()));
                }
            }
            assertThat(log4shell).contains("EXISTS", "CREED-101", "In Progress", "2.3.0");
            assertThat(wb.getSheet("Source").getRow(2).getCell(1).getStringCellValue()).isEqualTo("CREED");
        }
    }

    @Test
    void anUploadedFileIsAnalysedAndThenCreatedAndExportedByItsId() throws Exception {
        String csv = "CVE,Title,Component,Severity,Affected Version\n"
                + "CVE-2021-44228,Log4Shell,log4j-core 2.14.1,Very High,2.3.0\n"
                + "CVE-2022-42889,Text4Shell,commons-text 1.9,Very High,2.3.0\n";
        MockMultipartFile file = new MockMultipartFile("file", "sca-2.3.0.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        String body = mvc.perform(multipart("/api/veracode/analysis/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.source").value("UPLOAD"))
                .andExpect(jsonPath("$.page.title").value("sca-2.3.0.csv"))
                .andExpect(jsonPath("$.page.url").value(nullValue()))
                .andExpect(jsonPath("$.counts.total").value(2))
                .andExpect(jsonPath("$.counts.exists").value(1))
                .andExpect(jsonPath("$.findings[?(@.id=='CVE-2021-44228@2.3.0')].locations[0]").value("CSV, row 2"))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(body, "$.page.id");

        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\",\"findingIds\":[\"CVE-2022-42889@2.3.0\"]}"))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"));
        assertThat(jira.find("CREED-105").description()).contains("Source: uploaded file sca-2.3.0.csv");

        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(jsonPath("$.counts.exists").value(2));
        mvc.perform(get("/api/veracode/analysis/export").param("uploadId", uploadId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("veracode-upload-")));
    }

    @Test
    void uploadErrorsAreNamed() throws Exception {
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_file"));
        mvc.perform(multipart("/api/veracode/analysis/upload")
                        .file(new MockMultipartFile("file", "notes.csv", "text/csv", "a,b\n1,2\n".getBytes())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("report_unreadable"));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"no-such-upload\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("upload_expired"));
    }

    @Test
    void errorsAreNamed() throws Exception {
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("page_not_found"));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"98766\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("report_unreadable"));
        mvc.perform(post("/api/veracode/analysis").contentType(MediaType.APPLICATION_JSON).content("{\"pageId\":\"../x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/veracode/jira/issues").contentType(MediaType.APPLICATION_JSON).content("{\"findingIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("findingIds"));
    }

    @Test
    void settingsCarryNoCredentials() throws Exception {
        mvc.perform(get("/api/veracode/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPageId").value("98765"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * Same proxies, MockMvc underneath. MockMvc is built on first use: building it while the context is
     * still creating these beans would ask a half-built context for its filters.
     */
    @TestConfiguration
    static class ClientsOverMockMvc {

        @Bean
        @Primary
        ConfluenceClient mockMvcConfluenceClient(WebApplicationContext context) {
            return RemoteClientsConfiguration.confluenceClient(overMockMvc(context, "http://localhost/mock/confluence"));
        }

        @Bean
        @Primary
        JiraClient mockMvcJiraClient(WebApplicationContext context) {
            return RemoteClientsConfiguration.jiraClient(overMockMvc(context, "http://localhost/mock/jira"));
        }

        private static RestClient overMockMvc(WebApplicationContext context, String baseUrl) {
            AtomicReference<MockMvc> mvc = new AtomicReference<>();
            ClientHttpRequestFactory factory = (uri, method) -> new MockMvcClientHttpRequestFactory(
                    mvc.updateAndGet(m -> m != null ? m : MockMvcBuilders.webAppContextSetup(context).build()))
                    .createRequest(uri, method);
            return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
    }
}
