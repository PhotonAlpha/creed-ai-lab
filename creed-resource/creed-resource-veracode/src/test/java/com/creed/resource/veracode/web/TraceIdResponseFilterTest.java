package com.creed.resource.veracode.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Through the whole filter chain (MockMvc with the context's filters): Boot's observation filter, then this one. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceIdResponseFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void everyResponseNamesItsTrace() throws Exception {
        MvcResult first = mvc.perform(get("/api/veracode/settings")).andReturn();
        MvcResult second = mvc.perform(get("/api/veracode/settings")).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("X-Trace-Id")).matches("[0-9a-f]{32}");
        // one trace per request, not one per process
        assertThat(second.getResponse().getHeader("X-Trace-Id")).isNotEqualTo(first.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    void errorResponsesNameItToo() throws Exception {
        // no X-Jira-Token: the exception handler answers 400 jira_token_required
        MvcResult refused = mvc.perform(post("/api/veracode/jira/connection")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getHeader("X-Trace-Id")).matches("[0-9a-f]{32}");
    }
}
