package com.creed.resource.migrationrisk.web;

import com.creed.resource.migrationrisk.demo.DemoRepositories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The Thymeleaf diff page renders RefactoringMiner's viewer config for both sides, in both modes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiffViewTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRepositories demo;

    @Test
    void sideBComparesRepo1HeadWithWhereTheMigrationMovedTheMethod() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "B").param("file", "src/main/java/com/acme/shop/order/OrderProcessor.java")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(view().name("diff"))
                .andExpect(content().string(allOf(
                        containsString("mymonaco({"),
                        containsString("src/main/java/com/acme/shop/order/processing/OrderProcessor.java"),
                        containsString("handle renamed to process"),
                        containsString("repo1 vs repo2: repo1 HEAD → repo2 HEAD"),
                        // the left side is repo1 HEAD (with the upstream edit), not the baseline
                        containsString("orderId.trim().replace("),
                        containsString("left-container-"),
                        containsString("/api/migration-risk/view/assets/monaco.js"))));
    }

    @Test
    void sideAShowsTheUpstreamChangeWithPrevNext() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "A").param("index", "0").header("Accept-Language", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("mymonaco({"),
                        containsString("下一个（还有"),
                        containsString("index=1"))));
    }

    /** RefactoringMiner only pairs modified files; a new upstream file is shown one-sided, whole file inserted. */
    @Test
    void anAddedFileIsShownOneSided() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "A").param("file", "src/main/java/com/acme/shop/report/SalesReport.java")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("\"kind\":\"inserted\""),
                        containsString("\"file\":\"src/main/java/com/acme/shop/report/SalesReport.java\""),
                        containsString("new file"))));
    }

    @Test
    void folderModeRendersToo() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/view/diff").param("mode", "DIRECTORY")
                        .param("baselinePath", d.baselineFolder()).param("repo1Path", d.repo1Folder())
                        .param("repo2Path", d.repo2Folder()).param("side", "B"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mymonaco({")));
    }

    /** RefactoringMiner reads Java only; config and templates get a plain line diff in the same viewer. */
    @Test
    void configAndTemplatesGetALineDiff() throws Exception {
        var d = demo.view();
        // repo1 HEAD's application.yml next to repo2's
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "B").param("file", "src/main/resources/application.yml"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("max-items: 20"), containsString("max-items: 10"),
                        containsString("\"kind\":\"inserted\""), containsString("\"kind\":\"deleted\""))));
        // with repo2B, invoice.vm is put next to its FreeMarker version
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("repo2bPath", d.repo2bPath())
                        .param("side", "B").param("file", "src/main/resources/templates/invoice.vm"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("src/main/resources/templates/invoice.ftl"),
                        containsString("<#list invoice.lines as line>"), containsString("$!invoice.customer"))));
        // the upstream change of a template
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "A").param("file", "src/main/resources/templates/invoice.vm"))
                .andExpect(content().string(containsString("Due: $invoice.dueDate")));
        // a file the side does not have is an error, not silently the first file
        mvc.perform(get("/api/migration-risk/view/diff")
                        .param("repo1Path", d.repo1Path()).param("baseline", d.baseline()).param("repo2Path", d.repo2Path())
                        .param("side", "B").param("file", "no/such/file.txt"))
                .andExpect(content().string(containsString("no/such/file.txt has no diff on this side")));
    }

    @Test
    void viewerAssetsAreServedFromTheRefactoringMinerJar() throws Exception {
        mvc.perform(get("/api/migration-risk/view/assets/monaco.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("function mymonaco(config)")));
    }

    @Test
    void aBadPathIsShownOnThePage() throws Exception {
        mvc.perform(get("/api/migration-risk/view/diff").param("repo1Path", "/").param("baseline", "HEAD")
                        .param("repo2Path", "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("outside migration-risk.allowed-roots"),
                        not(containsString("mymonaco({")))));
    }
}
