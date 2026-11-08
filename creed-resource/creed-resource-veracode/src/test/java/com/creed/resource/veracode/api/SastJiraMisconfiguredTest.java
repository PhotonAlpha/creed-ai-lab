package com.creed.resource.veracode.api;

import com.creed.resource.veracode.mock.MockJiraStore;
import com.creed.resource.veracode.service.JiraToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A SAST issue type the Jira project does not have: the analysis still answers - SCA in full - and the SAST half says
 * why, with its issues listed but no status. Its own context: the SAST config is what differs.
 */
@SpringBootTest(properties = "veracode.jira.sast.issue-type=Epic")
@ActiveProfiles("test")
@Import(VeracodeApiTest.ClientsOverMockMvc.class)
class SastJiraMisconfiguredTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    MockJiraStore jira;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        jira.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header(JiraToken.HEADER, "pat-0000-abcd"))
                .build();
    }

    @Test
    void scaStillAnswersAndSastSaysWhy() throws Exception {
        MockMultipartFile sample = new MockMultipartFile("file", "detailedreport.xml", "application/xml",
                new ClassPathResource(VeracodeController.SAMPLE).getContentAsByteArray());
        mvc.perform(multipart("/api/veracode/analysis/upload").file(sample))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(10))
                // no Jira version chosen: any version, so the 2.2.1-only issue counts too
                .andExpect(jsonPath("$.counts.exists").value(4))
                .andExpect(jsonPath("$.sast.error", containsString("'Epic' cannot be created in project CREED")))
                .andExpect(jsonPath("$.sast.issues.length()").value(2))
                .andExpect(jsonPath("$.sast.issues[0].status").doesNotExist());
    }
}
