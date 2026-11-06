package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Where reports can be read from besides an upload: a Confluence page's attachments. No credentials - as with
 * Jira, every call carries the token the user typed on the page ({@code X-Confluence-Token}).
 *
 * @param baseUrl         everything before {@code /rest/api}: {@code https://acme.atlassian.net/wiki} on Cloud, the
 *                        context root ({@code https://wiki.acme.com} or {@code .../confluence}) on Data Center. Blank =
 *                        the page offers no Confluence source
 * @param pageSize        {@code limit} per page of the attachment listing
 * @param maxDownloadSize an attachment larger than this (by the size Confluence lists) is not downloaded - it is read
 *                        into memory whole
 * @param readTimeout     downloads included: a 50 MB attachment over a slow link takes a while
 */
@Validated
@ConfigurationProperties("veracode.confluence")
public record ConfluenceProperties(
        String baseUrl,
        @DefaultValue("50") @Min(1) @Max(200) int pageSize,
        @DefaultValue("50MB") DataSize maxDownloadSize,
        @DefaultValue("10") @Min(1) @Max(100) int maxConnections,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout) {

    public ConfluenceProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim().replaceAll("/+$", "");
    }

    public boolean enabled() {
        return baseUrl != null;
    }

    /** The token dialog's preselected deployment, as for Jira: {@code *.atlassian.net} is Cloud. */
    public boolean looksLikeCloud() {
        String host = enabled() ? URI.create(baseUrl).getHost() : null;
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(".atlassian.net");
    }

    /** The page as a person opens it - same path on Cloud and Data Center. */
    public String pageUrl(String pageId) {
        return baseUrl + "/pages/viewpage.action?pageId=" + pageId;
    }
}
