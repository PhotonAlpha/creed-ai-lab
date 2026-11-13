package com.creed.resource.veracode.client.jira;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The search page as Jira sends it, read as the module's mapper reads it ({@code use-jackson2-defaults}). */
class JiraSearchResultTest {

    private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    @Test
    void cloudsLastPageSaysSo() {
        JiraSearchResult page = JSON.readValue("""
                {"issues":[{"key":"DT-1","fields":{"summary":"x"}}],"isLast":true}""", JiraSearchResult.class);

        assertThat(page.isLast()).isTrue();
        assertThat(page.nextPageToken()).isNull();
        assertThat(page.issuesOrEmpty()).hasSize(1);
    }

    @Test
    void cloudsOtherPagesNameTheNext() {
        JiraSearchResult page = JSON.readValue("""
                {"issues":[{"key":"DT-1","fields":{"summary":"x"}}],"isLast":false,"nextPageToken":"Ch0jU3"}""",
                JiraSearchResult.class);

        assertThat(page.isLast()).isFalse();
        assertThat(page.nextPageToken()).isEqualTo("Ch0jU3");
    }
}
