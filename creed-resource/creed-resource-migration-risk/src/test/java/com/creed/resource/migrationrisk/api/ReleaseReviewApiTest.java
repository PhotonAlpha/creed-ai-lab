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

    private String analyse(String release) throws Exception {
        var d = demo.view();
        return mvc.perform(post("/api/migration-risk/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "release": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), release)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long itemId(String json, String element) {
        List<Integer> ids = JsonPath.read(json, "$.items[?(@.element == '" + element + "')].review.itemId");
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
        assertThat(JsonPath.<List<String>>read(first, "$.items[*].review.status")).hasSize(12).containsOnly("PENDING");

        long missing = itemId(first, "com.acme.shop.order.OrderService#legacyTotal(long,long)");
        long cancel = itemId(first, "com.acme.shop.order.OrderService#cancelOrder(String)");
        long count = itemId(first, "com.acme.shop.order.OrderService#orderCount()");

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

        // re-run: unchanged items keep their verdict, marked as carried over
        String second = analyse(release);
        assertThat(((Number) JsonPath.read(second, "$.analysisId")).longValue()).isNotEqualTo(analysisId);
        List<String> carried = JsonPath.read(second,
                "$.items[?(@.element == 'com.acme.shop.order.OrderService#legacyTotal(long,long)')].review.status");
        assertThat(carried).containsExactly("ATTENTION");
        List<Integer> from = JsonPath.read(second,
                "$.items[?(@.element == 'com.acme.shop.order.OrderService#legacyTotal(long,long)')].review.carriedOverFrom");
        assertThat(from).containsExactly((int) missing);
        assertThat(JsonPath.<List<String>>read(second, "$.items[?(@.review.status == 'PENDING')]")).hasSize(9);

        long releaseId = ((Number) JsonPath.<List<Object>>read(releases, "$[?(@.name == '" + release + "')].id").getFirst()).longValue();
        mvc.perform(get("/api/migration-risk/releases/{id}/analyses", releaseId))
                .andExpect(jsonPath("$.length()").value(2));
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
