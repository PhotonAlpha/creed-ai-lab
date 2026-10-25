package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.demo.DemoRepositories;
import com.creed.resource.migrationrisk.service.Progress;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Background analyses: start, poll, SSE replay, early validation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisJobsApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRepositories demo;

    private String start(String release) throws Exception {
        var d = demo.view();
        String body = mvc.perform(post("/api/migration-risk/analyses/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "%s", "repo2Path": "%s", "release": "%s"}
                                """.formatted(d.repo1Path(), d.baseline(), d.repo2Path(), release)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.jobId");
    }

    private String awaitDone(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String view = mvc.perform(get("/api/migration-risk/analyses/jobs/{id}", jobId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String status = JsonPath.read(view, "$.status");
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return view;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    @Test
    void jobRunsInTheBackgroundAndKeepsItsResult() throws Exception {
        String view = awaitDone(start("JOB-" + UUID.randomUUID()));
        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<Integer>read(view, "$.result.counts.total")).isEqualTo(12);
        assertThat(JsonPath.<Object>read(view, "$.result.analysisId")).isNotNull();
        assertThat(JsonPath.<String>read(view, "$.progress.stage")).isEqualTo("DONE");
        assertThat(JsonPath.<Integer>read(view, "$.progress.percent")).isEqualTo(100);
    }

    /** Subscribing after the fact replays every stage, then the terminal event, and closes the stream. */
    @Test
    void eventStreamReplaysStagesThenDone() throws Exception {
        String jobId = start("SSE-" + UUID.randomUUID());
        awaitDone(jobId);
        MvcResult async = mvc.perform(get("/api/migration-risk/analyses/jobs/{id}/events", jobId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String stream = mvc.perform(asyncDispatch(async)).andReturn().getResponse().getContentAsString();
        assertThat(stream)
                .contains("event:stage", "\"stage\":\"RESOLVE\"", "\"stage\":\"DIFF\"", "\"stage\":\"EXPORT\"",
                        "\"stage\":\"UPSTREAM\"", "\"stage\":\"MIGRATION\"", "\"stage\":\"SAVE\"", "\"stage\":\"DONE\"",
                        "event:done");
        assertThat(stream.indexOf("\"stage\":\"UPSTREAM\"")).isLessThan(stream.indexOf("\"stage\":\"MIGRATION\""));
    }

    @Test
    void aWrongBaselineIsRefusedBeforeAnyJobStarts() throws Exception {
        var d = demo.view();
        mvc.perform(post("/api/migration-risk/analyses/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repo1Path": "%s", "baseline": "no-such-tag", "repo2Path": "%s"}
                                """.formatted(d.repo1Path(), d.repo2Path())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("baseline"));
    }

    @Test
    void folderJobsWorkToo() throws Exception {
        var d = demo.view();
        String body = mvc.perform(post("/api/migration-risk/analyses/directories/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baselinePath": "%s", "repo1Path": "%s", "repo2Path": "%s"}
                                """.formatted(d.baselineFolder(), d.repo1Folder(), d.repo2Folder())))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String view = awaitDone(JsonPath.read(body, "$.jobId"));
        assertThat(JsonPath.<Integer>read(view, "$.result.counts.total")).isEqualTo(12);
        assertThat(JsonPath.<Object>read(view, "$.result.analysisId")).isNull();
    }

    @Test
    void unknownJobIs404() throws Exception {
        mvc.perform(get("/api/migration-risk/analyses/jobs/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("no analysis job")));
    }

    @Test
    void stageBandsCoverZeroToHundred() {
        assertThat(Progress.Stage.RESOLVE.start()).isZero();
        assertThat(Progress.Stage.JAVA.percent(5, 10)).isBetween(Progress.Stage.JAVA.start(), Progress.Stage.JAVA.end());
        assertThat(Progress.Stage.DONE.end()).isEqualTo(100);
    }
}
