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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saving under a release, reviewing items, and carrying verdicts over to the next run of the same release. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseReviewApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRepositories demo;

    private static final String ORDER_SERVICE = "src/main/java/com/acme/shop/order/OrderService.java";

    private String analyse(String release) throws Exception {
        var d = demo.view();
        return mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "release": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), release)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The saved record of one file. */
    private static long itemId(String json, String path) {
        List<Integer> ids = JsonPath.read(json, "$.items[?(@.element == '" + path + "')].review.itemId");
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    @Test
    void withoutAReleaseNothingIsSaved() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path())))
                .andExpect(jsonPath("$.analysisId").doesNotExist())
                .andExpect(jsonPath("$.items[0].review").doesNotExist());
    }

    @Test
    void savedRunIsListedLoadedAndReviewed() throws Exception {
        String release = "R-" + UUID.randomUUID();
        String first = analyse(release);
        long analysisId = ((Number) JsonPath.read(first, "$.analysisId")).longValue();
        assertThat(JsonPath.<String>read(first, "$.release")).isEqualTo(release);
        assertThat(JsonPath.<List<String>>read(first, "$.items[*].review.status")).hasSize(13).containsOnly("PENDING", "SYNCED");
        // repo2 already has OrderValidator's fix: SYNCED by the analysis itself
        assertThat(JsonPath.<List<Boolean>>read(first,
                "$.items[?(@.element == 'src/main/java/com/acme/shop/order/OrderValidator.java')].review.auto")).containsExactly(true);

        long missing = itemId(first, ORDER_SERVICE);
        long cancel = itemId(first, "src/main/java/com/acme/shop/payment/PaymentClient.java");
        long count = itemId(first, "pom.xml");

        mvc.perform(put("/api/migration-risk/items/{id}/review", missing).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "ATTENTION", "note": "port the new guard to PriceCalculator", "reviewer": "ethan"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("ATTENTION"))
                .andExpect(jsonPath("$.review.reviewer").value("ethan"))
                .andExpect(jsonPath("$.review.reviewedAt").exists());
        mvc.perform(put("/api/migration-risk/items/review").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemIds": [%d, %d], "status": "SAFE", "reviewer": "ethan"}
                                """.formatted(cancel, count)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // a reload shows the reviews; the release list shows the progress
        String loaded = mvc.perform(get("/api/migration-risk/analyses/{id}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreamRefactorings.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(loaded,
                "$.items[?(@.review.status == 'SAFE')].element")).hasSize(2);
        String releases = mvc.perform(get("/api/migration-risk/releases")).andReturn().getResponse().getContentAsString();
        List<Integer> progress = JsonPath.read(releases,
                "$[?(@.name == '" + release + "')].latest.review.safe");
        assertThat(progress).containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(releases, "$[?(@.name == '" + release + "')].latest.review.synced"))
                .containsExactly(1);

        // re-run: unchanged items keep their verdict, marked as carried over
        String second = analyse(release);
        assertThat(((Number) JsonPath.read(second, "$.analysisId")).longValue()).isNotEqualTo(analysisId);
        List<String> carried = JsonPath.read(second,
                "$.items[?(@.element == '" + ORDER_SERVICE + "')].review.status");
        assertThat(carried).containsExactly("ATTENTION");
        List<Integer> from = JsonPath.read(second,
                "$.items[?(@.element == '" + ORDER_SERVICE + "')].review.carriedOverFrom");
        assertThat(from).containsExactly((int) missing);
        assertThat(JsonPath.<List<String>>read(second, "$.items[?(@.review.status == 'PENDING')]")).hasSize(9);

        long releaseId = ((Number) JsonPath.<List<Object>>read(releases, "$[?(@.name == '" + release + "')].id").getFirst()).longValue();
        mvc.perform(get("/api/migration-risk/releases/{id}/analyses", releaseId))
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * Sync tracking across runs: the second run of a release says what is NEW / CHANGED / SAME, what is resolved,
     * and a file whose content changed after its review comes back PENDING with that review and the changed parts.
     */
    @Test
    void reRunsTrackDeltaResolvedAndChangedParts() throws Exception {
        var d = demo.view();
        String release = "S-" + UUID.randomUUID();
        String first = analyse(release);
        long yml = itemId(first, "src/main/resources/application.yml");
        mvc.perform(put("/api/migration-risk/items/{id}/review", yml).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SAFE\", \"note\": \"keys checked\", \"reviewer\": \"ethan\"}"))
                .andExpect(status().isOk());
        // second run: repo2B added (retry.enabled is found there), tests skipped (OrderServiceTest disappears)
        String second = mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "repo2bPath": "%s",
                                 "skipTests": true, "release": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), d.repo2bPath(), release)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ymlRow = "$.items[?(@.element == 'src/main/resources/application.yml')]";
        assertThat(JsonPath.<List<String>>read(second, ymlRow + ".review.status")).containsExactly("PENDING");
        assertThat(JsonPath.<List<String>>read(second, ymlRow + ".review.delta")).containsExactly("CHANGED");
        assertThat(JsonPath.<List<String>>read(second, ymlRow + ".review.previous.status")).containsExactly("SAFE");
        assertThat(JsonPath.<List<String>>read(second, ymlRow + ".review.previous.note")).containsExactly("keys checked");
        // only the key whose state changed is flagged, not the whole file
        assertThat(JsonPath.<List<List<String>>>read(second, ymlRow + ".review.changedParts").getFirst())
                .containsExactly("CONFIG_IN_SYNC:CONFIG:shop.order.retry.enabled");
        assertThat(JsonPath.<List<String>>read(second,
                "$.items[?(@.element == 'pom.xml')].review.delta")).containsExactly("SAME");
        long secondId = ((Number) JsonPath.read(second, "$.analysisId")).longValue();
        mvc.perform(get("/api/migration-risk/analyses/{id}/resolved", secondId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].element").value("src/test/java/com/acme/shop/order/OrderServiceTest.java"));
        // a reviewer can mark SYNCED by hand (a template re-written in FreeMarker)
        long vm = itemId(second, "src/main/resources/templates/invoice.vm");
        mvc.perform(put("/api/migration-risk/items/{id}/review", vm).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SYNCED\"}"))
                .andExpect(jsonPath("$.review.status").value("SYNCED"))
                .andExpect(jsonPath("$.review.auto").value(false));
    }

    @Test
    void jiraIssueIsCreatedOncePerItem() throws Exception {
        String saved = analyse("J-" + UUID.randomUUID());
        long id = itemId(saved, ORDER_SERVICE);
        String key = JsonPath.read(mvc.perform(post("/api/migration-risk/items/{id}/jira", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.review.jiraKey");
        assertThat(key).startsWith("MIG-");
        mvc.perform(post("/api/migration-risk/items/{id}/jira", id))
                .andExpect(jsonPath("$.review.jiraKey").value(key));
    }

    /** repo2B, the target's repo label and the module / test tags survive the round trip through the database. */
    @Test
    void savedRunWithRepo2bKeepsTagsAndRepoLabels() throws Exception {
        var d = demo.view();
        String release = "B-" + UUID.randomUUID();
        String saved = mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "repo2bPath": "%s", "release": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), d.repo2bPath(), release)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long analysisId = ((Number) JsonPath.read(saved, "$.analysisId")).longValue();
        String loaded = mvc.perform(get("/api/migration-risk/analyses/{id}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.repo2bPath").value(d.repo2bPath()))
                .andReturn().getResponse().getContentAsString();
        String test = "$.items[?(@.element == 'src/test/java/com/acme/shop/order/OrderServiceTest.java')]";
        assertThat(JsonPath.<List<String>>read(loaded, test + ".target.repo")).containsExactly("repo2B");
        assertThat(JsonPath.<List<Boolean>>read(loaded, test + ".test")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(loaded, test + ".module")).containsExactly("(root)");
        mvc.perform(get("/api/migration-risk/releases"))
                .andExpect(jsonPath("$[?(@.name == '" + release + "')].latest.repo2bPath").value(d.repo2bPath()));
    }

    @Test
    void reviewingAnUnknownItemIs404() throws Exception {
        mvc.perform(put("/api/migration-risk/items/{id}/review", 999_999).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SAFE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void reviewStatusIsValidated() throws Exception {
        mvc.perform(put("/api/migration-risk/items/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\": [], \"status\": \"SAFE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void folderModeRunsAreSavedToo() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses/directories").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baselinePath": "%s", "repo1Path": "%s", "repo2Path": "%s", "release": "%s"}
                                """.formatted(d.baselineFolder(), d.repo1Folder(), d.repo2Folder(), "F-" + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").exists())
                .andExpect(jsonPath("$.source.mode").value("DIRECTORY"));
    }

    @Test
    void demoBaselineTagIsStillTheBaseline() {
        assertThat(DemoRepositories.BASELINE_TAG).isEqualTo("migration-baseline");
    }
}
