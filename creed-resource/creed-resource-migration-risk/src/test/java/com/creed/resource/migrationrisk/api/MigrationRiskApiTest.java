package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.demo.DemoRepositories;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chain over the demo repositories: JGit diff and export, both real RefactoringMiner runs, and
 * the cross-check. Each upstream change in {@code demo/repo1-head} was written to land in one category;
 * this pins every one of them — first against repo2 alone, then against repo2A + repo2B ({@code demo/repo2b} holds
 * the templates converted to FreeMarker, the tests and a config/application.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MigrationRiskApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRepositories demo;

    private static String analysis;
    private static String merged;

    @BeforeAll
    static void reset() {
        analysis = null;
        merged = null;
    }

    /** repo2A + repo2B as one code base. */
    private String analyseMerged() throws Exception {
        if (merged == null) {
            var d = demo.view();
            merged = analyse("""
                    {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "repo2bPath": "%s"}
                    """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), d.repo2bPath()));
        }
        return merged;
    }

    private String analyse(String body) throws Exception {
        return mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> item(String json, String element) {
        List<Map<String, Object>> items = JsonPath.read(json, "$.items[?(@.element == '" + element + "')]");
        assertThat(items).as(element).hasSize(1);
        return items.getFirst();
    }

    private String analyseDemo() throws Exception {
        if (analysis == null) {
            var d = demo.view();
            analysis = mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s"}
                                    """.formatted(d.repo1Path(), d.baseline(), d.repo2Path())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
        return analysis;
    }

    /** The one item for {@code element}, as a map. */
    private Map<String, Object> item(String element) throws Exception {
        return item(analyseDemo(), element);
    }

    private void assertItem(String element, String level, String category, String targetState) throws Exception {
        Map<String, Object> item = item(element);
        assertThat(item).as(element)
                .containsEntry("level", level)
                .containsEntry("category", category)
                .containsEntry("targetState", targetState);
    }

    @Test
    void settingsOfferTheDemoRepositories() throws Exception {
        mvc.perform(get("/api/migration-risk/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo.baseline").value(DemoRepositories.BASELINE_TAG))
                .andExpect(jsonPath("$.demo.repo1Path").value(endsWith("repo1")))
                .andExpect(jsonPath("$.demo.baselineFolder").value(endsWith("folders/baseline")))
                .andExpect(jsonPath("$.apiRules[0].id").value("jackson2"));
    }

    @Test
    void modifiedMethodTheMigrationDeletedIsHigh() throws Exception {
        assertItem("com.acme.shop.order.OrderService#legacyTotal(long,long)", "HIGH", "TARGET_MISSING", "MISSING");
    }

    @Test
    void modifiedMethodUntouchedByTheMigrationSyncsAsIs() throws Exception {
        assertItem("com.acme.shop.order.OrderService#cancelOrder(String)", "LOW", "AUTO_SYNC", "UNCHANGED");
    }

    @Test
    void movedAndRenamedMethodIsFoundAtItsNewPlace() throws Exception {
        Map<String, Object> item = item("com.acme.shop.order.OrderProcessor#handle(String)");
        assertThat(item).containsEntry("level", "MEDIUM").containsEntry("category", "RELOCATED");
        assertThat(JsonPath.<String>read(item, "$.target.path"))
                .isEqualTo("src/main/java/com/acme/shop/order/processing/OrderProcessor.java");
        assertThat(JsonPath.<String>read(item, "$.target.element")).contains("process(orderId String)");
        assertThat(JsonPath.<List<String>>read(item, "$.evidence")).anyMatch(e -> e.contains("Rename Method"));
    }

    @Test
    void methodBothSidesChangedNeedsAMerge() throws Exception {
        assertItem("com.acme.shop.payment.PaymentClient#charge(String,long)", "MEDIUM", "BOTH_MODIFIED", "MODIFIED");
    }

    @Test
    void classDeletedUpstreamButStillInRepo2() throws Exception {
        Map<String, Object> item = item("com.acme.shop.customer.CustomerMapper");
        assertThat(item).containsEntry("category", "DELETED_UPSTREAM").containsEntry("kind", "CLASS")
                .containsEntry("change", "DELETED");
    }

    @Test
    void upstreamRenameIsReportedOnceNotAsDeletePlusAdd() throws Exception {
        assertItem("com.acme.shop.invoice.InvoiceService#generate(String,long)", "MEDIUM", "UPSTREAM_REFACTORING",
                "UNCHANGED");
        List<?> renderAdded = JsonPath.read(analyseDemo(), "$.items[?(@.element =~ /.*render.*/)]");
        assertThat(renderAdded).isEmpty();
    }

    @Test
    void newCodeAndItsJackson2Usage() throws Exception {
        assertItem("com.acme.shop.order.OrderService#orderCount()", "LOW", "NEW_CODE", "ABSENT");
        assertItem("com.acme.shop.report.SalesReport", "LOW", "NEW_CODE", "ABSENT");
        Map<String, Object> api = item("src/main/java/com/acme/shop/report/SalesReport.java [jackson2]");
        assertThat(api).containsEntry("level", "HIGH").containsEntry("category", "MIGRATION_API");
        assertThat(JsonPath.<List<String>>read(api, "$.details"))
                .containsExactly("L3: import com.fasterxml.jackson.databind.ObjectMapper;",
                        "L4: import com.fasterxml.jackson.databind.node.ObjectNode;");
    }

    @Test
    void configAndDependencyChangesShowWhatRepo2Has() throws Exception {
        // one item per key, not per file
        Map<String, Object> maxItems = item("shop.order.max-items");
        assertThat(maxItems).containsEntry("level", "HIGH").containsEntry("category", "CONFIG_CHANGED")
                .containsEntry("kind", "CONFIG");
        assertThat(JsonPath.<List<String>>read(maxItems, "$.details")).containsExactly(
                "~ shop.order.max-items: 10 -> 20",
                "repo1: 20 @ src/main/resources/application.yml",
                "repo2: 10 @ src/main/resources/application.yml");
        assertThat(JsonPath.<List<String>>read(item("shop.order.retry.enabled"), "$.details"))
                .containsExactly("+ shop.order.retry.enabled = true",
                        "repo1: true @ src/main/resources/application.yml", "repo2: missing");
        // spring.application.name moved from application.yml to application.properties, same value: no change
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element == 'spring.application.name')]")).isEmpty();
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element =~ /.*application\\.(yml|properties)/)]")).isEmpty();

        Map<String, Object> pom = item("pom.xml");
        assertThat(pom).containsEntry("level", "HIGH").containsEntry("category", "DEPENDENCY_CHANGED");
        assertThat(JsonPath.<List<String>>read(pom, "$.details")).containsExactly(
                "~ property commons-lang3.version: 3.14.0 -> 3.15.0  (repo2: 3.17.0)",
                "+ dependency com.google.guava:guava = 33.4.0-jre  (repo2: missing)");
    }

    @Test
    void nonJavaFileChangedOnBothSides() throws Exception {
        assertItem("src/main/resources/camel/order-route.xml", "MEDIUM", "NON_JAVA_FILE", "MODIFIED");
        // repo2 alone has no template at all: the Velocity file is just missing
        assertItem("src/main/resources/templates/invoice.vm", "MEDIUM", "NON_JAVA_FILE", "MISSING");
    }

    @Test
    void testCodeIsTagged() throws Exception {
        Map<String, Object> test = item("com.acme.shop.order.OrderServiceTest#unknownOrderIsNotCancelled()");
        assertThat(test).containsEntry("test", true).containsEntry("module", "(root)")
                .containsEntry("category", "TARGET_MISSING");
        assertThat(item("com.acme.shop.order.OrderService#orderCount()")).containsEntry("test", false);
    }

    // ---------------------------------------------------------------- repo2A + repo2B

    @Test
    void repo2bIsSearchedTooAndNamedOnTheTarget() throws Exception {
        String json = analyseMerged();
        assertThat(JsonPath.<String>read(json, "$.source.repo2bPath")).endsWith("repo2b");
        assertThat(JsonPath.<String>read(json, "$.source.repo2bHead.revision")).isEqualTo("HEAD");
        // found in repo2A: labelled so
        assertThat(JsonPath.<String>read(item(json, "com.acme.shop.order.OrderService#cancelOrder(String)"), "$.target.repo"))
                .isEqualTo("repo2A");
        // the test class only repo2B has: no longer missing
        Map<String, Object> test = item(json, "com.acme.shop.order.OrderServiceTest#unknownOrderIsNotCancelled()");
        assertThat(test).containsEntry("category", "NEW_CODE").containsEntry("test", true);
        assertThat(JsonPath.<String>read(test, "$.target.repo")).isEqualTo("repo2B");
        // a config key repo2B holds in another file is in sync
        Map<String, Object> retry = item(json, "shop.order.retry.enabled");
        assertThat(retry).containsEntry("level", "LOW").containsEntry("category", "CONFIG_IN_SYNC");
        assertThat(JsonPath.<String>read(retry, "$.target.path")).isEqualTo("src/main/resources/config/application.yml");
        assertThat(JsonPath.<String>read(retry, "$.target.repo")).isEqualTo("repo2B");
        assertThat(JsonPath.<Integer>read(json, "$.counts.total")).isEqualTo(16);
        assertThat(JsonPath.<Integer>read(json, "$.counts.high")).isEqualTo(4);
    }

    @Test
    void velocityTemplatesAreFoundAsFreeMarker() throws Exception {
        String json = analyseMerged();
        Map<String, Object> invoice = item(json, "src/main/resources/templates/invoice.vm");
        assertThat(invoice).containsEntry("level", "MEDIUM").containsEntry("category", "TEMPLATE_CONVERTED")
                .containsEntry("targetState", "RELOCATED");
        assertThat(JsonPath.<String>read(invoice, "$.target.path")).isEqualTo("src/main/resources/templates/invoice.ftl");
        assertThat(JsonPath.<List<String>>read(invoice, "$.details")).containsSubsequence(
                "- L2: <p>Customer: $invoice.customer</p>",
                "+ L2: <p>Customer: $!invoice.customer</p>",
                "+ L3: <p>Due: $invoice.dueDate</p>");
        // a new .vm while repo2 only has .ftl: to be written as FreeMarker
        assertThat(item(json, "src/main/resources/templates/receipt.vm"))
                .containsEntry("category", "TEMPLATE_CONVERTED").containsEntry("targetState", "ABSENT");
    }

    @Test
    void skipTestsLeavesTestCodeOut() throws Exception {
        var d = demo.view();
        String json = analyse("""
                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "repo2bPath": "%s", "skipTests": true}
                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), d.repo2bPath()));
        assertThat(JsonPath.<List<Boolean>>read(json, "$.items[*].test")).isNotEmpty().doesNotContain(true);
        assertThat(JsonPath.<Boolean>read(json, "$.source.skipTests")).isTrue();
        assertThat(JsonPath.<List<String>>read(json, "$.warnings")).contains("1 changed test file(s) skipped (skipTests)");
        assertThat(JsonPath.<Integer>read(json, "$.counts.total")).isEqualTo(15);
    }

    @Test
    void foldersWithRepo2bGiveTheSameItemsAsGit() throws Exception {
        var d = demo.view();
        String folders = mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baselinePath": "%s", "repo1Path": "%s", "repo2Path": "%s", "repo2bPath": "%s"}
                                """.formatted(d.baselineFolder(), d.repo1Folder(), d.repo2Folder(), d.repo2bFolder())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(folders, "$.items[*].id"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(analyseMerged(), "$.items[*].id"));
        assertThat(JsonPath.<List<String>>read(folders, "$.items[*].target.repo"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(analyseMerged(), "$.items[*].target.repo"));
    }

    @Test
    void itemsAreSortedRiskiestFirstAndCounted() throws Exception {
        String json = analyseDemo();
        List<String> levels = JsonPath.read(json, "$.items[*].level");
        assertThat(levels).isSortedAccordingTo((a, b) -> List.of("HIGH", "MEDIUM", "LOW").indexOf(a)
                - List.of("HIGH", "MEDIUM", "LOW").indexOf(b));
        int total = JsonPath.read(json, "$.counts.total");
        assertThat(total).isEqualTo(levels.size()).isEqualTo(16);
        assertThat(JsonPath.<Integer>read(json, "$.counts.high")).isEqualTo(6);
        assertThat(JsonPath.<String>read(json, "$.source.baseline.revision")).isEqualTo(DemoRepositories.BASELINE_TAG);
        assertThat(JsonPath.<List<String>>read(json, "$.warnings")).containsExactly(
                "1 config key(s) only moved between files upstream, values unchanged: not reported");
    }

    @Test
    void repositoryOutsideTheAllowedRootsIsRefused() throws Exception {
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "/", "baseline": "HEAD", "repo2Path": "/"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("path_not_allowed"))
                .andExpect(jsonPath("$.fields[0].field").value("repo1Path"));
    }

    @Test
    void refsAreListedPerRepository() throws Exception {
        var d = demo.view();
        mvc.perform(get("/api/migration-risk/refs").param("path", d.repo1Path()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("main"))
                .andExpect(jsonPath("$.branches[0]").value("main"))
                .andExpect(jsonPath("$.tags[0]").value(DemoRepositories.BASELINE_TAG))
                .andExpect(jsonPath("$.recentCommits.length()").value(2))
                .andExpect(jsonPath("$.recentCommits[0].message").value(containsString("Upstream")));
        // recent commits follow the selected head, not the checked-out branch
        mvc.perform(get("/api/migration-risk/refs").param("path", d.repo1Path()).param("head", DemoRepositories.BASELINE_TAG))
                .andExpect(jsonPath("$.head.message").value(containsString("before the Boot 4 migration")))
                .andExpect(jsonPath("$.recentCommits.length()").value(1));
        mvc.perform(get("/api/migration-risk/refs").param("path", d.repo2Path()))
                .andExpect(jsonPath("$.currentBranch").value("main"))
                .andExpect(jsonPath("$.tags").isEmpty());
    }

    @Test
    void refsOfABadPathMarkThePathField() throws Exception {
        mvc.perform(get("/api/migration-risk/refs").param("path", "/"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.fields[0].field").value("path"));
    }

    @Test
    void noSuggestionWhenTheHistoriesAreUnrelated() throws Exception {
        var d = demo.view();
        // demo repo2 was committed from a copy, not cloned: nothing to compute a merge-base from
        mvc.perform(get("/api/migration-risk/baseline-suggestion")
                        .param("repo1Path", d.repo1Path()).param("repo2Path", d.repo2Path()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("NO_SHARED_HISTORY"))
                .andExpect(jsonPath("$.baseline").doesNotExist());
    }

    /** Two clones of one origin — the common case — get the merge-base of the selected heads suggested. */
    @Test
    void mergeBaseOfTwoClonesIsSuggested(@Autowired MigrationRiskProperties properties) throws Exception {
        var d = demo.view();
        Path clone = properties.workDir().resolve("demo").resolve("clone-" + UUID.randomUUID());
        String repo1Head;
        try (Git git = Git.cloneRepository().setURI(Path.of(d.repo1Path()).toUri().toString())
                .setDirectory(clone.toFile()).call()) {
            repo1Head = git.getRepository().resolve("HEAD").name();
            Files.writeString(clone.resolve("MIGRATED.md"), "moved to Boot 4");
            git.add().addFilepattern(".").call();
            PersonIdent who = new PersonIdent("t", "t@creed.local");
            git.commit().setMessage("migrate").setAuthor(who).setCommitter(who).setSign(false).call();
        }
        mvc.perform(get("/api/migration-risk/baseline-suggestion")
                        .param("repo1Path", d.repo1Path()).param("repo2Path", clone.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("MERGE_BASE"))
                .andExpect(jsonPath("$.baseline.id").value(repo1Head));
        // another repo1 head moves the merge-base with it
        mvc.perform(get("/api/migration-risk/baseline-suggestion").param("repo1Path", d.repo1Path())
                        .param("repo1Head", DemoRepositories.BASELINE_TAG).param("repo2Path", clone.toString()))
                .andExpect(jsonPath("$.baseline.message").value(containsString("before the Boot 4 migration")));
    }

    /**
     * Folder mode over the same three versions as plain directories: no git, and exactly the same items as
     * git mode — the trees differ, the analysis does not.
     */
    @Test
    void folderModeGivesTheSameItemsAsGitMode() throws Exception {
        var d = demo.view();
        String folders = mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baselinePath": "%s", "repo1Path": "%s", "repo2Path": "%s"}
                                """.formatted(d.baselineFolder(), d.repo1Folder(), d.repo2Folder())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.mode").value("DIRECTORY"))
                .andExpect(jsonPath("$.source.baseline").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        List<String> fromFolders = JsonPath.read(folders, "$.items[*].id");
        List<String> fromGit = JsonPath.read(analyseDemo(), "$.items[*].id");
        assertThat(fromFolders).containsExactlyElementsOf(fromGit);
        List<String> levelsFromFolders = JsonPath.read(folders, "$.items[*].level");
        List<String> levelsFromGit = JsonPath.read(analyseDemo(), "$.items[*].level");
        assertThat(levelsFromFolders).containsExactlyElementsOf(levelsFromGit);
    }

    @Test
    void folderModeNeedsTheBaselineFolder() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "repo2Path": "%s"}
                                """.formatted(d.repo1Folder(), d.repo2Folder())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("baselinePath"));
    }

    @Test
    void unknownBaselineIsABadRequest() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "no-such-tag", "repo2Path": "%s"}
                                """.formatted(d.repo1Path(), d.repo2Path())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_repository"))
                .andExpect(jsonPath("$.message").value(containsString("no-such-tag")))
                // the page marks the baseline input, not the repository path
                .andExpect(jsonPath("$.fields[0].field").value("baseline"));
    }

    @Test
    void missingFieldsAreValidated() throws Exception {
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
