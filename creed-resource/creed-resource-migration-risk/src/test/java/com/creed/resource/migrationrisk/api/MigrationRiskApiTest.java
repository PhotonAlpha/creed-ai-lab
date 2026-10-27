package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.demo.DemoRepositories;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chain over the demo repositories: tree diff and export, the real RefactoringMiner run repo1 -> repo2,
 * and the member-by-member comparison. Each difference between {@code demo/repo1} and {@code demo/repo2} was written
 * to land in one category; this pins every one of them — first against repo2 alone, then against repo2A + repo2B
 * ({@code demo/repo2b} holds the templates converted to FreeMarker, the tests and a config/application.yml).
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
                    {"repo1Path": "%s", "repo2Path": "%s", "repo2bPath": "%s"}
                    """.formatted(d.repo1Path(), d.repo2Path(), d.repo2bPath()));
        }
        return merged;
    }

    private String analyse(String body) throws Exception {
        return mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The one element-level finding for {@code element}: a part of its file's record. */
    private static Map<String, Object> item(String json, String element) {
        List<Map<String, Object>> items = JsonPath.read(json, "$.items[*].parts[?(@.element == '" + element + "')]");
        assertThat(items).as(element).hasSize(1);
        return items.getFirst();
    }

    /** The record of one file. */
    private static Map<String, Object> file(String json, String path) {
        List<Map<String, Object>> files = JsonPath.read(json, "$.items[?(@.element == '" + path + "')]");
        assertThat(files).as(path).hasSize(1);
        return files.getFirst();
    }

    private String analyseDemo() throws Exception {
        if (analysis == null) {
            var d = demo.view();
            analysis = analyse("""
                    {"repo1Path": "%s", "repo2Path": "%s"}
                    """.formatted(d.repo1Path(), d.repo2Path()));
        }
        return analysis;
    }

    private Map<String, Object> item(String element) throws Exception {
        return item(analyseDemo(), element);
    }

    private void assertItem(String element, String level, String category, String targetState) throws Exception {
        assertThat(item(element)).as(element)
                .containsEntry("level", level)
                .containsEntry("category", category)
                .containsEntry("targetState", targetState);
    }

    @Test
    void settingsOfferTheDemoRepositories() throws Exception {
        mvc.perform(get("/api/migration-risk/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo.repo1Path").value(endsWith("repo1")))
                .andExpect(jsonPath("$.demo.repo1Folder").value(endsWith("folders/repo1")))
                .andExpect(jsonPath("$.demo.baseline").doesNotExist())
                .andExpect(jsonPath("$.apiRules[0].id").value("jackson2"));
    }

    @Test
    void methodsRepo2DoesNotHaveAreHigh() throws Exception {
        assertItem("com.acme.shop.order.OrderService#legacyTotal(long,long)", "HIGH", "TARGET_MISSING", "MISSING");
        assertItem("com.acme.shop.order.OrderService#orderCount()", "HIGH", "TARGET_MISSING", "MISSING");
    }

    @Test
    void methodsThatDifferCarryTheirLineDiff() throws Exception {
        Map<String, Object> cancel = item("com.acme.shop.order.OrderService#cancelOrder(String)");
        assertThat(cancel).containsEntry("level", "MEDIUM").containsEntry("category", "CONTENT_DIFFERS")
                .containsEntry("targetState", "MODIFIED").containsEntry("change", "MODIFIED");
        // like git diff repo1 repo2: - = repo1's line (numbered in repo1's file), + = repo2's
        assertThat(JsonPath.<List<String>>read(cancel, "$.details"))
                .containsExactly("- L17: if (id == null) {", "- L18: return false;", "- L19: }");
        assertItem("com.acme.shop.payment.PaymentClient#charge(String,long)", "MEDIUM", "CONTENT_DIFFERS", "MODIFIED");
        assertThat(JsonPath.<List<String>>read(item("com.acme.shop.payment.PaymentClient"), "$.details"))
                .containsExactly("- private final RestTemplate rest=new RestTemplate();", "+ private final RestClient rest;");
    }

    @Test
    void movedClassAndRenamedMethodAreFoundAtTheirNewPlace() throws Exception {
        Map<String, Object> handle = item("com.acme.shop.order.OrderProcessor#handle(String)");
        assertThat(handle).containsEntry("level", "MEDIUM").containsEntry("category", "RELOCATED");
        assertThat(JsonPath.<String>read(handle, "$.target.path"))
                .isEqualTo("src/main/java/com/acme/shop/order/processing/OrderProcessor.java");
        assertThat(JsonPath.<String>read(handle, "$.target.element")).contains("process(orderId String)");
        assertThat(JsonPath.<List<String>>read(handle, "$.evidence")).anyMatch(e -> e.contains("Rename Method"));
        // the class line: where it went, and that something in it still differs
        Map<String, Object> cls = item("com.acme.shop.order.OrderProcessor");
        assertThat(cls).containsEntry("category", "RELOCATED").containsEntry("kind", "CLASS");
        // repo2's file at the new path is the class's counterpart, not a class only repo2 has
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element =~ /.*processing.*/)]")).isEmpty();
    }

    /** repo1 renamed generate -> render; repo2 has the same method under the old name: nothing to port. */
    @Test
    void renamedButIdenticalMethodIsSynced() throws Exception {
        Map<String, Object> render = item("com.acme.shop.invoice.InvoiceService#render(String,long)");
        assertThat(render).containsEntry("level", "LOW").containsEntry("category", "ALREADY_SYNCED");
        assertThat(file(analyseDemo(), "src/main/java/com/acme/shop/invoice/InvoiceService.java"))
                .containsEntry("category", "ALREADY_SYNCED");
    }

    @Test
    void whatOnlyRepo2HasIsLow() throws Exception {
        Map<String, Object> mapper = file(analyseDemo(), "src/main/java/com/acme/shop/customer/CustomerMapper.java");
        assertThat(mapper).containsEntry("level", "LOW").containsEntry("category", "ONLY_IN_REPO2")
                .containsEntry("change", "DELETED").containsEntry("upstream", null);
        assertItem("spring.jackson.use-jackson2-defaults", "LOW", "ONLY_IN_REPO2", "EXTRA");
    }

    @Test
    void newClassAndItsJackson2Usage() throws Exception {
        assertItem("com.acme.shop.report.SalesReport", "HIGH", "TARGET_MISSING", "MISSING");
        Map<String, Object> api = item("src/main/java/com/acme/shop/report/SalesReport.java [jackson2]");
        assertThat(api).containsEntry("level", "MEDIUM").containsEntry("category", "MIGRATION_API");
        assertThat(JsonPath.<List<String>>read(api, "$.details"))
                .containsExactly("L3: import com.fasterxml.jackson.databind.ObjectMapper;",
                        "L4: import com.fasterxml.jackson.databind.node.ObjectNode;");
    }

    /**
     * OrderValidator differs only in its imports (javax -> jakarta): the migration's rewrite, not a difference to
     * sync. Its API matches are dropped with it; next to a real difference (OrderJson) they stay as porting hints.
     */
    @Test
    void anApiRewriteAloneIsNotReported() throws Exception {
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element =~ /.*OrderValidator.*/)]")).isEmpty();
        assertThat(JsonPath.<List<String>>read(file(analyseDemo(), "src/main/java/com/acme/shop/order/OrderJson.java"),
                "$.parts[*].category")).contains("CONTENT_DIFFERS", "MIGRATION_API");
    }

    @Test
    void configAndDependencyDifferences() throws Exception {
        // one item per key, not per file
        Map<String, Object> maxItems = item("shop.order.max-items");
        assertThat(maxItems).containsEntry("level", "HIGH").containsEntry("category", "CONFIG_CHANGED")
                .containsEntry("kind", "CONFIG");
        assertThat(JsonPath.<List<String>>read(maxItems, "$.details")).containsExactly(
                "~ shop.order.max-items: 20  (repo2: 10)",
                "repo1: 20 @ src/main/resources/application.yml",
                "repo2: 10 @ src/main/resources/application.yml");
        assertThat(JsonPath.<List<String>>read(item("shop.order.retry.enabled"), "$.details"))
                .containsExactly("- shop.order.retry.enabled = true  (repo2: missing)",
                        "repo1: true @ src/main/resources/application.yml");
        // spring.application.name: application.properties in repo1, application.yml in repo2, same value: no difference
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[*].parts[?(@.element == 'spring.application.name')]")).isEmpty();
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element =~ /.*application\\.properties/)]")).isEmpty();
        Map<String, Object> yml = file(analyseDemo(), "src/main/resources/application.yml");
        assertThat(JsonPath.<List<String>>read(yml, "$.parts[*].element")).containsExactly(
                "shop.order.max-items", "shop.order.retry.enabled", "spring.redis.host",
                "spring.jackson.use-jackson2-defaults", "spring.redis.port");
        assertThat(yml).containsEntry("level", "HIGH").containsEntry("kind", "FILE");

        Map<String, Object> pom = item("pom.xml");
        assertThat(pom).containsEntry("level", "HIGH").containsEntry("category", "DEPENDENCY_CHANGED");
        assertThat(JsonPath.<List<String>>read(pom, "$.details")).contains(
                "~ property commons-lang3.version: 3.15.0  (repo2: 3.17.0)",
                "- dependency com.google.guava:guava = 33.4.0-jre  (repo2: missing)",
                "+ dependency tools.jackson.core:jackson-databind = managed");
    }

    /**
     * Camel XML: compared by route id across all route files, step by step. order-audit moved to repo2's
     * audit-route.xml (with a camel: prefix and other formatting) and is identical: not reported, and neither is that file.
     */
    @Test
    void camelRoutesAreComparedByRouteId() throws Exception {
        Map<String, Object> intake = item("route order-intake");
        assertThat(intake).containsEntry("level", "MEDIUM").containsEntry("category", "CONTENT_DIFFERS")
                .containsEntry("kind", "ROUTE").containsEntry("targetState", "MODIFIED");
        assertThat(JsonPath.<List<String>>read(intake, "$.details")).containsExactly(
                "- log message=order ${body}",
                "- filter",
                "-   simple: ${body} != null",
                "-   to uri=bean:orderService?method=placeOrder",
                "+ log message=order ${body} (${header.CamelHttpMethod})",
                "+ to uri=bean:orderService?method=placeOrder");
        Map<String, Object> cancel = item("route order-cancel");
        assertThat(cancel).containsEntry("level", "HIGH").containsEntry("category", "TARGET_MISSING");
        assertThat(JsonPath.<List<String>>read(cancel, "$.details"))
                .containsExactly("- from uri=direct:cancel", "- to uri=bean:orderService?method=cancelOrder");
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[*].parts[?(@.element == 'route order-audit')]")).isEmpty();
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[?(@.element =~ /.*audit-route\\.xml/)]")).isEmpty();
        // the file record: route findings only, no line diff of the whole file
        assertThat(JsonPath.<List<String>>read(file(analyseDemo(), "src/main/resources/camel/order-route.xml"),
                "$.parts[*].element")).containsExactly("route order-cancel", "route order-intake");
        // repo2 alone has no template at all
        assertItem("src/main/resources/templates/invoice.vm", "HIGH", "TARGET_MISSING", "MISSING");
    }

    /** One record per file: the riskiest finding decides it, the others ride along as parts. */
    @Test
    void findingsAreGroupedPerFile() throws Exception {
        Map<String, Object> orderService = file(analyseDemo(), "src/main/java/com/acme/shop/order/OrderService.java");
        assertThat(orderService).containsEntry("level", "HIGH").containsEntry("category", "TARGET_MISSING")
                .containsEntry("kind", "FILE").containsEntry("change", "MODIFIED")
                .containsEntry("id", "FILE:src/main/java/com/acme/shop/order/OrderService.java");
        assertThat(JsonPath.<List<String>>read(orderService, "$.parts[*].element")).containsExactly(
                "com.acme.shop.order.OrderService#legacyTotal(long,long)",
                "com.acme.shop.order.OrderService#orderCount()",
                "com.acme.shop.order.OrderService#cancelOrder(String)");
        assertThat(JsonPath.<List<String>>read(file(analyseDemo(), "src/main/java/com/acme/shop/report/SalesReport.java"),
                "$.parts[*].category")).containsExactly("TARGET_MISSING", "MIGRATION_API");
        List<String> paths = JsonPath.read(analyseDemo(), "$.items[*].element");
        assertThat(paths).doesNotHaveDuplicates();
    }

    /** Boot renamed spring.redis.* to spring.data.redis.*: repo2's new key is found, its value decides. */
    @Test
    void renamedConfigKeysAreLookedUpUnderTheirNewName() throws Exception {
        Map<String, Object> host = item("spring.redis.host");
        assertThat(host).containsEntry("category", "CONFIG_CHANGED");
        assertThat(JsonPath.<List<String>>read(host, "$.evidence"))
                .containsExactly("renamed in Spring Boot: spring.redis.host -> spring.data.redis.host");
        assertThat(JsonPath.<List<String>>read(host, "$.details"))
                .contains("repo2 (spring.data.redis.host): localhost @ src/main/resources/application.yml");
        assertThat(item("spring.redis.port")).containsEntry("category", "CONFIG_IN_SYNC").containsEntry("level", "LOW");
        // repo2's new names are not reported again as keys only repo2 has
        assertThat(JsonPath.<List<?>>read(analyseDemo(), "$.items[*].parts[?(@.element =~ /spring\\.data\\.redis.*/)]")).isEmpty();
    }

    @Test
    void testCodeIsTagged() throws Exception {
        Map<String, Object> test = item("com.acme.shop.order.OrderServiceTest");
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
        assertThat(JsonPath.<String>read(item(json, "com.acme.shop.order.OrderService#cancelOrder(String)"), "$.target.repo"))
                .isEqualTo("repo2A");
        // the test class repo2B has: found there, one method short
        Map<String, Object> test = item(json, "com.acme.shop.order.OrderServiceTest#unknownOrderIsNotCancelled()");
        assertThat(test).containsEntry("category", "TARGET_MISSING").containsEntry("test", true);
        assertThat(JsonPath.<String>read(test, "$.target.repo")).isEqualTo("repo2B");
        // a config key repo2B holds in another file, same value: no difference left
        assertThat(JsonPath.<List<?>>read(json, "$.items[*].parts[?(@.element == 'shop.order.retry.enabled')]")).isEmpty();
        assertThat(JsonPath.<Integer>read(json, "$.counts.total")).isEqualTo(13);
        assertThat(JsonPath.<Integer>read(json, "$.counts.high")).isEqualTo(6);
    }

    /** repo1's .vm drafted in FreeMarker and diffed with repo2B's .ftl: only the real difference remains. */
    @Test
    void velocityTemplatesAreComparedWithTheirFreeMarkerCopy() throws Exception {
        String json = analyseMerged();
        Map<String, Object> invoice = item(json, "src/main/resources/templates/invoice.vm");
        assertThat(invoice).containsEntry("level", "MEDIUM").containsEntry("category", "TEMPLATE_CONVERTED")
                .containsEntry("targetState", "RELOCATED");
        assertThat(JsonPath.<String>read(invoice, "$.target.path")).isEqualTo("src/main/resources/templates/invoice.ftl");
        assertThat(JsonPath.<List<String>>read(invoice, "$.details")).containsSubsequence(
                "- L2: <p>Customer: ${invoice.customer!}</p>",
                "- L3: <p>Due: ${invoice.dueDate}</p>",
                "+ L2: <p>Customer: ${invoice.customer}</p>");
        // the .ftl is the .vm's counterpart, not a file only repo2 has
        assertThat(JsonPath.<List<?>>read(json, "$.items[?(@.element =~ /.*invoice\\.ftl/)]")).isEmpty();
        // a .vm while repo2 only has .ftl: to be written as FreeMarker
        Map<String, Object> receipt = item(json, "src/main/resources/templates/receipt.vm");
        assertThat(receipt).containsEntry("category", "TEMPLATE_CONVERTED").containsEntry("targetState", "MISSING");
        assertThat(JsonPath.<List<String>>read(receipt, "$.details"))
                .contains("≈ L1: <p>Receipt ${receipt.id} for order ${receipt.orderId}</p>");
    }

    @Test
    void skipTestsLeavesTestCodeOut() throws Exception {
        var d = demo.view();
        String json = analyse("""
                {"repo1Path": "%s", "repo2Path": "%s", "repo2bPath": "%s", "skipTests": true}
                """.formatted(d.repo1Path(), d.repo2Path(), d.repo2bPath()));
        assertThat(JsonPath.<List<Boolean>>read(json, "$.items[*].test")).isNotEmpty().doesNotContain(true);
        assertThat(JsonPath.<Boolean>read(json, "$.source.skipTests")).isTrue();
        assertThat(JsonPath.<List<String>>read(json, "$.warnings")).contains("1 changed test file(s) skipped (skipTests)");
        assertThat(JsonPath.<Integer>read(json, "$.counts.total")).isEqualTo(12);
    }

    @Test
    void foldersWithRepo2bGiveTheSameItemsAsGit() throws Exception {
        var d = demo.view();
        String folders = mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "repo2Path": "%s", "repo2bPath": "%s"}
                                """.formatted(d.repo1Folder(), d.repo2Folder(), d.repo2bFolder())))
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
        assertThat(total).isEqualTo(levels.size()).isEqualTo(13);
        assertThat(JsonPath.<Integer>read(json, "$.counts.high")).isEqualTo(8);
        assertThat(JsonPath.<String>read(json, "$.source.repo1Head.revision")).isEqualTo("HEAD");
        assertThat(JsonPath.<List<?>>read(json, "$.refactorings")).isNotEmpty();
        assertThat(JsonPath.<List<String>>read(json, "$.warnings")).containsExactly(
                "3 differing file(s) have nothing to report: imports, formatting, comments, Camel routes kept in"
                        + " another file, or the migration's own API rewrite only");
    }

    @Test
    void repositoryOutsideTheAllowedRootsIsRefused() throws Exception {
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "/", "repo2Path": "/"}
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
                .andExpect(jsonPath("$.recentCommits.length()").value(1))
                .andExpect(jsonPath("$.recentCommits[0].message").value(containsString("cancel reason")));
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

    /**
     * Folder mode over the same versions as plain directories: no git, and exactly the same items as git mode — the
     * trees differ, the analysis does not.
     */
    @Test
    void folderModeGivesTheSameItemsAsGitMode() throws Exception {
        var d = demo.view();
        String folders = mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "repo2Path": "%s"}
                                """.formatted(d.repo1Folder(), d.repo2Folder())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.mode").value("DIRECTORY"))
                .andExpect(jsonPath("$.source.repo1Head").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(folders, "$.items[*].id"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(analyseDemo(), "$.items[*].id"));
        assertThat(JsonPath.<List<String>>read(folders, "$.items[*].level"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(analyseDemo(), "$.items[*].level"));
    }

    @Test
    void unknownBranchIsABadRequestOnItsField() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "repo1Head": "no-such-branch", "repo2Path": "%s"}
                                """.formatted(d.repo1Path(), d.repo2Path())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_repository"))
                .andExpect(jsonPath("$.message").value(containsString("no-such-branch")))
                .andExpect(jsonPath("$.fields[0].field").value("repo1Head"));
    }

    @Test
    void missingFieldsAreValidated() throws Exception {
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
