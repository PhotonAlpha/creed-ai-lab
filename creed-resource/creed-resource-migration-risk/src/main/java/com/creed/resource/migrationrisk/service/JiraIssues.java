package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties.Jira;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates a Jira issue (REST API v2, {@code POST /rest/api/2/issue}). With {@code migration-risk.jira.mock} the
 * key is made up in memory ({@code MIG-101}, ...) and nothing leaves the process — the demo and the tests run
 * without a Jira. The token comes from configuration ({@code CREED_MIGRATION_RISK_JIRA_TOKEN}), never the request.
 */
@Component
@Slf4j
public class JiraIssues {

    private final Jira config;
    private final RestClient client;
    private final AtomicInteger mockKeys = new AtomicInteger(100);

    public JiraIssues(MigrationRiskProperties properties) {
        this.config = properties.jira();
        if (config.mock() || config.baseUrl() == null || config.baseUrl().isBlank()) {
            this.client = null;
            return;
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        RestClient.Builder builder = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory);
        if (config.token() != null && !config.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.token());
        }
        this.client = builder.build();
    }

    public boolean mock() {
        return client == null;
    }

    /** The page links {@code browseUrl + key}. */
    public String browseUrl() {
        if (config.browseUrl() != null && !config.browseUrl().isBlank()) {
            return config.browseUrl();
        }
        return client == null ? null : config.baseUrl().replaceAll("/+$", "") + "/browse/";
    }

    /** @return the new issue's key */
    public String create(String summary, String description, List<String> labels) {
        if (client == null) {
            String key = config.projectKey() + "-" + mockKeys.incrementAndGet();
            log.info("jira (mock): {} {}", key, summary);
            return key;
        }
        Map<String, Object> fields = Map.of("project", Map.of("key", config.projectKey()),
                "issuetype", Map.of("name", config.issueType()), "summary", summary,
                "description", description, "labels", labels);
        try {
            Map<?, ?> created = client.post().uri("/rest/api/2/issue").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fields", fields)).retrieve().body(Map.class);
            Object key = created == null ? null : created.get("key");
            if (key == null) {
                throw new JiraException("Jira answered without an issue key");
            }
            return key.toString();
        } catch (RestClientException e) {
            throw new JiraException("Jira refused the issue: " + e.getMessage());
        }
    }

    public static class JiraException extends RuntimeException {
        public JiraException(String message) {
            super(message);
        }
    }
}
