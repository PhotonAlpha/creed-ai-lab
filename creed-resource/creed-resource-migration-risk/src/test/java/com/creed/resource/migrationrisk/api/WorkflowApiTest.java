package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.demo.DemoRepositories;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ignore rules, export, remote repositories and the extra diff views. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRepositories demo;

    private String analyse(String body) throws Exception {
        return mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String demoBody() {
        var d = demo.view();
        return """
                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "repo2bPath": "%s"}
                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), d.repo2bPath());
    }

    @Test
    void ignoreRulesDropFilesOrFindings() throws Exception {
        String whole = JsonPath.read(mvc.perform(post("/api/migration-risk/ignore-rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pattern\": \"**/templates/*.vm\", \"note\": \"templates are ported separately\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id").toString();
        String category = JsonPath.read(mvc.perform(post("/api/migration-risk/ignore-rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pattern\": \"**/report/*.java\", \"category\": \"NEW_CODE\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id").toString();
        try {
            String json = analyse(demoBody());
            assertThat(JsonPath.<List<String>>read(json, "$.items[*].element")).noneMatch(e -> e.endsWith(".vm"));
            assertThat(JsonPath.<List<String>>read(json,
                    "$.items[?(@.element == 'src/main/java/com/acme/shop/report/SalesReport.java')].parts[*].category"))
                    .containsExactly("MIGRATION_API");
            assertThat(JsonPath.<List<String>>read(json, "$.warnings"))
                    .contains("ignore rules left out 2 file(s) and 1 finding(s) of kept files");
            mvc.perform(get("/api/migration-risk/ignore-rules")).andExpect(jsonPath("$.length()").value(2));
        } finally {
            mvc.perform(delete("/api/migration-risk/ignore-rules/{id}", whole)).andExpect(status().isNoContent());
            mvc.perform(delete("/api/migration-risk/ignore-rules/{id}", category)).andExpect(status().isNoContent());
        }
    }

    @Test
    void exportsAChecklist() throws Exception {
        String json = analyse(demoBody());
        mvc.perform(post("/api/migration-risk/export").param("format", "md").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("migration-sync.md")))
                .andExpect(content().string(allOf(containsString("# Migration sync checklist"),
                        containsString("## HIGH (4)"),
                        containsString("- [ ] `src/main/java/com/acme/shop/order/OrderService.java` — TARGET_MISSING"))));
        byte[] xlsx = mvc.perform(post("/api/migration-risk/export").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx).startsWith('P', 'K'); // a zip: the OOXML container
    }

    /** A file:// URL goes through the same clone + fetch as https: the repositories are read from the bare mirror. */
    @Test
    void remoteRepositoriesAreClonedAndAnalysed() throws Exception {
        var d = demo.view();
        String repo1 = Path.of(d.repo1Path()).toUri().toString();
        String json = analyse("""
                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s"}
                """.formatted(repo1, d.baseline(), d.repo2Path()));
        assertThat(JsonPath.<String>read(json, "$.source.repo1Path")).isEqualTo(repo1);
        assertThat(JsonPath.<Integer>read(json, "$.counts.total")).isEqualTo(13);
        mvc.perform(get("/api/migration-risk/refs").param("path", repo1))
                .andExpect(jsonPath("$.tags[0]").value(DemoRepositories.BASELINE_TAG));
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content("""
                        {"repo1Path": "https://elsewhere.example.org/x.git", "baseline": "HEAD", "repo2Path": "%s"}
                        """.formatted(d.repo2Path())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.fields[0].field").value("repo1Path"));
    }

    @Test
    void threeWayViewMarksWhoChangedWhat() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff3").param("repo1Path", d.repo1Path()).param("baseline", d.baseline())
                        .param("repo2Path", d.repo2Path()).param("file", "src/main/java/com/acme/shop/payment/PaymentClient.java")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Three-way view"), containsString("\"kind\":\"upstream\""),
                        containsString("\"kind\":\"migration\""), containsString("amount must be positive"))));
    }

    /** A config key repo2B keeps in another file: the B diff opens that file when the page names it. */
    @Test
    void sideBCanBePointedAtAnotherRepo2File() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff").param("repo1Path", d.repo1Path()).param("baseline", d.baseline())
                        .param("repo2Path", d.repo2Path()).param("repo2bPath", d.repo2bPath()).param("side", "B")
                        .param("file", "src/main/resources/application.yml")
                        .param("target", "src/main/resources/config/application.yml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src/main/resources/config/application.yml")));
    }
}
