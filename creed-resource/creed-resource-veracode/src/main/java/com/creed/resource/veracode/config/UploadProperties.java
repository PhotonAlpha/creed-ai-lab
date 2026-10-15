package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Uploaded reports are kept in memory so that "create" and "export" can re-read them by id, the way a
 * page is re-read from Confluence. They are lost on restart.
 *
 * @param ttl         how long after upload an id still resolves
 * @param maxEntries  the oldest upload is dropped beyond this
 */
@Validated
@ConfigurationProperties("veracode.upload")
public record UploadProperties(@DefaultValue("2h") Duration ttl, @DefaultValue("50") @Min(1) int maxEntries) {
}
