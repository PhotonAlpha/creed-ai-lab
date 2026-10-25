package com.creed.resource.veracode.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param baseUrl        everything before {@code /rest/api} - {@code https://wiki.example.com} (Data
 *                       Center) or {@code https://example.atlassian.net/wiki} (Cloud)
 * @param pageId         the page the UI opens with; the user may type another
 * @param browseUrl      template for the page link shown in the UI, {@code {id}} is replaced. Only used
 *                       when the page JSON carries no {@code _links.webui}
 * @param sslBundle      {@code spring.ssl.bundle.*} name whose trust (and key) material the client uses
 *                       for an {@code https} base URL; blank = the JDK's default trust store
 */
@Validated
@ConfigurationProperties("veracode.confluence")
public record ConfluenceProperties(
        @NotBlank String baseUrl,
        String pageId,
        String browseUrl,
        @DefaultValue RemoteAuth auth,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        String sslBundle) {
}
