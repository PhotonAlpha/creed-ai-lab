package com.creed.resource.veracode.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Uploaded reports are kept in memory so that "create", "export" and re-analysis can re-read them by id.
 * They are lost on restart.
 *
 * @param ttl         how long after upload an id still resolves
 * @param maxEntries  the oldest upload is dropped beyond this
 * @param maxUnzippedSize   a {@code .zip} upload's XML files together, inflated - the multipart limit only bounds
 *                          the compressed size, and XML compresses some 20:1
 * @param maxArchiveReports {@code .xml} files one {@code .zip} may hold
 */
@Validated
@ConfigurationProperties("veracode.upload")
public record UploadProperties(@DefaultValue("2h") Duration ttl, @DefaultValue("50") @Min(1) int maxEntries,
                               @DefaultValue("500MB") DataSize maxUnzippedSize,
                               @DefaultValue("50") @Min(1) int maxArchiveReports) {
}
