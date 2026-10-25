package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param baseUrl        everything before {@code /rest/api/2}
 * @param projectKey     the project findings are looked up in and created in
 * @param issueType      issue type name for created issues; must exist in the project's scheme
 * @param summaryPrefix  prepended to a created issue's summary; not used for matching
 * @param labels         added to every created issue (the CVE id is added as well)
 * @param browseUrl      template for issue links, {@code {key}} is replaced
 * @param jqlBatchSize   findings per search; each adds one {@code summary ~} clause, and a long JQL
 *                       is slow on Jira's side and can exceed its URL/body limits
 * @param pageSize       {@code maxResults} per search page (Jira caps it, usually at 100)
 * @param sslBundle      see {@link ConfluenceProperties#sslBundle()}
 */
@Validated
@ConfigurationProperties("veracode.jira")
public record JiraProperties(
        @NotBlank String baseUrl,
        @NotBlank String projectKey,
        @DefaultValue("Bug") String issueType,
        @DefaultValue("[Veracode]") String summaryPrefix,
        @DefaultValue("veracode") List<String> labels,
        @NotBlank String browseUrl,
        @DefaultValue("40") @Min(1) @Max(200) int jqlBatchSize,
        @DefaultValue("100") @Min(1) @Max(1000) int pageSize,
        @DefaultValue RemoteAuth auth,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        String sslBundle) {

    public String browse(String key) {
        return browseUrl.replace("{key}", key);
    }
}
